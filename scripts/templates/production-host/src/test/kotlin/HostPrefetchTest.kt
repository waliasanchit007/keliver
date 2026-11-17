package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** NF4: the first screen's declared request, sent at start and taken by the guest's identical one (HostPrefetch.kt). */
class HostPrefetchTest {
  private val server = MockWebServer()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var clock = 0L

  @Before fun start() = server.start()

  @After fun stop() {
    scope.cancel()
    server.close()
  }

  private fun base() = server.url("/api/")

  private val items = KeliverPrefetch("/items", mapOf("size" to "small", "page" to "1"))

  private fun http(vararg declared: KeliverPrefetch, policy: RequestPolicy? = null, maxAgeMillis: Long = 30_000L) =
    OkHttpHostHttp(OkHttpClient(), base(), policy, prefetched = PrefetchedRequests(declared.toList(), maxAgeMillis) { clock })

  private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

  /** Starts the prefetch and waits until the server has seen it. */
  private fun OkHttpHostHttp.prefetchedOnce(): OkHttpHostHttp = apply {
    startPrefetch(scope)
    val sent = server.takeRequest(5, TimeUnit.SECONDS)
    assertEquals("/api/items?size=small&page=1", sent?.url?.encodedPath + "?" + sent?.url?.encodedQuery)
  }

  @Test fun theGuestsIdenticalRequestTakesThePrefetchedResponse() = runBlocking {
    server.enqueue(ok("prefetched"))
    val http = http(items).prefetchedOnce()
    // The guest's own form of the same request: no leading slash, the query in another order.
    val response = http.execute(HttpRequest("get", "items", query = mapOf("page" to "1", "size" to "small")))
    assertEquals("prefetched", response.body)
    assertEquals(1, server.requestCount)
  }

  @Test fun itIsTakenOnceAndTheNextIdenticalRequestGoesToTheNetwork() = runBlocking {
    server.enqueue(ok("prefetched"))
    server.enqueue(ok("fresh"))
    val http = http(items).prefetchedOnce()
    val request = HttpRequest("GET", "/items", query = mapOf("size" to "small", "page" to "1"))
    assertEquals("prefetched", http.execute(request).body)
    assertEquals("fresh", http.execute(request).body)
    assertEquals(2, server.requestCount)
  }

  @Test fun anotherQueryAPostOrAHeaderOfTheGuestsOwnIsNotTheSameRequest() = runBlocking {
    server.enqueue(ok("prefetched"))
    repeat(4) { server.enqueue(ok("network")) }
    val http = http(items).prefetchedOnce()
    assertEquals("network", http.execute(HttpRequest("GET", "items", query = mapOf("size" to "large", "page" to "1"))).body)
    assertEquals("network", http.execute(HttpRequest("GET", "items", query = mapOf("size" to "small"))).body)
    assertEquals("network", http.execute(HttpRequest("GET", "items", headers = mapOf("X-Trace" to "1"), query = mapOf("size" to "small", "page" to "1"))).body)
    assertEquals("network", http.execute(HttpRequest("POST", "items", query = mapOf("size" to "small", "page" to "1"), body = "{}")).body)
    // Still there for the identical request.
    assertEquals("prefetched", http.execute(HttpRequest("GET", "items", query = mapOf("size" to "small", "page" to "1"))).body)
    assertEquals(5, server.requestCount)
  }

  @Test fun aHeaderTheHostDropsAnywayDoesNotStopTheMatch() = runBlocking {
    server.enqueue(ok("prefetched"))
    val http = http(items).prefetchedOnce()
    val response = http.execute(HttpRequest("GET", "items", headers = mapOf("Connection" to "keep-alive"), query = mapOf("size" to "small", "page" to "1")))
    assertEquals("prefetched", response.body)
    assertEquals(1, server.requestCount)
  }

  @Test fun aFailedPrefetchIsNotUsedAndTheGuestsRequestGoesOut() = runBlocking {
    server.enqueue(MockResponse.Builder().code(503).body("busy").build())
    server.enqueue(ok("fresh"))
    val http = http(items).prefetchedOnce()
    val response = http.execute(HttpRequest("GET", "items", query = mapOf("size" to "small", "page" to "1")))
    assertEquals(200, response.status)
    assertEquals("fresh", response.body)
    assertEquals(2, server.requestCount)
  }

  @Test fun anOldPrefetchIsDroppedAndNeverHandedOut() = runBlocking {
    server.enqueue(ok("prefetched"))
    server.enqueue(ok("fresh"))
    val http = http(items, maxAgeMillis = 1_000L).prefetchedOnce()
    clock += 1_001L
    assertEquals("fresh", http.execute(HttpRequest("GET", "items", query = mapOf("size" to "small", "page" to "1"))).body)
    assertEquals(2, server.requestCount)
  }

  @Test fun aDeclaredRequestThePolicyRefusesSendsNothing() {
    val policy = RequestPolicy(base(), setOf(RequestPolicy.Rule("GET", "orders")), allowCleartext = true)
    val http = http(items, policy = policy)
    http.startPrefetch(scope)
    try {
      runBlocking { http.execute(HttpRequest("GET", "items", query = mapOf("size" to "small", "page" to "1"))) }
      fail("not refused")
    } catch (e: HostHttpRefused) {
      assertTrue(e.message, "is not in the allow-list" in e.message.orEmpty())
    }
    assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    assertEquals(0, server.requestCount)
  }

  @Test fun startIsOnceAndWithoutDeclarationsNothingIsSent() = runBlocking {
    server.enqueue(ok("prefetched"))
    val http = http(items)
    http.startPrefetch(scope)
    http.startPrefetch(scope)
    assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
    assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    OkHttpHostHttp(OkHttpClient(), base()).startPrefetch(scope)
    assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    assertEquals(1, server.requestCount)
  }

  @Test fun aDeclarationMustBeAPlainPathUnderTheApiBase() {
    for (path in listOf("", "/", "a/../b", "items?size=small", "a\\b", "items#x")) {
      try {
        KeliverPrefetch(path)
        fail("'$path' was accepted")
      } catch (e: IllegalArgumentException) {
        assertTrue(e.message, "KeliverPrefetch" in e.message.orEmpty())
      }
    }
    assertEquals(listOf("v1", "items"), KeliverPrefetch("/v1//items/").segments)
  }
}
