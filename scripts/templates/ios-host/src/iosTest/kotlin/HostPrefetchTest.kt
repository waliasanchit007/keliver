package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSLock
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UISceneWillEnterForegroundNotification
import platform.posix.usleep

/**
 * NF4 on iOS: the first screen's declared request (Keliver.prefetch), sent at a start toward a
 * screen and taken by the guest's identical one (HostPrefetch.kt, the Android host's file; UiStart.kt),
 * through NSURLSessionHostHttp to a local server, on the iOS simulator:
 * `./gradlew -p host-ios iosSimulatorArm64Test`. The same rules as the Android host's HostPrefetchTest.
 */
class HostPrefetchTest {
  private val server = LocalServer()
  private val base = "http://127.0.0.1:${server.port}/api"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  @Volatile private var clock = 0L
  private val lock = NSLock()
  private val logs = mutableListOf<String>()
  private val logged: List<String> get() = locked { logs.toList() }
  private val replies = ArrayDeque<Reply>()
  private lateinit var prefetched: PrefetchedRequests

  private class Reply(val status: Int, val body: String, val delayMillis: Int = 0)

  init {
    server.respond = {
      val reply = locked { replies.removeFirstOrNull() } ?: Reply(200, "ok")
      if (reply.delayMillis > 0) usleep((reply.delayMillis * 1_000).toUInt())
      "HTTP/1.1 ${reply.status} X\r\nContent-Length: ${reply.body.encodeToByteArray().size}\r\nConnection: close\r\n\r\n${reply.body}"
    }
  }

  @AfterTest fun stop() {
    scope.cancel()
    server.stop()
  }

  private fun enqueue(body: String, status: Int = 200, delayMillis: Int = 0) = locked { replies.addLast(Reply(status, body, delayMillis)) }

  private val items = KeliverPrefetch("/items", mapOf("size" to "small", "page" to "1"))
  private val guestsItems = HttpRequest("GET", "items", query = mapOf("size" to "small", "page" to "1"))

  private fun policy(vararg rules: RequestPolicy.Rule) =
    RequestPolicy("http://127.0.0.1:${server.port}", rules.toSet(), allowStateChanging = false, allowCleartext = true)

  private fun http(
    vararg declared: KeliverPrefetch,
    policy: RequestPolicy? = null,
    headers: KeliverRequestHeaders? = null,
    maxAgeMillis: Long = PrefetchedRequests.DEFAULT_PREFETCH_MAX_AGE_MILLIS,
    maxWaitMillis: Long = PrefetchedRequests.DEFAULT_PREFETCH_MAX_WAIT_MILLIS,
  ): NSURLSessionHostHttp {
    prefetched = PrefetchedRequests(declared.toList(), maxAgeMillis, maxWaitMillis, now = { clock }, log = { line -> locked { logs += line } })
    return NSURLSessionHostHttp(base, policy, headers, EmptyCoroutineContext, 2.seconds, prefetched = prefetched)
  }

  /** Whether the server has seen [count] requests within [seconds]. */
  private fun seen(count: Int, seconds: Int = 5): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (server.requests.size < count && start.elapsedNow() < seconds.seconds) usleep(10_000u)
    return server.requests.size >= count
  }

  /** Nothing more reaches the server for a while. */
  private fun nothingMoreThan(count: Int) {
    usleep(400_000u)
    assertEquals(count, server.requests.size, server.requests.toString())
  }

  /** A start toward a screen: armed when the host started, then the UI start; waits until the server has it. */
  private fun NSURLSessionHostHttp.prefetchedOnce(): NSURLSessionHostHttp = apply {
    startPrefetch(scope)
    prefetched.uiStarting()
    assertTrue(seen(1), "the prefetch did not reach the server")
    assertTrue(server.requests[0].startsWith("GET /api/items?size=small&page=1 HTTP/1.1\r\n"), server.requests[0])
  }

  /** As [prefetchedOnce], and its answer has arrived. */
  private fun NSURLSessionHostHttp.answeredOnce(): NSURLSessionHostHttp = prefetchedOnce().apply { runBlocking { prefetched.settled() } }

  @Test fun theGuestsIdenticalRequestTakesThePrefetchedResponse() = runBlocking {
    enqueue("prefetched")
    val http = http(items).prefetchedOnce()
    // The guest's own form of the same request: the method in lower case, the query in another order.
    val response = http.execute(HttpRequest("get", "items", query = mapOf("page" to "1", "size" to "small")))
    assertEquals("prefetched", response.body)
    assertEquals(1, server.requests.size)
  }

  @Test fun itIsTakenOnceAndTheNextIdenticalRequestGoesToTheNetwork() = runBlocking {
    enqueue("prefetched")
    enqueue("fresh")
    val http = http(items).prefetchedOnce()
    val request = HttpRequest("GET", "/items", query = mapOf("size" to "small", "page" to "1"))
    assertEquals("prefetched", http.execute(request).body)
    assertEquals("fresh", http.execute(request).body)
    assertEquals(2, server.requests.size)
  }

  @Test fun anotherQueryOrAHeaderOfTheGuestsOwnIsNotTheSameRequestAndSaysWhy() = runBlocking {
    enqueue("prefetched")
    repeat(4) { enqueue("network") }
    val http = http(items).answeredOnce()
    assertEquals("network", http.execute(HttpRequest("GET", "items", query = mapOf("size" to "large", "page" to "1"))).body)
    assertEquals("network", http.execute(HttpRequest("GET", "items", query = mapOf("size" to "small"))).body)
    assertEquals("network", http.execute(HttpRequest("GET", "items", headers = mapOf("X-Trace" to "secret-1"), query = mapOf("size" to "small", "page" to "1"))).body)
    // Without the app's credentials a guest that sends its own token is not handed the anonymous prefetch.
    assertEquals("network", http.execute(HttpRequest("GET", "items", headers = mapOf("Authorization" to "Bearer secret-2"), query = mapOf("size" to "small", "page" to "1"))).body)
    // Still there for the identical request.
    assertEquals("prefetched", http.execute(guestsItems).body)
    assertEquals(5, server.requests.size)
    // Each near miss is logged (DEBUG binaries), with names and never values.
    val nearMisses = logged.filter { "not taken by a GET of the same path" in it }
    assertEquals(4, nearMisses.size, logged.toString())
    assertTrue("another query" in nearMisses[0], nearMisses[0])
    assertTrue("headers of its own: [x-trace]" in nearMisses[2], nearMisses[2])
    assertTrue("headers of its own: [authorization]" in nearMisses[3], nearMisses[3])
    assertTrue(logged.none { "secret" in it || "small" in it || "large" in it }, logged.toString())
  }

  @Test fun theAppsCredentialsGoWithThePrefetchAndAreNotAskedAgainForTheGuestsRequest() = runBlocking {
    enqueue("prefetched")
    val asked = mutableListOf<String>()
    val signedIn: KeliverRequestHeaders = { method, path, done ->
      locked { asked += "$method $path" }
      done(mapOf("Authorization" to "Bearer app-token"))
    }
    val http = http(items, policy = policy(RequestPolicy.Rule("GET", "items")), headers = signedIn).prefetchedOnce()
    assertTrue("\r\nauthorization: bearer app-token\r\n" in server.requests[0].lowercase(), server.requests[0])
    // With the app's credentials the guest's own Authorization is dropped, so the request still matches.
    val response = http.execute(HttpRequest("GET", "items", headers = mapOf("Authorization" to "Bearer guest"), query = mapOf("size" to "small", "page" to "1")))
    assertEquals("prefetched", response.body)
    assertEquals(1, server.requests.size)
    assertEquals(listOf("GET items"), locked { asked.toList() })
  }

  @Test fun aPrefetchTheAppsCredentialsRefusedIsNotUsedAndTheGuestsRequestGoesOut() = runBlocking {
    enqueue("fresh")
    // Signed out when the host started, signed in by the time the screen asks.
    var signedIn = false
    val headers: KeliverRequestHeaders = { _, _, done -> done(if (signedIn) mapOf("Authorization" to "Bearer app-token") else null) }
    val http = http(items, policy = policy(RequestPolicy.Rule("GET", "items")), headers = headers)
    http.startPrefetch(scope)
    prefetched.uiStarting()
    prefetched.settled()
    assertEquals(0, server.requests.size)
    signedIn = true
    assertEquals("fresh", http.execute(guestsItems).body)
    assertEquals(1, server.requests.size)
    assertTrue(logged.any { "failed (HostHttpRefused)" in it }, logged.toString())
  }

  @Test fun aHeaderTheHostDropsAnywayDoesNotStopTheMatch() = runBlocking {
    enqueue("prefetched")
    val http = http(items).prefetchedOnce()
    val response = http.execute(HttpRequest("GET", "items", headers = mapOf("Connection" to "keep-alive"), query = mapOf("size" to "small", "page" to "1")))
    assertEquals("prefetched", response.body)
    assertEquals(1, server.requests.size)
  }

  @Test fun onlyA2xxIsUsedAndTheGuestsRequestGoesOut() = runBlocking {
    enqueue("busy", status = 503)
    enqueue("fresh")
    val http = http(items).prefetchedOnce()
    val response = http.execute(guestsItems)
    assertEquals(200, response.status)
    assertEquals("fresh", response.body)
    assertEquals(2, server.requests.size)
    assertTrue(logged.any { "answered 503" in it }, logged.toString())
  }

  @Test fun aGuestRequestThatArrivesWhileThePrefetchIsInFlightWaitsForIt() = runBlocking {
    enqueue("prefetched", delayMillis = 400)
    enqueue("fresh")
    val http = http(items).prefetchedOnce()
    // The server has the prefetch and is still holding its answer: within the default 1 s.
    assertEquals("prefetched", http.execute(guestsItems).body)
    assertEquals(1, server.requests.size)
  }

  @Test fun aStalledPrefetchIsWaitedForOneSecondThenTheGuestsRequestGoesOut() = runBlocking {
    enqueue("prefetched", delayMillis = 4_000)
    enqueue("fresh")
    val http = http(items).prefetchedOnce()
    val began = TimeSource.Monotonic.markNow()
    assertEquals("fresh", http.execute(guestsItems).body)
    val took = began.elapsedNow().inWholeMilliseconds
    assertTrue(took in 1_000 until 3_000, "took $took ms")
    assertEquals(2, server.requests.size)
    assertTrue(logged.any { "still in flight after 1000 ms" in it }, logged.toString())
  }

  @Test fun anAnswerOlderThanFiveSecondsIsDroppedAndNeverHandedOut() = runBlocking {
    enqueue("prefetched")
    enqueue("fresh")
    val http = http(items).answeredOnce()
    clock += 5_001L
    assertEquals("fresh", http.execute(guestsItems).body)
    assertEquals(2, server.requests.size)
    assertTrue(logged.any { "expired untaken" in it }, logged.toString())
  }

  @Test fun theFiveSecondsCountFromTheAnswersArrivalNotFromTheSend() = runBlocking {
    enqueue("prefetched", delayMillis = 300)
    val http = http(items).prefetchedOnce()
    // Sent at 0; the answer arrives at 5,000.
    clock = 5_000L
    prefetched.settled()
    clock = 10_000L
    assertEquals("prefetched", http.execute(guestsItems).body)
    assertEquals(1, server.requests.size)
  }

  @Test fun anyRequestButAGetDropsWhatWasNotTaken() = runBlocking {
    enqueue("prefetched")
    enqueue("saved")
    enqueue("fresh")
    val http = http(items).answeredOnce()
    // The guest changes something (the screen it opened from a link), then goes to the list.
    assertEquals("saved", http.execute(HttpRequest("PUT", "items/7", body = "{}")).body)
    assertEquals("fresh", http.execute(guestsItems).body)
    assertEquals(3, server.requests.size)
    assertTrue(logged.any { "dropped untaken: a PUT went through HostHttp" in it }, logged.toString())
  }

  @Test fun aRefusedNonGetDropsWhatWasNotTakenToo() = runBlocking {
    enqueue("prefetched")
    enqueue("fresh")
    val http = http(items, policy = policy(RequestPolicy.Rule("GET", "items"))).answeredOnce()
    assertFailsWith<HostHttpRefused> { http.execute(HttpRequest("DELETE", "items/7")) }
    assertEquals("fresh", http.execute(guestsItems).body)
    assertEquals(2, server.requests.size)
  }

  @Test fun discardDropsWhatWasNotTaken() = runBlocking {
    // What Keliver.shared.discardPrefetched() calls, on sign-out or a change of user.
    enqueue("user A's")
    enqueue("user B's")
    val http = http(items).answeredOnce()
    prefetched.discard("the app called discardPrefetched()")
    assertEquals("user B's", http.execute(guestsItems).body)
    assertEquals(2, server.requests.size)
    assertTrue(logged.any { "dropped untaken: the app called discardPrefetched()" in it }, logged.toString())
  }

  @Test fun aBackgroundLaunchSendsNothingUntilASceneEntersTheForeground() = runBlocking {
    enqueue("prefetched")
    val center = NSNotificationCenter()
    val watch = UiStartWatch(inForeground = { false }, center = center)
    val http = http(items)
    // Keliver.start() in a process launched for a push or a background refresh: armed, not sent.
    http.startPrefetch(scope)
    watch.whenStarted { prefetched.uiStarting() }
    nothingMoreThan(0)
    // Later, the user opens the app in the same process.
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    assertTrue(seen(1))
    assertEquals("prefetched", http.execute(guestsItems).body)
    assertEquals(1, server.requests.size)
  }

  @Test fun aForegroundStartSendsAtOnce() {
    var ran = 0
    UiStartWatch(inForeground = { true }, center = NSNotificationCenter()).whenStarted { ran++ }
    assertEquals(1, ran)
  }

  @Test fun eachUiStartNotificationStartsItOnceAndIsThenUnobserved() {
    for (name in UiStartWatch.UI_START_NOTIFICATIONS) {
      val center = NSNotificationCenter()
      val watch = UiStartWatch(inForeground = { false }, center = center)
      var ran = 0
      watch.whenStarted { ran++ }
      assertEquals(0, ran, name)
      center.postNotificationName(name, null)
      UiStartWatch.UI_START_NOTIFICATIONS.forEach { center.postNotificationName(it, null) }
      watch.whenStarted { ran += 10 } // started: runs at once
      assertEquals(11, ran, name)
    }
  }

  @Test fun aKeliverScreenIsAStartTowardAScreenBeforeOrAfterTheHostStarts() {
    val before = UiStartWatch(inForeground = { false }, center = NSNotificationCenter())
    before.screenShown()
    var ran = 0
    before.whenStarted { ran++ }
    assertEquals(1, ran)
    val after = UiStartWatch(inForeground = { false }, center = NSNotificationCenter())
    after.whenStarted { ran++ }
    assertEquals(1, ran)
    after.screenShown()
    after.screenShown()
    assertEquals(2, ran)
  }

  @Test fun aUiStartBeforeTheHostHttpIsReadySendsItWhenItIs() = runBlocking {
    enqueue("prefetched")
    val http = http(items)
    prefetched.uiStarting()
    nothingMoreThan(0)
    http.startPrefetch(scope)
    assertTrue(seen(1))
    assertEquals("prefetched", http.execute(guestsItems).body)
  }

  @Test fun aDeclaredRequestThePolicyRefusesSendsNothing() {
    val http = http(items, policy = policy(RequestPolicy.Rule("GET", "orders")))
    http.startPrefetch(scope)
    prefetched.uiStarting()
    val e = assertFailsWith<HostHttpRefused> { runBlocking { http.execute(guestsItems) } }
    assertTrue("is not in the allow-list" in e.message.orEmpty(), e.message)
    nothingMoreThan(0)
  }

  @Test fun startIsOnceAndWithoutDeclarationsNothingIsSent() = runBlocking {
    enqueue("prefetched")
    val http = http(items)
    http.startPrefetch(scope)
    prefetched.uiStarting()
    http.startPrefetch(scope)
    prefetched.uiStarting()
    assertTrue(seen(1))
    nothingMoreThan(1)
    NSURLSessionHostHttp(base).startPrefetch(scope)
    nothingMoreThan(1)
  }

  @Test fun aDeclarationMustBeAPlainPathUnderTheApiBase() {
    for (path in listOf("", "/", "a/../b", "items?size=small", "a\\b", "items#x")) {
      try {
        KeliverPrefetch(path)
        fail("'$path' was accepted")
      } catch (e: IllegalArgumentException) {
        assertTrue("KeliverPrefetch" in e.message.orEmpty(), e.message)
      }
    }
    assertEquals(listOf("v1", "items"), KeliverPrefetch("/v1//items/").segments)
  }

  private fun <T> locked(block: () -> T): T {
    lock.lock()
    try { return block() } finally { lock.unlock() }
  }
}
