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
  private val events = BundleEvents({ 1 })
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
    val on = Json.parseToJsonElement(DeviceProxy.statusJson(listOf("http://10.0.2.2:8078"))).jsonObject
    val item = (on["urls"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
    assertEquals("http://10.0.2.2:8078", item["url"]!!.jsonPrimitive.content)
    assertTrue(item["qrSvg"]!!.jsonPrimitive.content.startsWith("<svg "))
  }

  @Test
  fun theRelayStartsOneContextOnTheProxyAndRegistersTheStatusReadOnly() {
    val proxy = File("src/main/kotlin/DeviceProxy.kt").readText()
    assertEquals(1, Regex("""\.createContext\(""").findAll(proxy).count())
    val relay = File("src/main/kotlin/Relay.kt").readText()
    // The relay's own server is unchanged in where it binds: the proxy is a second server.
    assertTrue("HttpServer.create(InetSocketAddress(bindAddress, PORT), 0)" in relay)
    assertTrue("DeviceProxy(bundlesDir, bundleEvents).start(proxyAddress, proxyPort)" in relay)
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
