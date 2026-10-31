@file:OptIn(ExperimentalForeignApi::class)

package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.usleep

/**
 * B2: guests' HostHttp with YOUR app's credentials (Keliver.requestHeaders), over NSURLSession to
 * a local server, on the iOS simulator: `./gradlew -p host-ios iosSimulatorArm64Test`.
 */
class AppHeadersHostHttpTest {
  private val server = LocalServer()
  private val base = "http://127.0.0.1:${server.port}/api"
  private val calls = mutableListOf<String>()

  private fun policy(vararg rules: RequestPolicy.Rule, origin: String = "http://127.0.0.1:${server.port}") =
    RequestPolicy(origin, rules.toSet(), allowStateChanging = false, allowCleartext = true)

  /** Your credentials, recording each call (method and path). */
  private val signedIn: KeliverRequestHeaders = { method, path, done ->
    calls += "$method $path"
    done(mapOf("Authorization" to "Bearer app-token"))
  }

  private fun http(
    policy: RequestPolicy? = policy(RequestPolicy.Rule("GET", "orders")),
    headers: KeliverRequestHeaders? = signedIn,
  ) = NSURLSessionHostHttp(base, policy, headers, EmptyCoroutineContext, 300.milliseconds)

  private fun get(path: String = "orders", headers: Map<String, String> = emptyMap()) =
    HttpRequest(method = "GET", path = path, headers = headers)

  @AfterTest fun stop() = server.stop()

  @Test fun anAllowedRequestCarriesTheAppsCredentials() = runBlocking {
    val response = http().execute(get())
    assertEquals(200, response.status)
    assertEquals(listOf("GET orders"), calls)
    val sent = server.requests.single()
    assertTrue(sent.startsWith("GET /api/orders HTTP/1.1\r\n"), sent)
    assertEquals("Bearer app-token", header(sent, "Authorization"))
  }

  @Test fun aRefusedRequestNeverReachesTheApp() {
    assertFailsWith<HostHttpRefused> { runBlocking { http().execute(get("admin")) } }
    assertTrue(calls.isEmpty())
    assertTrue(server.requests.isEmpty())
  }

  @Test fun anotherOriginIsRefusedBeforeTheApp() {
    val other = policy(RequestPolicy.Rule("GET", "orders"), origin = "https://api.example.com")
    assertFailsWith<HostHttpRefused> { runBlocking { http(policy = other).execute(get()) } }
    assertTrue(calls.isEmpty())
    assertTrue(server.requests.isEmpty())
  }

  @Test fun theAppsCredentialsNeedAPolicy() {
    val e = assertFailsWith<HostHttpRefused> { runBlocking { http(policy = null).execute(get()) } }
    assertTrue("needs a requestPolicy" in e.message!!, e.message)
    assertTrue(calls.isEmpty())
    assertTrue(server.requests.isEmpty())
  }

  @Test fun aRedirectIsNotFollowed() = runBlocking {
    server.respond = { "HTTP/1.1 302 Found\r\nLocation: /api/elsewhere\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }
    assertEquals(302, http().execute(get()).status)
    assertEquals(1, server.requests.size)
  }

  @Test fun aGuestCannotSetItsOwnCredentials() = runBlocking {
    http().execute(
      get(headers = mapOf("authorization" to "Bearer guest", "Proxy-Authorization" to "Basic eA==", "Cookie" to "sid=guest", "X-Trace" to "1")),
    )
    val sent = server.requests.single()
    assertEquals("Bearer app-token", header(sent, "Authorization"))
    assertNull(header(sent, "Proxy-Authorization"))
    assertNull(header(sent, "Cookie"))
    assertEquals("1", header(sent, "X-Trace"))
  }

  @Test fun theSessionCookieIsNotHandedToTheGuestNorSentAgain() = runBlocking {
    server.respond = { "HTTP/1.1 200 OK\r\nSet-Cookie: sid=user; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok" }
    val h = http()
    val first = h.execute(get())
    assertFalse(first.headers.keys.any { it.equals("Set-Cookie", ignoreCase = true) }, "${first.headers}")
    h.execute(get())
    assertNull(header(server.requests[1], "Cookie"))
  }

  @Test fun theAppCanRefuseARequest() {
    val signedOut: KeliverRequestHeaders = { _, _, done -> done(null) }
    assertFailsWith<HostHttpRefused> { runBlocking { http(headers = signedOut).execute(get()) } }
    assertTrue(server.requests.isEmpty())
  }

  @Test fun noAnswerInTimeRefusesTheRequest() {
    val silent: KeliverRequestHeaders = { _, _, _ -> }
    val e = assertFailsWith<HostHttpRefused> { runBlocking { http(headers = silent).execute(get()) } }
    assertTrue("did not answer" in e.message!!, e.message)
    assertTrue(server.requests.isEmpty())
  }

  @Test fun aCallbackThatThrowsRefusesTheRequest() {
    val broken: KeliverRequestHeaders = { _, _, _ -> error("no token store") }
    assertFailsWith<HostHttpRefused> { runBlocking { http(headers = broken).execute(get()) } }
    assertTrue(server.requests.isEmpty())
  }

  @Test fun anInvalidHeaderFromTheAppIsRefused() {
    val bad: KeliverRequestHeaders = { _, _, done -> done(mapOf("Authorization" to "Bearer a\r\nX-Admin: 1")) }
    assertFailsWith<HostHttpRefused> { runBlocking { http(headers = bad).execute(get()) } }
    assertTrue(server.requests.isEmpty())
  }

  @Test fun aSecondAnswerIsIgnored() = runBlocking {
    val twice: KeliverRequestHeaders = { _, _, done -> done(mapOf("Authorization" to "Bearer first")); done(null) }
    assertEquals(200, http(headers = twice).execute(get()).status)
    assertEquals("Bearer first", header(server.requests.single(), "Authorization"))
  }

  @Test fun withoutTheAppsCredentialsTheGuestsHeadersGoAsBeforeButNotTheOverrides() = runBlocking {
    server.respond = { "HTTP/1.1 200 OK\r\nSet-Cookie: sid=s\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok" }
    val plain = NSURLSessionHostHttp(base)
    val response = plain.execute(
      get(headers = mapOf("Authorization" to "Bearer guest", "X-Original-URL" to "/admin", "X-HTTP-Method-Override" to "DELETE")),
    )
    assertTrue(response.headers.keys.any { it.equals("Set-Cookie", ignoreCase = true) })
    val sent = server.requests.single()
    assertEquals("Bearer guest", header(sent, "Authorization"))
    assertNull(header(sent, "X-Original-URL"))
    assertNull(header(sent, "X-HTTP-Method-Override"))
  }

  private fun header(request: String, name: String): String? =
    request.substringBefore("\r\n\r\n").split("\r\n").drop(1)
      .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
      ?.substringAfter(':')?.trim()
}

/** One HTTP/1.1 request per connection on 127.0.0.1, answered by [respond]; [requests] holds each, raw. */
private class LocalServer {
  private val lock = NSLock()
  private val seen = mutableListOf<String>()
  @Volatile private var stopping = false
  @Volatile private var stopped = false
  var respond: (String) -> String = { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok" }
  val requests: List<String> get() = locked { seen.toList() }

  private val fd = socket(AF_INET, SOCK_STREAM, 0)
  val port: Int = memScoped {
    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
    val address = alloc<sockaddr_in>()
    address.sin_family = AF_INET.convert()
    address.sin_port = 0u // any free port
    address.sin_addr.s_addr = 0x0100007fu // 127.0.0.1, in network byte order
    check(bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind failed" }
    check(listen(fd, 8) == 0) { "listen failed" }
    val size = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
    getsockname(fd, address.ptr.reinterpret(), size.ptr)
    val p = address.sin_port.toInt()
    ((p and 0xff) shl 8) or (p shr 8)
  }

  init {
    NSThread { serve() }.start()
  }

  private fun serve() {
    while (true) {
      val client = accept(fd, null, null)
      if (client < 0) break
      if (stopping) {
        close(client)
        break
      }
      val raw = StringBuilder()
      val buffer = ByteArray(8192)
      while (true) {
        val n = buffer.usePinned { recv(client, it.addressOf(0), buffer.size.convert(), 0) }.toInt()
        if (n <= 0) break
        raw.append(buffer.decodeToString(0, n))
        val text = raw.toString()
        val end = text.indexOf("\r\n\r\n")
        if (end >= 0) {
          val length = Regex("(?i)\r\ncontent-length: *(\\d+)").find(text.substring(0, end))?.groupValues?.get(1)?.toInt() ?: 0
          if (text.length - end - 4 >= length) break
        }
      }
      val request = raw.toString()
      locked { seen += request }
      val answer = respond(request).encodeToByteArray()
      answer.usePinned { send(client, it.addressOf(0), answer.size.convert(), 0) }
      close(client)
    }
    stopped = true
  }

  /**
   * Wakes the accept loop with a connection of its own and waits for it to end, THEN closes the
   * socket: on Darwin close() does not wake a blocked accept(), and the next test's socket could
   * get this descriptor's number, so a thread left waiting would answer that test's requests.
   */
  fun stop() {
    stopping = true
    memScoped {
      val wake = socket(AF_INET, SOCK_STREAM, 0)
      val address = alloc<sockaddr_in>()
      address.sin_family = AF_INET.convert()
      address.sin_port = (((port and 0xff) shl 8) or (port shr 8)).convert()
      address.sin_addr.s_addr = 0x0100007fu
      connect(wake, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
      close(wake)
    }
    repeat(200) { if (!stopped) usleep(10_000u) }
    check(stopped) { "the local server did not stop" }
    close(fd)
  }

  private fun <T> locked(block: () -> T): T {
    lock.lock()
    try { return block() } finally { lock.unlock() }
  }
}
