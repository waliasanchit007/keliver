package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UISceneWillEnterForegroundNotification
import platform.posix.usleep

/**
 * NF4 on iOS: how `Keliver` makes the guests' one HostHttp and sends the app's declared first
 * requests (IosHttp.kt: ProcessHostHttp). `Keliver.start()` calls [ProcessHostHttp.firstStart]
 * on every start (a start retried after "no bundle" included), and startHost() calls
 * [ProcessHostHttp.checksPassed] once the key and the bundle server are good; a Keliver screen
 * calls UiStartWatch.screenShown(). Through NSURLSessionHostHttp to a local server, on the
 * simulator: `./gradlew -p host-ios iosSimulatorArm64Test`.
 */
class ProcessHostHttpTest {
  private val server = LocalServer()
  private val base = "http://127.0.0.1:${server.port}/api"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val center = NSNotificationCenter()
  private val logged = mutableListOf<String>() // main thread only: ProcessHostHttp's log runs on it

  private val items = KeliverPrefetch("/items", mapOf("page" to "1"))
  private val orders = KeliverPrefetch("/orders")

  @AfterTest fun stop() {
    scope.cancel()
    server.stop()
  }

  /** As `Keliver` makes it, with a HostHttp that asks the app's headers here, not on the main thread (the test holds it). */
  private fun processHttp(inForeground: Boolean): Pair<ProcessHostHttp, UiStartWatch> {
    val watch = UiStartWatch(inForeground = { inForeground }, center = center)
    val made = ProcessHostHttp(watch, scope, log = { logged += it }) { base, policy, headers, prefetched ->
      NSURLSessionHostHttp(base, policy, headers, EmptyCoroutineContext, 2.seconds, prefetched = prefetched)
    }
    return made to watch
  }

  private fun policy(vararg paths: String) =
    RequestPolicy("http://127.0.0.1:${server.port}", paths.map { RequestPolicy.Rule("GET", it) }.toSet(), allowStateChanging = false, allowCleartext = true)

  private fun bearer(token: String): KeliverRequestHeaders = { _, _, done -> done(mapOf("Authorization" to "Bearer $token")) }

  private fun seen(count: Int, seconds: Int = 5): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (server.requests.size < count && start.elapsedNow() < seconds.seconds) usleep(10_000u)
    return server.requests.size >= count
  }

  private fun nothingMoreThan(count: Int) {
    usleep(400_000u)
    assertEquals(count, server.requests.size, server.requests.toString())
  }

  @Test fun startTwiceMakesOneHostHttpAndSendsTheDeclaredRequestOnce() {
    val (http, _) = processHttp(inForeground = true)
    http.firstStart(base, null, null, listOf(items))
    val first = http.http
    http.checksPassed()
    assertTrue(seen(1), "the prefetch did not go out")
    // start() again: nothing new is made or sent.
    http.firstStart(base, null, null, listOf(items))
    http.checksPassed()
    assertSame(first, http.http)
    nothingMoreThan(1)
    assertTrue(server.requests[0].startsWith("GET /api/items?page=1 HTTP/1.1\r\n"), server.requests[0])
  }

  @Test fun aStartRetriedAfterNoBundleKeepsTheFirstStartsSettings() = runBlocking {
    val (http, _) = processHttp(inForeground = true)
    http.firstStart(base, policy("items"), bearer("first"), listOf(items))
    val first = http.http
    val prefetched = http.prefetched
    http.checksPassed()
    assertTrue(seen(1))
    // "No bundle": the app changes its settings, then the host starts again.
    http.firstStart(base, policy("orders"), bearer("second"), listOf(orders))
    http.checksPassed()
    assertSame(first, http.http)
    assertSame(prefetched, http.prefetched)
    nothingMoreThan(1)
    assertTrue("\r\nauthorization: bearer first\r\n" in server.requests[0].lowercase(), server.requests[0])
    // The guest's request finds what the first start's prefetch sent, through the same HostHttp.
    assertEquals("ok", first!!.execute(HttpRequest("GET", "items", query = mapOf("page" to "1"))).body)
    assertEquals(1, server.requests.size)
    // And the first policy still applies: /orders is not allowed.
    assertTrue(runCatching { first.execute(HttpRequest("GET", "orders")) }.exceptionOrNull() is HostHttpRefused)
  }

  @Test fun aPrefetchDeclaredOnlyBeforeARetriedStartIsNotSentAndNothingIsHalfMade() {
    val (http, watch) = processHttp(inForeground = true)
    http.firstStart(base, null, null, emptyList())
    http.checksPassed()
    // Declared in the gap after "no bundle" (Keliver's setter logs it applies from the next process).
    http.firstStart(base, null, null, listOf(items))
    http.checksPassed()
    watch.screenShown()
    assertTrue(http.started)
    assertNull(http.prefetched)
    nothingMoreThan(0)
  }

  @Test fun aScreenBeforeStartSendsItOnceTheHostPassedItsChecks() {
    // A background launch, then a Keliver screen made before Keliver.start() (the screen starts the host).
    val (http, watch) = processHttp(inForeground = false)
    watch.screenShown()
    http.firstStart(base, null, null, listOf(items))
    nothingMoreThan(0) // the host has not passed its checks yet
    http.checksPassed()
    assertTrue(seen(1))
    nothingMoreThan(1)
  }

  @Test fun aHostThatRefusesToLoadSendsNothingEvenInTheForeground() {
    val (http, watch) = processHttp(inForeground = false)
    http.firstStart(base, null, null, listOf(items))
    // startHost() refused (a bad key or bundle server): checksPassed() is never called.
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    watch.screenShown()
    nothingMoreThan(0)
  }

  @Test fun aBackgroundLaunchThatPassedItsChecksSendsWhenASceneEntersTheForeground() {
    val (http, _) = processHttp(inForeground = false)
    http.firstStart(base, null, null, listOf(items))
    http.checksPassed()
    nothingMoreThan(0)
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    assertTrue(seen(1))
    nothingMoreThan(1)
  }

  @Test fun withoutAnApiBaseNothingIsMadeAndADeclaredPrefetchIsLogged() {
    val (http, watch) = processHttp(inForeground = true)
    http.firstStart(null, null, null, listOf(items))
    http.checksPassed()
    watch.screenShown()
    assertNull(http.http)
    assertNull(http.prefetched)
    assertEquals(listOf("prefetch is set but there is no API_BASE_URL: nothing is prefetched"), logged)
    nothingMoreThan(0)
  }

  @Test fun discardBeforeTheAnswerIsTakenDropsIt() = runBlocking {
    val (http, _) = processHttp(inForeground = true)
    http.firstStart(base, null, null, listOf(items))
    http.checksPassed()
    assertTrue(seen(1))
    http.prefetched!!.settled()
    http.discard("the app called discardPrefetched()")
    http.http!!.execute(HttpRequest("GET", "items", query = mapOf("page" to "1")))
    assertEquals(2, server.requests.size) // not taken: it went to the network
  }
}
