import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger
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

  private val upstream: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
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
  }

  private var now = 0L
  private val logs = mutableListOf<String>()
  private fun assets(hosts: List<String> = listOf("localhost"), entries: Int = 256) =
    PreviewAssets(hosts, maxBytes = 1_000, cacheMaxEntries = entries, clock = { now }, log = { logs += it })

  @AfterTest fun stop() = upstream.stop(0)

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

  private fun relay(a: PreviewAssets): HttpServer =
    HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
      createContext("/preview-asset") { ex -> a.serve(ex) }
      start()
    }

  private class Reply(val code: Int, val headers: okhttp3.Headers, val body: ByteArray) {
    val contentType get() = headers["Content-Type"]
    fun getHeaderField(name: String): String? = headers[name]
  }

  // OkHttp, not HttpURLConnection: the JDK client silently drops `Origin`.
  private fun call(server: HttpServer, url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Reply {
    val target = "http://127.0.0.1:${server.address.port}/preview-asset?url=" + URLEncoder.encode(url, "UTF-8")
    val body = if (method == "GET") null else ByteArray(0).toRequestBody()
    val request = okhttp3.Request.Builder().url(target).method(method, body)
    headers.forEach { (k, v) -> request.header(k, v) }
    okhttp3.OkHttpClient.Builder().followRedirects(false).build().newCall(request.build()).execute().use {
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
      assertEquals("http://localhost:8096", conn.getHeaderField("Access-Control-Allow-Origin"))
      assertEquals("nosniff", conn.getHeaderField("X-Content-Type-Options"))
      assertContentEquals(png, conn.body)
      val seen = seenHeaders.single()
      for (h in listOf("cookie", "authorization", "x-api-key", "referer", "origin")) assertNull(seen[h], h)
      assertEquals(listOf(PreviewAssets.USER_AGENT), seen["user-agent"])
    } finally {
      server.stop(0)
    }
  }

  @Test fun aForeignOriginGetsNoCorsGrant() {
    val server = relay(assets())
    try {
      val conn = call(server, "$base/img.png", headers = mapOf("Origin" to "https://evil.example"))
      assertEquals(200, conn.code)
      assertNull(conn.getHeaderField("Access-Control-Allow-Origin"))
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

  @Test fun loopbackOrigins() {
    assertTrue(PreviewAssets.isLoopbackOrigin("http://localhost:8096"))
    assertTrue(PreviewAssets.isLoopbackOrigin("http://127.0.0.1:18096"))
    assertFalse(PreviewAssets.isLoopbackOrigin("http://localhost.evil.test"))
    assertFalse(PreviewAssets.isLoopbackOrigin("null"))
  }

  @Test fun configAcceptsHostNamesOnly() {
    assertEquals(listOf("img.example.com"), PortalConfig(previewAssetHosts = listOf("img.example.com")).previewAssetHosts)
    for (bad in listOf("*.example.com", "https://img.example.com", "img.example.com:443", "img.example.com/x", "Img.Example.com", "10.0.0.1", "::1", "")) {
      assertFailsWith<IllegalArgumentException>(bad) { PortalConfig(previewAssetHosts = listOf(bad)) }
    }
    assertFailsWith<IllegalArgumentException> { PortalConfig(previewAssetHosts = listOf("a.example", "a.example")) }
    assertTrue(PortalConfig().previewAssetHosts.isEmpty())
  }
}
