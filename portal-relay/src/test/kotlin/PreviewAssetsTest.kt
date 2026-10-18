import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** K5: the editor canvas's image pass-through (`/preview-asset`). */
class PreviewAssetsTest {
  private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
  private val hits = AtomicInteger()
  private val seenHeaders = mutableListOf<Map<String, List<String>>>()

  // Released by each test's end: a /hang request waits for it.
  private val release = CountDownLatch(1)

  private val upstream: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
    // Its own threads: one route sleeping must not hold the others.
    executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    fun route(path: String, status: Int, type: String?, body: ByteArray = ByteArray(0), location: String? = null) {
      createContext(path) { ex ->
        hits.incrementAndGet()
        synchronized(seenHeaders) { seenHeaders += ex.requestHeaders.mapKeys { it.key.lowercase() } }
        type?.let { ex.responseHeaders.add("Content-Type", it) }
        location?.let { ex.responseHeaders.add("Location", it) }
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
        ex.close()
      }
    }
    route("/img.png", 200, "image/png", png)
    route("/img2.png", 200, "image/png; charset=binary", png)
    route("/img3.png", 200, "image/png", png)
    route("/page", 200, "text/html", "<html></html>".encodeToByteArray())
    route("/vector.svg", 200, "image/svg+xml", "<svg/>".encodeToByteArray())
    route("/big.png", 200, "image/png", ByteArray(5_000))
    route("/missing.png", 404, "text/plain", "no".encodeToByteArray())
    start()
  }
  private val port get() = upstream.address.port
  private val base get() = "http://localhost:$port"

  init {
    upstream.createContext("/redirect-ok") { ex ->
      ex.responseHeaders.add("Location", "/img.png"); ex.sendResponseHeaders(302, -1); ex.close()
    }
    upstream.createContext("/redirect-off") { ex ->
      ex.responseHeaders.add("Location", "http://127.0.0.1:$port/img.png"); ex.sendResponseHeaders(302, -1); ex.close()
    }
    upstream.createContext("/redirect-loop") { ex ->
      ex.responseHeaders.add("Location", "/redirect-loop"); ex.sendResponseHeaders(302, -1); ex.close()
    }
    // Waits until the test releases it, then answers an image.
    upstream.createContext("/hang") { ex ->
      hits.incrementAndGet()
      release.await(20, TimeUnit.SECONDS)
      ex.responseHeaders.add("Content-Type", "image/png")
      runCatching { ex.sendResponseHeaders(200, png.size.toLong()); ex.responseBody.use { it.write(png) } }
      ex.close()
    }
    // /hop/N: 400 ms, then a redirect to /hop/N+1; /hop/5 is the image.
    upstream.createContext("/hop/") { ex ->
      hits.incrementAndGet()
      Thread.sleep(400)
      val n = ex.requestURI.path.substringAfterLast('/').toInt()
      runCatching {
        if (n >= PreviewAssets.MAX_REDIRECTS) {
          ex.responseHeaders.add("Content-Type", "image/png")
          ex.sendResponseHeaders(200, png.size.toLong()); ex.responseBody.use { it.write(png) }
        } else {
          ex.responseHeaders.add("Location", "/hop/${n + 1}"); ex.sendResponseHeaders(302, -1)
        }
      }
      ex.close()
    }
  }

  private var now = 0L
  private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
  private val created = mutableListOf<PreviewAssets>()

  // The stub upstream listens on an ephemeral port: allowed here via extraPorts.
  private fun assets(
    hosts: List<String> = listOf("localhost"),
    entries: Int = 256,
    deadlineMillis: Long = 10_000,
    threads: Int = 4,
    queue: Int = 16,
    client: OkHttpClient = PreviewAssets.defaultClient(),
  ) = PreviewAssets(
    hosts, client = client, maxBytes = 1_000, cacheMaxEntries = entries, deadlineMillis = deadlineMillis,
    threads = threads, queue = queue, extraPorts = setOf(port), clock = { now }, log = { logs += it },
  ).also { created += it }

  /** A client that never touches the network: [answer] maps each request to a response. */
  private val fakeSeen = java.util.Collections.synchronizedList(mutableListOf<String>())
  private fun fake(answer: (okhttp3.Request) -> okhttp3.Response.Builder): OkHttpClient =
    PreviewAssets.defaultClient().newBuilder().addInterceptor { chain ->
      fakeSeen += chain.request().url.toString()
      answer(chain.request()).request(chain.request()).protocol(Protocol.HTTP_1_1).message("x").build()
    }.build()

  private fun image(vararg types: String) = okhttp3.Response.Builder().code(200).apply {
    types.forEach { addHeader("Content-Type", it) }
  }.body(png.toResponseBody())

  @AfterTest fun stop() {
    release.countDown()
    created.forEach { it.close() }
    upstream.stop(0)
  }

  @Test fun aListedHostsImageIsServedWithItsType() {
    val r = assertIs<PreviewAssets.Result.Image>(assets().fetch("$base/img.png"))
    assertEquals("image/png", r.contentType)
    assertContentEquals(png, r.bytes)
    // Parameters are dropped from the type.
    assertEquals("image/png", assertIs<PreviewAssets.Result.Image>(assets().fetch("$base/img2.png")).contentType)
  }

  @Test fun disabledWithoutHosts() {
    val off = assets(hosts = emptyList())
    assertFalse(off.enabled)
    assertEquals(404, assertIs<PreviewAssets.Result.Refused>(off.fetch("$base/img.png")).status)
    assertEquals(0, hits.get())
  }

  @Test fun onlyExactListedHostsAndHttpSchemes() {
    val a = assets(hosts = listOf("img.example.com"))
    for (url in listOf(
      "$base/img.png", // localhost is not listed here
      "https://cdn.img.example.com/x.png", // no subdomains
      "https://img.example.com.evil.test/x.png",
      "ftp://img.example.com/x.png",
      "https://user:pw@img.example.com/x.png",
      "not a url",
    )) {
      assertEquals(403, assertIs<PreviewAssets.Result.Refused>(a.fetch(url), url).status, url)
    }
    assertEquals(400, assertIs<PreviewAssets.Result.Refused>(a.fetch(null)).status)
    assertEquals(0, hits.get())
  }

  @Test fun onlyRasterImages() {
    val a = assets()
    assertEquals(415, assertIs<PreviewAssets.Result.Refused>(a.fetch("$base/page")).status)
    assertEquals(415, assertIs<PreviewAssets.Result.Refused>(a.fetch("$base/vector.svg")).status)
    assertEquals(502, assertIs<PreviewAssets.Result.Refused>(a.fetch("$base/missing.png")).status)
  }

  @Test fun sizeCap() {
    assertEquals(413, assertIs<PreviewAssets.Result.Refused>(assets().fetch("$base/big.png")).status)
  }

  @Test fun redirectsOnlyToListedHosts() {
    val a = assets()
    assertIs<PreviewAssets.Result.Image>(a.fetch("$base/redirect-ok"))
    val off = assertIs<PreviewAssets.Result.Refused>(a.fetch("$base/redirect-off"))
    assertEquals(403, off.status)
    assertEquals(502, assertIs<PreviewAssets.Result.Refused>(a.fetch("$base/redirect-loop")).status)
    // The off-list target was never requested.
    assertTrue(seenHeaders.none { it["host"]?.single()?.startsWith("127.0.0.1") == true })
  }

  @Test fun cacheServesRepeatsUntilItExpiresAndStaysBounded() {
    val a = assets(entries = 2)
    a.fetch("$base/img.png")
    a.fetch("$base/img.png")
    assertEquals(1, hits.get())
    now += 11 * 60 * 1000L // past the ten-minute TTL
    a.fetch("$base/img.png")
    assertEquals(2, hits.get())
    a.fetch("$base/img2.png")
    a.fetch("$base/img3.png")
    assertEquals(2, a.cacheSize())
    // img.png was the least recently used: it went first.
    a.fetch("$base/img3.png")
    assertEquals(4, hits.get())
    a.fetch("$base/img.png")
    assertEquals(5, hits.get())
  }

  @Test fun refusalsAreLoggedWithoutPathOrQuery() {
    assets().fetch("$base/vector.svg?token=s3cret")
    assertTrue(logs.isNotEmpty())
    assertTrue(logs.none { "s3cret" in it || "vector" in it }, "$logs")
    assertTrue(logs.all { "localhost" in it })
  }

  // ── the HTTP contract, through a real server like the relay's ─────────────

  // Like the relay: behind its access gate (which owns CORS), the default
  // executor (one dispatcher thread for every endpoint) and another endpoint
  // beside /preview-asset.
  private fun relay(a: PreviewAssets): HttpServer =
    RelayAccess.gate(HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)).apply {
      createContext("/preview-asset") { ex -> a.serve(ex) }
      createContext("/ping") { ex ->
        ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("ok".encodeToByteArray()) }; ex.close()
      }
      executor = null
      start()
    }

  private class Reply(val code: Int, val headers: okhttp3.Headers, val body: ByteArray) {
    val contentType get() = headers["Content-Type"]
    fun getHeaderField(name: String): String? = headers[name]
  }

  // OkHttp, not HttpURLConnection: the JDK client silently drops `Origin`.
  private fun call(
    server: HttpServer,
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    path: String = "/preview-asset?url=" + URLEncoder.encode(url, "UTF-8"),
    timeoutMillis: Long = 30_000,
  ): Reply {
    val target = "http://127.0.0.1:${server.address.port}$path"
    val body = if (method == "GET") null else ByteArray(0).toRequestBody()
    val request = okhttp3.Request.Builder().url(target).method(method, body)
    headers.forEach { (k, v) -> request.header(k, v) }
    okhttp3.OkHttpClient.Builder().followRedirects(false).callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
      .build().newCall(request.build()).execute().use {
        return Reply(it.code, it.headers, it.body.bytes())
      }
  }

  @Test fun clientHeadersAreNeverForwardedAndTheEditorOriginIsAllowed() {
    val server = relay(assets())
    try {
      val conn = call(
        server, "$base/img.png",
        headers = mapOf(
          "Origin" to "http://localhost:8096",
          "Cookie" to "session=abc",
          "Authorization" to "Bearer secret",
          "X-Api-Key" to "k",
          "Referer" to "http://localhost:8096/",
        ),
      )
      assertEquals(200, conn.code)
      assertEquals("image/png", conn.contentType)
      // Exactly one: two Access-Control-Allow-Origin values fail every browser fetch.
      assertEquals(listOf("http://localhost:8096"), conn.headers.values("Access-Control-Allow-Origin"))
      assertEquals(listOf("Origin"), conn.headers.values("Vary"))
      assertEquals("nosniff", conn.getHeaderField("X-Content-Type-Options"))
      assertContentEquals(png, conn.body)
      val seen = seenHeaders.single()
      for (h in listOf("cookie", "authorization", "x-api-key", "referer", "origin")) assertNull(seen[h], h)
      assertEquals(listOf(PreviewAssets.USER_AGENT), seen["user-agent"])
    } finally {
      server.stop(0)
    }
  }

  @Test fun aForeignOriginIsRefusedByTheGate() {
    val server = relay(assets())
    try {
      val conn = call(server, "$base/img.png", headers = mapOf("Origin" to "https://evil.example"))
      assertEquals(403, conn.code)
      assertNull(conn.getHeaderField("Access-Control-Allow-Origin"))
      assertEquals(0, hits.get())
    } finally {
      server.stop(0)
    }
  }

  @Test fun getOnlyAndOffWhenDisabled() {
    val server = relay(assets())
    val off = relay(assets(hosts = emptyList()))
    try {
      assertEquals(405, call(server, "$base/img.png", method = "POST").code)
      assertEquals(405, call(server, "$base/img.png", method = "DELETE").code)
      assertEquals(404, call(off, "$base/img.png").code)
      assertEquals(403, call(server, "https://elsewhere.example/x.png").code)
      assertEquals(0, hits.get())
    } finally {
      server.stop(0)
      off.stop(0)
    }
  }

  @Test fun configAcceptsHostNamesOnly() {
    assertEquals(listOf("img.example.com"), PortalConfig(previewAssetHosts = listOf("img.example.com")).previewAssetHosts)
    for (good in listOf("img.example.com", "a.co", "xn--bcher-kva.example", "0xab.example.com", "img2.example.com", "1a.example.com")) {
      assertTrue(isPreviewAssetHost(good), good)
    }
    for (bad in listOf(
      "*.example.com", "https://img.example.com", "img.example.com:443", "img.example.com/x", "Img.Example.com", "",
      "img.example.com.", // trailing dot
      // loopback by name, single labels
      "localhost", "foo.localhost", "a.b.localhost", "img", "intranet",
      // IPv4 in every form a URL parser accepts
      "10.0.0.1", "127.0.0.1", "127.1", "127.0.1", "2130706433", "0177.0.0.1", "017700000001",
      "0x7f.0.0.1", "0x7f000001", "0x7f.0x0.0x0.0x1", "1.2.3.4.5",
      // any all-numeric label, and a hex last label
      "123.example.com", "img.123", "img.example.0x1f",
      // IPv6
      "::1", "[::1]", "fe80::1", "::ffff:127.0.0.1",
    )) {
      assertFalse(isPreviewAssetHost(bad), bad)
      assertFailsWith<IllegalArgumentException>(bad) { PortalConfig(previewAssetHosts = listOf(bad)) }
    }
    assertFailsWith<IllegalArgumentException> { PortalConfig(previewAssetHosts = listOf("a.example", "a.example")) }
    assertTrue(PortalConfig().previewAssetHosts.isEmpty())
  }

  // ── ports ─────────────────────────────────────────────────────────────────

  @Test fun onlyTheSchemesDefaultPort() {
    val a = PreviewAssets(listOf("img.example.com"), client = fake { image("image/png") }, log = { logs += it })
      .also { created += it }
    for (ok in listOf(
      "http://img.example.com/x.png", "http://img.example.com:80/x.png",
      "https://img.example.com/x.png", "https://img.example.com:443/x.png",
    )) {
      assertIs<PreviewAssets.Result.Image>(a.fetch(ok), ok)
    }
    for (url in listOf(
      "http://img.example.com:6379/x", "http://img.example.com:443/x", "https://img.example.com:80/x",
      "https://img.example.com:8443/x",
    )) {
      assertEquals(403, assertIs<PreviewAssets.Result.Refused>(a.fetch(url), url).status, url)
    }
    for (url in listOf("http://img.example.com:0/x", "http://img.example.com:99999/x", "https://img.example.com:65536/x")) {
      assertEquals(400, assertIs<PreviewAssets.Result.Refused>(a.fetch(url), url).status, url)
    }
    assertEquals(4, fakeSeen.size, "$fakeSeen")
  }

  @Test fun aRedirectToAnotherPortIsRefused() {
    val a = PreviewAssets(
      listOf("img.example.com"),
      client = fake { r ->
        if (r.url.encodedPath == "/a") okhttp3.Response.Builder().code(302).header("Location", "http://img.example.com:6379/x").body("".toResponseBody())
        else image("image/png")
      },
      log = { logs += it },
    ).also { created += it }
    assertEquals(403, assertIs<PreviewAssets.Result.Refused>(a.fetch("http://img.example.com/a")).status)
    assertEquals(listOf("http://img.example.com/a"), fakeSeen.toList())
  }

  @Test fun anInvalidPortIsA400ThroughTheServer() {
    val server = relay(PreviewAssets(listOf("img.example.com"), client = fake { image("image/png") }).also { created += it })
    try {
      assertEquals(400, call(server, "http://img.example.com:99999/x").code)
      assertEquals(400, call(server, "http://img.example.com:0/x").code)
      assertTrue(fakeSeen.isEmpty())
    } finally {
      server.stop(0)
    }
  }

  // ── content types ─────────────────────────────────────────────────────────

  @Test fun onlyOneExactRasterTypeIsServedAsItsCanonicalName() {
    val served = mapOf(
      listOf("image/png") to "image/png",
      listOf("IMAGE/JPEG; charset=binary") to "image/jpeg",
      listOf("image/webp;q=1") to "image/webp",
      listOf(" image/gif ") to "image/gif",
      listOf("image/avif") to "image/avif",
      listOf("image/bmp") to "image/bmp",
      listOf("image/x-icon") to "image/x-icon",
      listOf("image/vnd.microsoft.icon") to "image/vnd.microsoft.icon",
    )
    for ((upstream, canonical) in served) assertEquals(canonical, PreviewAssets.rasterType(upstream), "$upstream")
    for (refused in listOf(
      emptyList(),
      listOf(""),
      listOf("image/png, text/html"),
      listOf("image/png,image/svg+xml"),
      listOf("text/html; image/png"),
      listOf("image/png; x=\"a,b\""), // a comma anywhere
      listOf("image/png", "text/html"), // two headers
      listOf("image/png", "image/png"),
      listOf("image/svg+xml"),
      listOf("image/svg"),
      listOf("image/pngx"),
      listOf("image/"),
      listOf("image/*"),
      listOf("text/html"),
      listOf("application/octet-stream"),
    )) {
      assertNull(PreviewAssets.rasterType(refused), "$refused")
    }
  }

  @Test fun theServerSendsTheCanonicalTypeNotTheUpstreamString() {
    val types = mapOf("/a" to listOf("IMAGE/PNG; charset=\"x\""), "/b" to listOf("image/png, text/html"), "/c" to listOf("image/png", "text/html"))
    val a = PreviewAssets(listOf("img.example.com"), client = fake { r -> image(*types.getValue(r.url.encodedPath).toTypedArray()) })
      .also { created += it }
    val server = relay(a)
    try {
      val ok = call(server, "https://img.example.com/a")
      assertEquals(200, ok.code)
      assertEquals(listOf("image/png"), ok.headers.values("Content-Type"))
      assertEquals("nosniff", ok.getHeaderField("X-Content-Type-Options"))
      assertEquals("default-src 'none'; sandbox", ok.getHeaderField("Content-Security-Policy"))
      for (path in listOf("/b", "/c")) {
        val r = call(server, "https://img.example.com$path")
        assertEquals(415, r.code, path)
        assertEquals("nosniff", r.getHeaderField("X-Content-Type-Options"))
      }
    } finally {
      server.stop(0)
    }
  }

  // ── redirects: no downgrade ───────────────────────────────────────────────

  @Test fun anHttpsToHttpRedirectIsRefusedButAnUpgradeIsFollowed() {
    val a = PreviewAssets(
      listOf("img.example.com"),
      client = fake { r ->
        when (r.url.encodedPath) {
          "/down" -> okhttp3.Response.Builder().code(301).header("Location", "http://img.example.com/x.png").body("".toResponseBody())
          "/up" -> okhttp3.Response.Builder().code(301).header("Location", "https://img.example.com/x.png").body("".toResponseBody())
          else -> image("image/png")
        }
      },
      log = { logs += it },
    ).also { created += it }
    val down = assertIs<PreviewAssets.Result.Refused>(a.fetch("https://img.example.com/down"))
    assertEquals(403, down.status)
    assertTrue("https" in down.reason, down.reason)
    assertTrue(fakeSeen.none { it.startsWith("http://") }, "$fakeSeen")
    assertIs<PreviewAssets.Result.Image>(a.fetch("http://img.example.com/up"))
    assertEquals("https://img.example.com/x.png", fakeSeen.last())
  }

  // ── its own pool, one deadline, remembered failures ───────────────────────

  @Test fun aHangingUpstreamDoesNotBlockTheRelaysOtherEndpoints() {
    val server = relay(assets())
    try {
      val pending = thread { runCatching { call(server, "$base/hang") } }
      waitFor { hits.get() == 1 } // the pool thread is now stuck upstream
      val started = System.nanoTime()
      assertEquals(200, call(server, "", path = "/ping", timeoutMillis = 2_000).code)
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
      release.countDown()
      pending.join(10_000)
    } finally {
      server.stop(0)
    }
  }

  @Test fun aFullPoolAnswers503() {
    val a = assets(threads = 1, queue = 1)
    val server = relay(a)
    try {
      val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
      val first = thread { codes += call(server, "$base/hang").code }
      waitFor { hits.get() == 1 }
      val second = thread { codes += call(server, "$base/hang").code }
      waitFor { a.pending() == 2 }
      val third = call(server, "$base/hang", timeoutMillis = 2_000)
      assertEquals(503, third.code)
      assertEquals("1", third.getHeaderField("Retry-After"))
      // The other endpoints still answer.
      assertEquals(200, call(server, "", path = "/ping", timeoutMillis = 2_000).code)
      release.countDown()
      first.join(10_000); second.join(10_000)
      assertEquals(listOf(200, 200), codes.sorted())
    } finally {
      server.stop(0)
    }
  }

  @Test fun oneDeadlineCoversEveryRedirect() {
    // Six requests of 400 ms each: every hop is quick, the whole is not.
    val slow = assets(deadlineMillis = 1_000)
    val started = System.nanoTime()
    val r = assertIs<PreviewAssets.Result.Refused>(slow.fetch("$base/hop/0"))
    val tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assertEquals(504, r.status)
    assertTrue(tookMillis < 2_000, "took $tookMillis ms")
    assertTrue(hits.get() < 6, "${hits.get()} hops")
    // With room for all six, the same chain is an image.
    hits.set(0)
    assertIs<PreviewAssets.Result.Image>(assets(deadlineMillis = 10_000).fetch("$base/hop/0"))
    assertEquals(6, hits.get())
  }

  @Test fun aFailureIsRememberedBriefly() {
    val a = assets()
    assertEquals(502, assertIs<PreviewAssets.Result.Refused>(a.fetch("$base/missing.png")).status)
    assertEquals(1, hits.get())
    // A re-render asks again within 30 s: answered from memory, same status.
    assertEquals(502, assertIs<PreviewAssets.Result.Refused>(a.fetch("$base/missing.png")).status)
    assertEquals(1, hits.get())
    // Other URLs on a host that answered are unaffected.
    assertIs<PreviewAssets.Result.Image>(a.fetch("$base/img.png"))
    now += 31_000
    a.fetch("$base/missing.png")
    assertEquals(3, hits.get())
  }

  @Test fun anUnreachableHostIsRememberedForEveryUrlOnIt() {
    val a = assets(deadlineMillis = 300)
    assertEquals(504, assertIs<PreviewAssets.Result.Refused>(a.fetch("$base/hang")).status)
    assertEquals(1, hits.get())
    // The host timed out: another image on it is not tried for 30 s.
    assertEquals(504, assertIs<PreviewAssets.Result.Refused>(a.fetch("$base/img.png")).status)
    assertEquals(1, hits.get())
    now += 31_000
    assertIs<PreviewAssets.Result.Image>(a.fetch("$base/img.png"))
    assertEquals(2, hits.get())
  }

  private fun waitFor(condition: () -> Boolean) {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition()) {
      check(System.nanoTime() < until) { "timed out waiting" }
      Thread.sleep(10)
    }
  }
}
