import app.cash.zipline.loader.ZiplineHttpClient
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import hosttemplate.ManifestPinningHttpClient
import hosttemplate.pickFromIndex
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** ED3: the device proxy serves GET under /bundles/ and nothing else. */
class DeviceProxyTest {
  private val tmp = Files.createTempDirectory("device-proxy").toFile()
  private val bundles = File(tmp, "store/bundles").apply { mkdirs() }
  private val module = ByteArray(300) { (it * 7).toByte() }
  private val events = BundleEvents({ 1 }, maxListeners = DeviceProxy.MAX_LISTENERS, maxPerKey = DeviceProxy.MAX_LISTENERS_PER_IP)
  private val server = DeviceProxy(bundles, events).start(InetAddress.getByName("127.0.0.1"), 0)
  private val port = server.address.port

  init {
    val v1 = File(bundles, "v1").apply { mkdirs() }
    File(v1, "main.zipline").writeBytes(module)
    File(v1, "manifest.zipline.json").writeText(
      """{"unsigned":{"signatures":{}},"modules":{"main":{"url":"main.zipline","sha256":"${module.toByteString().sha256().hex()}"}},"mainModuleId":"main"}""",
    )
    File(v1, "meta.json").writeText("""{"widgetVersion":1,"capabilities":[],"createdAt":1}""")
    // What the relay keeps beside the bundles must stay out of reach.
    File(tmp, "store/keys").apply { mkdirs() }.resolve("private.key").writeText("secret")
    File(bundles, "secret.txt").writeText("not a bundle file")
  }

  @AfterTest fun tearDown() {
    server.stop(0)
    events.close()
    tmp.deleteRecursively()
  }

  /** A raw request, so no client normalises the path. Returns the status line's code and the body. */
  private fun raw(method: String, path: String, host: String = "127.0.0.1:$port", extra: String = ""): Pair<Int, String> =
    Socket("127.0.0.1", port).use { s ->
      s.soTimeout = 5_000
      val body = if (method in setOf("POST", "PUT", "PATCH")) "{}" else ""
      s.getOutputStream().write(
        "$method $path HTTP/1.1\r\nHost: $host\r\n${extra}Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
      )
      val text = s.getInputStream().readBytes().decodeToString()
      text.substringAfter(' ').take(3).toInt() to text.substringAfter("\r\n\r\n", "")
    }

  @Test
  fun everyMethodButGetIsRefused() {
    for (path in listOf("/bundles/index.json", "/bundles/v1/main.zipline", "/publish", "/ops", "/")) {
      for (m in listOf("POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD", "TRACE", "CONNECT")) {
        if (m == "CONNECT") continue // not a path request
        assertEquals(405, raw(m, path).first, "$m $path")
      }
    }
    // A preflight from a loopback page passes the Host/Origin gate and still gets 405.
    assertEquals(405, raw("OPTIONS", "/bundles/index.json", extra = "Origin: http://localhost:8096\r\n").first)
  }

  @Test
  fun everyPathOutsideTheBundlesIsRefused() {
    for (p in listOf(
      "/", "/publish", "/ops", "/undo", "/redo", "/doc", "/draft", "/active", "/screens", "/source", "/devstate",
      "/publickey", "/http-record", "/report", "/device-proxy", "/bundles", "/bundles/", "/bundles/latest",
      "/bundles/latest?widgetVersion=1", "/bundles/secret.txt", "/bundles/v1", "/bundles/v1/", "/bundles/v0/main.zipline",
      "/bundles/v01/main.zipline", "/bundles/v1/../secret.txt", "/bundles/v1/..%2fsecret.txt", "/bundles/%2e%2e/keys/private.key",
      "/bundles/../keys/private.key", "/bundles/v1/.hidden", "/bundles//v1/main.zipline", "/bundles/v1//main.zipline",
      "/bundles/v1/main.zipline/", "/bundles/v1\\main.zipline", "/bundles/v2/main.zipline", "/bundles/events/x",
      "/BUNDLES/index.json", "/x/bundles/index.json",
    )) {
      // 400: the JDK's server refuses a path that is not a URI (a backslash) before any handler.
      val code = raw("GET", p).first
      assertTrue(code == 404 || (code == 400 && '\\' in p), "$p: $code")
    }
  }

  @Test
  fun aLinkOutOfTheStoreIsNotFollowed() {
    val v2 = File(bundles, "v2").apply { mkdirs() }
    Files.createSymbolicLink(File(v2, "main.zipline").toPath(), File(tmp, "store/keys/private.key").toPath())
    Files.createSymbolicLink(File(bundles, "v3").toPath(), File(tmp, "store/keys").toPath())
    assertEquals(404, raw("GET", "/bundles/v2/main.zipline").first)
    assertEquals(404, raw("GET", "/bundles/v3/private.key").first)
  }

  @Test
  fun theRelaysHostAndOriginGateAppliesToo() {
    assertEquals(403, raw("GET", "/bundles/index.json", host = "evil.example:$port").first) // DNS rebinding
    assertEquals(403, raw("GET", "/bundles/index.json", extra = "Origin: https://evil.example\r\n").first)
    assertEquals(200, raw("GET", "/bundles/index.json", host = "10.0.2.2:$port").first) // the emulator
  }

  @Test
  fun theEventStreamIsServed() {
    Socket("127.0.0.1", port).use { s ->
      s.soTimeout = 5_000
      s.getOutputStream().write("GET /bundles/events HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
      val head = ByteArray(512).let { b -> String(b, 0, s.getInputStream().read(b)) }
      assertTrue(head.startsWith("HTTP/1.1 200"), head)
      assertTrue("text/event-stream" in head, head)
    }
  }

  private class UrlClient : ZiplineHttpClient() {
    override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString {
      val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
      if (c.responseCode != 200) throw okio.IOException("$url: ${c.responseCode}")
      return c.inputStream.use { it.readBytes() }.toByteString()
    }
  }

  @Test
  fun aDebugHostLoadsTheBundleThroughIt() = runBlocking {
    // The production host template's own index reader and manifest pin (BundleIndex.kt).
    val base = "http://127.0.0.1:$port/bundles/"
    val http = UrlClient()
    val index = http.download("${base}index.json", emptyList()).utf8()
    val pick = pickFromIndex(index, emptyList()).getOrThrow()
    assertEquals(1, pick.sequence)
    val manifestUrl = base + pick.manifestPath
    val manifest = ManifestPinningHttpClient(http, manifestUrl, pick.manifestSha256).download(manifestUrl, emptyList()).utf8()
    val moduleUrl = Json.parseToJsonElement(manifest).jsonObject["modules"]!!.jsonObject["main"]!!.jsonObject["url"]!!.jsonPrimitive.content
    assertContentEquals(module, http.download(manifestUrl.substringBeforeLast('/') + "/" + moduleUrl, emptyList()).toByteArray())
  }

  // ── configuration ──────────────────────────────────────────────────────

  @Test
  fun itIsOffUnlessAskedFor() {
    assertNull(DeviceProxy.bindAddress(null))
    assertNull(DeviceProxy.bindAddress(" "))
    assertEquals(InetAddress.getByName("127.0.0.1"), DeviceProxy.bindAddress("loopback"))
    assertTrue(DeviceProxy.bindAddress("all")!!.isAnyLocalAddress)
    val lan = InetAddress.getByName("192.168.1.20")
    assertEquals(lan, DeviceProxy.bindAddress("192.168.1.20") { setOf(lan) })
    for (v in listOf("192.168.1.21", "LOOPBACK", "on", "1", "true", "localhost", "evil.example", "0.0.0.0")) {
      assertFailsWith<IllegalArgumentException>(v) { DeviceProxy.bindAddress(v) { setOf(lan) } }
    }
  }

  @Test
  fun itsPortIsTheRelaysPlusOneOrItsOwn() {
    assertEquals(8078, DeviceProxy.port(null, 8077))
    assertEquals(9000, DeviceProxy.port("9000", 8077))
    for (v in listOf("8077", "0", "70000", "x")) assertFailsWith<IllegalArgumentException>(v) { DeviceProxy.port(v, 8077) }
  }

  @Test
  fun theUrlsADebugHostUses() {
    assertEquals(listOf("http://10.0.2.2:8078", "http://127.0.0.1:8078"), DeviceProxy.urls(InetAddress.getByName("127.0.0.1"), 8078))
    val lan = InetAddress.getByName("192.168.1.20")
    assertEquals(
      listOf("http://10.0.2.2:8078", "http://192.168.1.20:8078"),
      DeviceProxy.urls(InetAddress.getByName("0.0.0.0"), 8078) { setOf(lan, InetAddress.getByName("127.0.0.1"), InetAddress.getByName("169.254.1.1")) },
    )
    assertEquals(listOf("http://192.168.1.20:8078"), DeviceProxy.urls(lan, 8078))
  }

  @Test
  fun theStatusSaysOffOrGivesEachUrlWithItsQrCode() {
    assertEquals(false, Json.parseToJsonElement(DeviceProxy.statusJson(null)).jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
    val on = Json.parseToJsonElement(DeviceProxy.statusJson(listOf("http://10.0.2.2:8078"), listening = 3)).jsonObject
    val item = (on["urls"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
    assertEquals("http://10.0.2.2:8078", item["url"]!!.jsonPrimitive.content)
    assertTrue(item["qrSvg"]!!.jsonPrimitive.content.startsWith("<svg "))
    // The proxy's own listeners, a count apart from the relay's devicesListening.
    assertEquals(3, on["listening"]!!.jsonPrimitive.content.toInt())
    // Without the app's link scheme: no link, and the QR code is the bare URL's.
    assertEquals(kotlinx.serialization.json.JsonNull, item["link"])
    assertEquals(QrCode.encode("http://10.0.2.2:8078").toSvg(), item["qrSvg"]!!.jsonPrimitive.content)
  }

  @Test
  fun withTheAppsSchemeTheQrCodeIsTheDebugLink() {
    val on = Json.parseToJsonElement(DeviceProxy.statusJson(listOf("http://192.168.1.20:8078", "http://[fd00::12]:8078"), linkScheme = "keliver-com.example.shop")).jsonObject
    val items = (on["urls"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
    val link = "keliver-com.example.shop://keliver-debug/bundles?server=http%3A%2F%2F192.168.1.20%3A8078"
    assertEquals("http://192.168.1.20:8078", items[0]["url"]!!.jsonPrimitive.content) // the bare URL stays
    assertEquals(link, items[0]["link"]!!.jsonPrimitive.content)
    assertEquals(QrCode.encode(link).toSvg(), items[0]["qrSvg"]!!.jsonPrimitive.content)
    assertEquals(link, decode(link))
    assertEquals(
      "keliver-com.example.shop://keliver-debug/bundles?server=http%3A%2F%2F%5Bfd00%3A%3A12%5D%3A8078",
      items[1]["link"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun theHostsDebugLinkCheckAcceptsEveryLinkTheRelayMakes() {
    val lan = setOf(
      InetAddress.getByName("192.168.1.20"), InetAddress.getByName("10.1.2.3"), InetAddress.getByName("127.0.0.1"),
      InetAddress.getByName("172.20.0.5"), InetAddress.getByName("100.100.1.2"),
    )
    val scheme = "keliver-com.example.shop"
    for (port in listOf(1024, 8078, 9000, 65535)) {
      val urls = DeviceProxy.urls(InetAddress.getByName("127.0.0.1"), port) +
        DeviceProxy.urls(InetAddress.getByName("0.0.0.0"), port) { lan } +
        DeviceProxy.urls(InetAddress.getByName("fd00::12"), port) +
        DeviceProxy.urls(InetAddress.getByName("fe80::1"), port) +
        DeviceProxy.urls(InetAddress.getByName("::1"), port)
      for (u in urls) {
        // The host template's own check (scripts/templates/production-host/src/debug), compiled in here.
        assertEquals(u, hosttemplate.DebugBundleLink.server(DeviceProxy.debugLink(scheme, u), scheme), u)
      }
    }
  }

  @Test
  fun noLinkWhereTheHostWouldRefuseIt() {
    val scheme = "keliver-com.example.shop"
    // A public address, or a port below 1024: the bare URL only, as the host refuses such a link.
    val urls = DeviceProxy.urls(InetAddress.getByName("0.0.0.0"), 8078) {
      setOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("172.32.0.1"), InetAddress.getByName("100.128.0.1"))
    }.filter { it != "http://10.0.2.2:8078" } +
      DeviceProxy.urls(InetAddress.getByName("2606:4700::1111"), 8078) +
      DeviceProxy.urls(InetAddress.getByName("192.168.1.20"), 80) +
      DeviceProxy.urls(InetAddress.getByName("192.168.1.20"), 1023)
    assertEquals(6, urls.size, urls.toString())
    for (u in urls) {
      assertNull(DeviceProxy.debugLink(scheme, u), u)
      assertNull(hosttemplate.DebugBundleLink.bundleServer(u), u) // the host agrees
    }
    val items = (Json.parseToJsonElement(DeviceProxy.statusJson(listOf("http://8.8.8.8:8078"), linkScheme = scheme)).jsonObject["urls"]
      as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
    assertEquals("null", items.single()["link"].toString())
    assertEquals(QrCode.encode("http://8.8.8.8:8078").toSvg(), items.single()["qrSvg"]!!.jsonPrimitive.content)
  }

  @Test
  fun theDebugLinkIsMadeOnlyFromTheRelaysOwnShapes() {
    assertEquals(
      "keliver-dev.example.inventory://keliver-debug/bundles?server=http%3A%2F%2F10.0.2.2%3A8078",
      DeviceProxy.debugLink("keliver-dev.example.inventory", "http://10.0.2.2:8078"),
    )
    for (scheme in listOf("http", "keliver-", "keliver-Com.x", "keliver-com.x/y", "keliver-com.x:", "javascript", "keliver-1x")) {
      assertNull(DeviceProxy.debugLink(scheme, "http://10.0.2.2:8078"), scheme)
    }
    for (url in listOf("https://10.0.2.2:8078", "http://evil.example:8078", "http://10.0.2.2:8078/x", "http://10.0.2.2", "http://10.0.2.2:8078?a&b")) {
      assertNull(DeviceProxy.debugLink("keliver-com.x", url), url)
    }
  }

  @Test
  fun theLinkSchemeFollowsTheScaffoldersPackageRule() {
    fun screen(pkg: String) = "// a screen\npackage $pkg\n\nimport x.y\n"
    assertEquals("keliver-com.example.shop", DeviceProxy.linkScheme(listOf(screen("com.example.shop.screens"), screen("com.example.shop.screens"))))
    assertEquals("keliver-com.my-app.v2", DeviceProxy.linkScheme(listOf(screen("com.My_App.V2.screens"))))
    assertNull(DeviceProxy.linkScheme(emptyList()), "no screens")
    assertNull(DeviceProxy.linkScheme(listOf(screen("com.a.screens"), screen("com.b.screens"))), "two packages")
    assertNull(DeviceProxy.linkScheme(listOf(screen("com.a.screens"), "fun x() {}")), "a screen without a package")
    assertNull(DeviceProxy.linkScheme(listOf(screen("com.a.ui"))), "not <package>.screens")
    assertNull(DeviceProxy.linkScheme(listOf(screen("screens"))), "no package before .screens")
    assertNull(DeviceProxy.linkScheme(listOf(screen("_x.screens"))), "a scheme must start with a letter")
  }

  @Test
  fun theRelayStartsOneContextOnTheProxyAndRegistersTheStatusReadOnly() {
    val proxy = File("src/main/kotlin/DeviceProxy.kt").readText()
    assertEquals(1, Regex("""\.createContext\(""").findAll(proxy).count())
    val relay = File("src/main/kotlin/Relay.kt").readText()
    // The relay's own server is unchanged in where it binds: the proxy is a second server.
    assertTrue("HttpServer.create(InetSocketAddress(bindAddress, PORT), 0)" in relay)
    // M1: the proxy has its own listeners; the relay's (and its devices count) are not shared.
    assertTrue("DeviceProxy(bundlesDir, deviceProxyEvents).start(proxyAddress, proxyPort)" in relay)
    assertTrue("\\\"devicesListening\\\":\${bundleEvents.listening}" in relay)
    // /device-proxy counts the proxy's own listeners, apart (and only when the proxy is on).
    assertTrue("DeviceProxy.statusJson(proxyUrls, if (proxyUrls != null) deviceProxyEvents.listening else 0, appLinkScheme())" in relay)
    // A port taken on the proxy's own address is exit 64, before the relay serves.
    assertTrue(relay.indexOf("DeviceProxy(bundlesDir, deviceProxyEvents).start") < relay.indexOf("  server.start()"))
  }

  /** Opens an event stream on the proxy; returns the socket (left open) and the status code. */
  private fun listen(): Pair<Socket, Int> {
    val s = Socket("127.0.0.1", port)
    s.soTimeout = 5_000
    s.getOutputStream().write("GET /bundles/events HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
    val head = ByteArray(64).let { b -> String(b, 0, s.getInputStream().read(b)) }
    return s to head.substringAfter(' ').take(3).toInt()
  }

  @Test
  fun localDevicesShareOnlyTheProxysQuotaAndNeverTakeTheRelaysSlots() {
    val relayEvents = BundleEvents({ 1 }) // the relay's own, as Relay.kt keeps it
    val sockets = mutableListOf<Socket>()
    try {
      // The emulator, Genymotion, the iOS simulator and adb reverse all arrive from 127.0.0.1:
      // they share the proxy's MAX_LISTENERS, with no per-address limit; the rest are refused.
      val codes = (1..32).map { listen().also { sockets += it.first }.second }
      assertEquals(DeviceProxy.MAX_LISTENERS, codes.count { it == 200 }, "$codes")
      assertTrue(codes.all { it == 200 || it == 503 }, "$codes")
      assertEquals(DeviceProxy.MAX_LISTENERS, events.listening)
      // The relay is untouched.
      assertTrue(relayEvents.hasRoom())
      assertEquals(0, relayEvents.listening)
    } finally {
      sockets.forEach { it.close() }
      relayEvents.close()
    }
  }

  @Test
  fun thePerAddressKeyIsTheIpExceptForLoopback() {
    fun key(ip: String) = DeviceProxy.listenerKey(java.net.InetSocketAddress(InetAddress.getByName(ip), 40000))
    for (ip in listOf("127.0.0.1", "127.0.0.2", "::1")) assertNull(key(ip), ip)
    assertEquals("192.168.1.20", key("192.168.1.20"))
    assertEquals("10.0.0.1", key("10.0.0.1"))
    assertEquals(InetAddress.getByName("fd00::1").hostAddress, key("fd00::1"))
    assertEquals("?", DeviceProxy.listenerKey(null)) // no address: limited, like one LAN peer
  }

  @Test
  fun theProxysQuotaAndThePerAddressLimit() {
    fun key(ip: String) = DeviceProxy.listenerKey(java.net.InetSocketAddress(InetAddress.getByName(ip), 40000))
    val e = BundleEvents({ 1 }, heartbeatSeconds = 3600, maxListeners = DeviceProxy.MAX_LISTENERS, maxPerKey = DeviceProxy.MAX_LISTENERS_PER_IP)
    try {
      // A LAN address gets MAX_LISTENERS_PER_IP streams.
      repeat(DeviceProxy.MAX_LISTENERS_PER_IP) { assertTrue(e.subscribe(java.io.ByteArrayOutputStream(), key("10.0.0.1"))) }
      assertTrue(!e.hasRoom(key("10.0.0.1")))
      assertTrue(!e.subscribe(java.io.ByteArrayOutputStream(), key("10.0.0.1")))
      // Loopback is not limited per address, so three or more local devices are admitted, up to the quota.
      var local = 0
      while (e.hasRoom(key("127.0.0.1"))) assertTrue(e.subscribe(java.io.ByteArrayOutputStream(), key("127.0.0.1"))).also { local++ }
      assertEquals(DeviceProxy.MAX_LISTENERS - DeviceProxy.MAX_LISTENERS_PER_IP, local)
      assertTrue(local >= 3, "$local")
      assertEquals(DeviceProxy.MAX_LISTENERS, e.listening)
      // The quota is full for everyone.
      assertTrue(!e.hasRoom(key("10.9.9.9")))
      assertTrue(!e.hasRoom(key("127.0.0.1")))
    } finally {
      e.close()
    }
    val lan = BundleEvents({ 1 }, heartbeatSeconds = 3600, maxListeners = DeviceProxy.MAX_LISTENERS, maxPerKey = DeviceProxy.MAX_LISTENERS_PER_IP)
    try {
      var n = 1
      while (lan.hasRoom(key("10.0.0.$n"))) {
        repeat(DeviceProxy.MAX_LISTENERS_PER_IP) { lan.subscribe(java.io.ByteArrayOutputStream(), key("10.0.0.$n")) }
        n++
      }
      assertEquals(DeviceProxy.MAX_LISTENERS, lan.listening)
      assertEquals(DeviceProxy.MAX_LISTENERS / DeviceProxy.MAX_LISTENERS_PER_IP, n - 1)
    } finally {
      lan.close()
    }
  }

  @Test
  fun onlyTheFilesTheManifestListsAreServed() {
    val v1 = File(bundles, "v1")
    File(v1, "main.zipline.map").writeText("""{"sources":["/Users/someone/src/Screen.kt"]}""")
    File(v1, "other.zipline").writeText("stray compile output")
    assertEquals(200, raw("GET", "/bundles/v1/manifest.zipline.json").first)
    assertEquals(200, raw("GET", "/bundles/v1/main.zipline").first)
    for (p in listOf("meta.json", "main.zipline.map", "other.zipline")) assertEquals(404, raw("GET", "/bundles/v1/$p").first, p)
    // Without a readable manifest, no module is served.
    val v4 = File(bundles, "v4").apply { mkdirs() }
    File(v4, "main.zipline").writeBytes(module)
    assertEquals(404, raw("GET", "/bundles/v4/main.zipline").first)
    assertEquals(setOf("a.zipline", "b.zipline"), DeviceProxy.moduleFiles("""{"modules":{"a":{"url":"a.zipline"},"b":{"url":"b.zipline"}}}"""))
    assertEquals(emptySet(), DeviceProxy.moduleFiles("not json"))
  }

  @Test
  fun aClientThatStopsReadingIsCutOff() {
    val big = ByteArray(64 shl 20)
    val v5 = File(bundles, "v5").apply { mkdirs() }
    File(v5, "big.zipline").writeBytes(big)
    File(v5, "manifest.zipline.json").writeText("""{"modules":{"big":{"url":"big.zipline","sha256":"x"}}}""")
    val before = DeviceProxy.sendLimitMs
    val floorBefore = DeviceProxy.sendFloorBytesPerSecond
    DeviceProxy.sendLimitMs = 500
    DeviceProxy.sendFloorBytesPerSecond = Long.MAX_VALUE // no allowance for the size: cut at 500 ms
    try {
      Socket("127.0.0.1", port).use { s ->
        s.receiveBufferSize = 4096
        s.soTimeout = 10_000
        s.getOutputStream().write("GET /bundles/v5/big.zipline HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
        Thread.sleep(2_000) // not reading: the send stalls and is cut
        var total = 0L
        val buf = ByteArray(65536)
        while (true) {
          val n = runCatching { s.getInputStream().read(buf) }.getOrDefault(-1)
          if (n < 0) break
          total += n
        }
        assertTrue(total < big.size, "read $total of ${big.size}")
      }
      // The pool thread is free again.
      assertEquals(200, raw("GET", "/bundles/index.json").first)
    } finally {
      DeviceProxy.sendLimitMs = before
      DeviceProxy.sendFloorBytesPerSecond = floorBefore
    }
  }

  @Test
  fun aSendsDeadlineGrowsWithItsSize() {
    // Counted from the send's start: 60 s, plus a second per 16 KiB (a 128 kbit/s floor).
    assertEquals(60_000L, DeviceProxy.sendDeadlineMs(0))
    assertEquals(61_000L, DeviceProxy.sendDeadlineMs(16L * 1024))
    assertEquals(60_000L + 256_000L, DeviceProxy.sendDeadlineMs(4L shl 20)) // a 4 MiB module: 316 s
  }

  @Test
  fun aFinishedSendLeavesNoCutQueued() {
    repeat(3) { assertEquals(200, raw("GET", "/bundles/v1/main.zipline").first) }
    // A cancelled cut leaves the watchdog's queue at once, not 60 s later.
    val until = System.currentTimeMillis() + 5_000
    while (DeviceProxy.watchdogQueued > 0 && System.currentTimeMillis() < until) Thread.sleep(20)
    assertEquals(0, DeviceProxy.watchdogQueued)
  }

  @Test
  fun theStatusEscapesItsStrings() {
    val odd = "http://x/\"a\\b\n</script> "
    val item = (Json.parseToJsonElement(DeviceProxy.statusJson(listOf(odd))).jsonObject["urls"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
    assertEquals(odd, item["url"]!!.jsonPrimitive.content)
    // A URL too long for a QR code still answers, with the link only.
    val long = "http://x/" + "y".repeat(300)
    val item2 = (Json.parseToJsonElement(DeviceProxy.statusJson(listOf(long))).jsonObject["urls"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
    assertEquals(kotlinx.serialization.json.JsonNull, item2["qrSvg"])
  }

  // ── QR codes ───────────────────────────────────────────────────────────

  private fun decode(text: String): String {
    val qr = QrCode.encode(text)
    val m = BitMatrix(qr.size)
    for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.isDark(x, y)) m.set(x, y)
    return Decoder().decode(m).text
  }

  @Test
  fun qrCodesDecodeWithAnIndependentDecoder() {
    val samples = listOf("", "a", "http://10.0.2.2:8078", "http://192.168.100.200:65535") +
      (1..204 step 7).map { n -> "http://x/" + "abcdefghij".repeat(30).take(n) }
    for (s in samples) assertEquals(s, decode(s), "length ${s.length}")
    assertFailsWith<IllegalArgumentException> { QrCode.encode("x".repeat(214)) }
  }
}
