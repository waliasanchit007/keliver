import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** KNOWN_BUGS U35 (where the relay listens) and U36 (who may drive it). */
class RelayAccessTest {
  // ── bindAddress ────────────────────────────────────────────────────────

  @Test
  fun unsetOrEmptyBindIsLoopbackAndAllIsEveryInterface() {
    assertEquals(InetAddress.getByName("127.0.0.1"), RelayAccess.bindAddress(null))
    assertEquals(InetAddress.getByName("127.0.0.1"), RelayAccess.bindAddress(""))
    assertEquals(InetAddress.getByName("127.0.0.1"), RelayAccess.bindAddress("  "))
    assertTrue(RelayAccess.bindAddress("all").isAnyLocalAddress)
    assertTrue(RelayAccess.bindAddress(" all ").isAnyLocalAddress)
  }

  @Test
  fun anyOtherBindValueIsRefused() {
    for (v in listOf("ALL", "0.0.0.0", "192.168.1.20", "localhost", "::", "bogus")) {
      val e = assertFailsWith<IllegalArgumentException>(v) { RelayAccess.bindAddress(v) }
      assertTrue("KELIVER_RELAY_BIND" in e.message!!, e.message)
    }
  }

  // ── hostAllowed ────────────────────────────────────────────────────────

  private val loopback = InetAddress.getByName("127.0.0.1")
  private val lan = InetAddress.getByName("192.168.1.20")

  @Test
  fun hostsNamingThisMachineAreAllowed() {
    for (h in listOf(
      "localhost", "localhost:8077", "LOCALHOST:8077", "127.0.0.1", "127.0.0.1:8077", "127.5.6.7:1",
      "[::1]", "[::1]:8077", "10.0.2.2:8077", "10.0.3.2:8077",
    )) {
      assertTrue(RelayAccess.hostAllowed(h, loopback), h)
    }
  }

  @Test
  fun foreignOrMalformedHostsAreRefused() {
    for (h in listOf(
      null, "", " ", "evil.example", "evil.example:8077", "localhost.evil.example", "localhost.evil.example:8077",
      "127.0.0.1.nip.io:8077", "0.0.0.0:8077", "999.1.1.1:8077", "[::1", "::1:8077", "localhost:abc",
      "localhost:8077:1", "user@localhost:8077", "[fe80::1]:8077", "8.8.8.8:8077",
    )) {
      assertFalse(RelayAccess.hostAllowed(h, loopback), h.toString())
    }
  }

  @Test
  fun aLanAddressIsAllowedOnlyWhenTheConnectionArrivedOnIt() {
    assertTrue(RelayAccess.hostAllowed("192.168.1.20:8077", lan))
    // A dual-stack socket reports an IPv4 local address as ::ffff:a.b.c.d.
    assertTrue(RelayAccess.hostAllowed("192.168.1.20:8077", InetAddress.getByName("::ffff:192.168.1.20")))
    assertFalse(RelayAccess.hostAllowed("192.168.1.20:8077", loopback))
    assertFalse(RelayAccess.hostAllowed("192.168.1.21:8077", lan))
    assertFalse(RelayAccess.hostAllowed("192.168.1.20:8077", null))
  }

  // ── originAllowed ──────────────────────────────────────────────────────

  @Test
  fun loopbackOriginsAreAllowed() {
    for (o in listOf(
      "http://localhost", "http://localhost:8096", "https://localhost:8443", "http://127.0.0.1:8096",
      "http://[::1]:8096", "HTTP://LOCALHOST:8096",
    )) {
      assertTrue(RelayAccess.originAllowed(o), o)
    }
  }

  @Test
  fun everyOtherOriginIsRefused() {
    for (o in listOf(
      "null", "", "https://evil.example", "http://localhost.evil.example", "http://localhost.evil.example:8096",
      "http://127.0.0.1.nip.io", "http://localhost:8096/path", "http://user@localhost:8096", "file://",
      "http://192.168.1.20:8096", "http://10.0.2.2:8096", "ws://localhost:8096",
    )) {
      assertFalse(RelayAccess.originAllowed(o), o)
    }
  }

  // ── admit, on a real server ────────────────────────────────────────────

  private class Response(val code: Int, val headers: Map<String, List<String>>) {
    fun header(name: String): List<String> = headers[name.lowercase()].orEmpty()
  }

  /** A raw HTTP/1.1 request: HttpURLConnection will not send a chosen Host or Origin. */
  private fun raw(port: Int, method: String, path: String, vararg headers: Pair<String, String>, body: String = ""): Response {
    Socket("127.0.0.1", port).use { s ->
      s.soTimeout = 5_000
      val hasHost = headers.any { it.first.equals("Host", ignoreCase = true) }
      val req = buildString {
        append("$method $path HTTP/1.1\r\n")
        if (!hasHost) append("Host: localhost:$port\r\n")
        headers.forEach { (k, v) -> append("$k: $v\r\n") }
        append("Content-Length: ${body.length}\r\nConnection: close\r\n\r\n")
        append(body)
      }
      s.getOutputStream().apply { write(req.encodeToByteArray()); flush() }
      val lines = s.getInputStream().readBytes().decodeToString().substringBefore("\r\n\r\n").split("\r\n")
      val code = lines.first().split(' ')[1].toInt()
      val map = lines.drop(1).groupBy({ it.substringBefore(':').trim().lowercase() }, { it.substringAfter(':').trim() })
      return Response(code, map)
    }
  }

  private fun <T> withServer(block: (port: Int, runs: AtomicInteger) -> T): T {
    val server = RelayAccess.gate(HttpServer.create(InetSocketAddress(RelayAccess.LOOPBACK, 0), 0))
    val runs = AtomicInteger()
    server.createContext("/x") { ex ->
      runs.incrementAndGet()
      ex.requestBody.readBytes()
      val bytes = "ok".encodeToByteArray()
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      return block(server.address.port, runs)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun aRequestWithoutAnOriginPassesWithoutCors() = withServer { port, runs ->
    val r = raw(port, "GET", "/x")
    assertEquals(200, r.code)
    assertEquals(1, runs.get())
    assertTrue(r.header("Access-Control-Allow-Origin").isEmpty())
    // An emulator's host names this machine 10.0.2.2.
    assertEquals(200, raw(port, "GET", "/x", "Host" to "10.0.2.2:$port").code)
    // A browser's same-site no-cors request (the editor on another localhost port).
    assertEquals(200, raw(port, "GET", "/x", "Sec-Fetch-Site" to "same-site").code)
  }

  @Test
  fun aLoopbackOriginPassesAndIsEchoedNeverStar() = withServer { port, runs ->
    for (origin in listOf("http://localhost:18196", "http://127.0.0.1:18196", "http://[::1]:18196")) {
      for (method in listOf("GET", "POST")) {
        val r = raw(port, method, "/x", "Origin" to origin, "Sec-Fetch-Site" to "cross-site", body = "{}")
        assertEquals(200, r.code, "$method $origin")
        assertEquals(listOf(origin), r.header("Access-Control-Allow-Origin"))
        assertEquals(listOf("Origin"), r.header("Vary"))
      }
    }
    assertEquals(6, runs.get())
  }

  @Test
  fun aForeignOriginIsRefusedBeforeTheHandler() = withServer { port, runs ->
    for (method in listOf("GET", "POST", "PUT", "OPTIONS")) {
      for (origin in listOf("https://evil.example", "http://localhost.evil.example", "null")) {
        val r = raw(port, method, "/x", "Origin" to origin, body = "{}")
        assertEquals(403, r.code, "$method $origin")
        assertTrue(r.header("Access-Control-Allow-Origin").isEmpty(), "$method $origin")
      }
    }
    assertEquals(0, runs.get())
  }

  @Test
  fun aForeignHostIsRefusedBeforeTheHandler() = withServer { port, runs ->
    // DNS rebinding: the page's name now resolves to 127.0.0.1, and its Host says so.
    assertEquals(403, raw(port, "GET", "/x", "Host" to "evil.example:$port").code)
    assertEquals(403, raw(port, "POST", "/x", "Host" to "evil.example:$port", body = "{}").code)
    assertEquals(403, raw(port, "GET", "/x", "Host" to "localhost.evil.example:$port", "Origin" to "http://localhost:$port").code)
    assertEquals(0, runs.get())
  }

  @Test
  fun aPreflightIsAnsweredOnlyForALoopbackOrigin() = withServer { port, _ ->
    val foreign = raw(
      port, "OPTIONS", "/x", "Origin" to "https://evil.example", "Access-Control-Request-Method" to "POST",
    )
    assertEquals(403, foreign.code)
    assertTrue(foreign.header("Access-Control-Allow-Origin").isEmpty())
    val local = raw(
      port, "OPTIONS", "/x", "Origin" to "http://localhost:18196", "Access-Control-Request-Method" to "POST",
    )
    assertEquals(listOf("http://localhost:18196"), local.header("Access-Control-Allow-Origin"))
  }

  @Test
  fun aCrossSiteRequestWithoutAnOriginIsRefused() = withServer { port, runs ->
    // What a foreign page's <img src="http://localhost:8077/…"> sends.
    assertEquals(403, raw(port, "GET", "/x", "Sec-Fetch-Site" to "cross-site").code)
    assertEquals(0, runs.get())
  }

  @Test
  fun contextsRegisteredElsewhereAreGatedToo() {
    val server = RelayAccess.gate(HttpServer.create(InetSocketAddress(RelayAccess.LOOPBACK, 0), 0))
    val reports = HostReports(Files.createTempDirectory("relay-access").toFile())
    HostReports.register(server, reports) { _, block -> block() }
    server.start()
    try {
      val port = server.address.port
      val report = """{"installId":"4f0c2a8e-1b2c-4d5e-8f90-0123456789ab","channel":"stable","hostVersion":1,""" +
        """"sequence":8,"source":"network","outcome":"loaded","reason":"","platform":"android"}"""
      assertEquals(403, raw(port, "POST", "/report", "Origin" to "https://evil.example", body = report).code)
      assertEquals(403, raw(port, "GET", "/reports", "Host" to "evil.example").code)
      assertEquals(0, reports.summary()["total"].toString().toInt())
      assertEquals(204, raw(port, "POST", "/report", body = report).code)
      assertEquals(1, reports.summary()["total"].toString().toInt())
    } finally {
      server.stop(0)
    }
  }

  // ── portTakenBy ────────────────────────────────────────────────────────

  @Test
  fun aListenerOnEitherLoopbackAddressIsFound() {
    ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { s ->
      assertEquals("127.0.0.1", RelayAccess.portTakenBy(s.localPort))
    }
    ServerSocket(0, 50, InetAddress.getByName("::1")).use { s ->
      assertEquals("::1", RelayAccess.portTakenBy(s.localPort))
    }
    val free = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    assertNull(RelayAccess.portTakenBy(free))
  }
}
