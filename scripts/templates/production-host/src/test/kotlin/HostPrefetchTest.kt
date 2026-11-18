package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

/** NF4: the first screen's declared request, sent at a start toward a screen and taken by the guest's identical one (HostPrefetch.kt). */
class HostPrefetchTest {
  private val server = MockWebServer()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var clock = 0L
  private val logged: MutableList<String> = Collections.synchronizedList(ArrayList())
  private lateinit var prefetched: PrefetchedRequests

  @Before fun start() = server.start()

  @After fun stop() {
    scope.cancel()
    server.close()
  }

  private fun base() = server.url("/api/")

  private val items = KeliverPrefetch("/items", mapOf("size" to "small", "page" to "1"))
  private val guestsItems = HttpRequest("GET", "items", query = mapOf("size" to "small", "page" to "1"))

  private fun http(
    vararg declared: KeliverPrefetch,
    policy: RequestPolicy? = null,
    maxAgeMillis: Long = 5_000L,
    maxWaitMillis: Long = 1_000L,
    client: OkHttpClient = OkHttpClient(),
    appOwnsCredentials: Boolean = false,
  ): OkHttpHostHttp {
    prefetched = PrefetchedRequests(declared.toList(), maxAgeMillis, maxWaitMillis, now = { clock }, log = { logged += it })
    return OkHttpHostHttp(client, base(), policy, appOwnsCredentials = appOwnsCredentials, prefetched = prefetched)
  }

  private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

  /** A start toward a screen: armed by run(), then the first activity's creation; waits until the server has seen it. */
  private fun OkHttpHostHttp.prefetchedOnce(): OkHttpHostHttp = apply {
    startPrefetch(scope)
    prefetched.uiStarting()
    val sent = server.takeRequest(5, TimeUnit.SECONDS)
    assertEquals("/api/items?size=small&page=1", sent?.url?.encodedPath + "?" + sent?.url?.encodedQuery)
  }

  /** As [prefetchedOnce], and its answer has arrived. */
  private fun OkHttpHostHttp.answeredOnce(): OkHttpHostHttp = prefetchedOnce().apply { runBlocking { prefetched.settled() } }

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

  @Test fun anotherQueryOrAHeaderOfTheGuestsOwnIsNotTheSameRequestAndSaysWhy() = runBlocking {
    server.enqueue(ok("prefetched"))
    repeat(4) { server.enqueue(ok("network")) }
    val http = http(items).answeredOnce()
    assertEquals("network", http.execute(HttpRequest("GET", "items", query = mapOf("size" to "large", "page" to "1"))).body)
    assertEquals("network", http.execute(HttpRequest("GET", "items", query = mapOf("size" to "small"))).body)
    assertEquals("network", http.execute(HttpRequest("GET", "items", headers = mapOf("X-Trace" to "secret-1"), query = mapOf("size" to "small", "page" to "1"))).body)
    // In plain mode a guest that sends its own token is not handed the anonymous prefetch.
    assertEquals("network", http.execute(HttpRequest("GET", "items", headers = mapOf("Authorization" to "Bearer secret-2"), query = mapOf("size" to "small", "page" to "1"))).body)
    // Still there for the identical request.
    assertEquals("prefetched", http.execute(guestsItems).body)
    assertEquals(5, server.requestCount)
    // L1: each near miss is logged (debug builds), with names and never values.
    val nearMisses = logged.filter { "not taken by a GET of the same path" in it }
    assertEquals(logged.toString(), 4, nearMisses.size)
    assertTrue(nearMisses[0], "another query" in nearMisses[0])
    assertTrue(nearMisses[2], "headers of its own: [x-trace]" in nearMisses[2])
    assertTrue(nearMisses[3], "headers of its own: [authorization]" in nearMisses[3])
    assertTrue(logged.toString(), logged.none { "secret" in it || "small" in it || "large" in it })
  }

  @Test fun theAppsOwnClientDropsTheGuestsAuthorizationAndTheRequestStillMatches() = runBlocking {
    server.enqueue(ok("prefetched"))
    val policy = RequestPolicy(base(), setOf(RequestPolicy.Rule("GET", "items")), allowCleartext = true)
    val http = http(items, policy = policy, appOwnsCredentials = true).prefetchedOnce()
    val response = http.execute(HttpRequest("GET", "items", headers = mapOf("Authorization" to "Bearer guest"), query = mapOf("size" to "small", "page" to "1")))
    assertEquals("prefetched", response.body)
    assertEquals(1, server.requestCount)
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
    val response = http.execute(guestsItems)
    assertEquals(200, response.status)
    assertEquals("fresh", response.body)
    assertEquals(2, server.requestCount)
    assertTrue(logged.toString(), logged.any { "answered 503" in it })
  }

  @Test fun aPrefetchThatFailsWithAnIOExceptionIsNotUsedAndTheGuestsRequestGoesOut() = runBlocking {
    server.enqueue(ok("fresh"))
    // The first call (the prefetch) fails on the way out; the guest's goes through.
    val calls = AtomicInteger()
    val client = OkHttpClient.Builder().addInterceptor { chain ->
      if (calls.getAndIncrement() == 0) throw IOException("the network went away") else chain.proceed(chain.request())
    }.build()
    val http = http(items, client = client)
    http.startPrefetch(scope)
    prefetched.uiStarting()
    prefetched.settled()
    assertEquals(0, server.requestCount)
    val response = http.execute(guestsItems)
    assertEquals("fresh", response.body)
    assertEquals(1, server.requestCount)
    assertTrue(logged.toString(), logged.any { "failed (IOException)" in it })
  }

  @Test fun aGuestRequestThatArrivesWhileThePrefetchIsInFlightWaitsForIt() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body("prefetched").headersDelay(400, TimeUnit.MILLISECONDS).build())
    server.enqueue(ok("fresh"))
    val http = http(items, maxWaitMillis = 5_000L).prefetchedOnce()
    // The server has the prefetch and is still holding its answer.
    assertEquals("prefetched", http.execute(guestsItems).body)
    assertEquals(1, server.requestCount)
  }

  @Test fun aStalledPrefetchIsWaitedForOnlyMaxWaitThenTheGuestsRequestGoesOut() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body("prefetched").headersDelay(5, TimeUnit.SECONDS).build())
    server.enqueue(ok("fresh"))
    val http = http(items, maxWaitMillis = 200L).prefetchedOnce()
    val began = System.nanoTime()
    assertEquals("fresh", http.execute(guestsItems).body)
    val tookMillis = (System.nanoTime() - began) / 1_000_000
    assertTrue("took $tookMillis ms", tookMillis in 200 until 3_000)
    assertEquals(2, server.requestCount)
    assertTrue(logged.toString(), logged.any { "still in flight after 200 ms" in it })
  }

  @Test fun anOldAnswerIsDroppedAndNeverHandedOut() = runBlocking {
    server.enqueue(ok("prefetched"))
    server.enqueue(ok("fresh"))
    val http = http(items, maxAgeMillis = 1_000L).answeredOnce()
    clock += 1_001L
    assertEquals("fresh", http.execute(guestsItems).body)
    assertEquals(2, server.requestCount)
    assertTrue(logged.toString(), logged.any { "expired untaken" in it })
  }

  @Test fun theLifetimeCountsFromTheAnswersArrivalNotFromTheSend() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body("prefetched").headersDelay(300, TimeUnit.MILLISECONDS).build())
    val http = http(items, maxAgeMillis = 1_000L).prefetchedOnce()
    // Sent at 0; the answer arrives at 5,000.
    clock = 5_000L
    prefetched.settled()
    clock = 5_900L
    assertEquals("prefetched", http.execute(guestsItems).body)
    assertEquals(1, server.requestCount)
  }

  @Test fun anyRequestButAGetDropsWhatWasNotTaken() = runBlocking {
    server.enqueue(ok("prefetched"))
    server.enqueue(ok("saved"))
    server.enqueue(ok("fresh"))
    val http = http(items).answeredOnce()
    // The guest changes something (the screen it opened from a link), then goes to the list.
    assertEquals("saved", http.execute(HttpRequest("PUT", "items/7", body = "{}")).body)
    assertEquals("fresh", http.execute(guestsItems).body)
    assertEquals(3, server.requestCount)
    assertTrue(logged.toString(), logged.any { "dropped untaken: a PUT went through HostHttp" in it })
  }

  @Test fun discardDropsWhatWasNotTaken() = runBlocking {
    // What KeliverHost.discardPrefetched() calls, on sign-out or a change of user.
    server.enqueue(ok("user A's"))
    server.enqueue(ok("user B's"))
    val http = http(items).answeredOnce()
    prefetched.discard("the app called discardPrefetched()")
    assertEquals("user B's", http.execute(guestsItems).body)
    assertEquals(2, server.requestCount)
  }

  @Test fun aStartThatShowsNoScreenSendsNothingAndTheFirstScreenSendsIt() = runBlocking {
    server.enqueue(ok("prefetched"))
    val http = http(items)
    // run() armed it: a push, a job or a broadcast started the process; no activity.
    http.startPrefetch(scope)
    assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    assertEquals(0, server.requestCount)
    // Later, the user opens the app in the same process.
    prefetched.uiStarting()
    assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
    assertEquals("prefetched", http.execute(guestsItems).body)
    assertEquals(1, server.requestCount)
  }

  @Test fun aUiStartBeforeTheHostHttpIsReadySendsItWhenItIs() = runBlocking {
    server.enqueue(ok("prefetched"))
    val http = http(items)
    prefetched.uiStarting()
    assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    http.startPrefetch(scope)
    assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
    assertEquals("prefetched", http.execute(guestsItems).body)
  }

  @Test fun aDeclaredRequestThePolicyRefusesSendsNothing() {
    val policy = RequestPolicy(base(), setOf(RequestPolicy.Rule("GET", "orders")), allowCleartext = true)
    val http = http(items, policy = policy)
    http.startPrefetch(scope)
    prefetched.uiStarting()
    try {
      runBlocking { http.execute(guestsItems) }
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
    prefetched.uiStarting()
    http.startPrefetch(scope)
    prefetched.uiStarting()
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
