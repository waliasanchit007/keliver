package @@PACKAGE@@

import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A held app's calls out (CallGates.kt): HostHttp and the app's bridges' context wait while the app
 * is held, go once it shows, and fail without reaching the network or the app's code once it is
 * dropped. The iOS host has the same file.
 */
class CallGatesTest {
  /** The network: what reached it. */
  private class Network : HostHttpProvider {
    val sent = mutableListOf<String>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
      synchronized(sent) { sent += request.path }
      return HttpResponse(200, "ok")
    }
  }

  private fun held() = LoadFence(held = true) { it() }

  @Test
  fun aHeldAppsRequestGoesOnceItShows() = runBlocking {
    val network = Network()
    val fence = held()
    val http = GatedHostHttp(network, fence.calls)
    val call = async(kotlinx.coroutines.Dispatchers.Default) { http.execute(HttpRequest("POST", "session/start")) }
    delay(200)
    assertFalse("held: still waiting", call.isCompleted)
    assertEquals(emptyList<String>(), network.sent)
    fence.goLive()
    assertEquals(200, withTimeout(5_000) { call.await() }.status)
    assertEquals(listOf("session/start"), network.sent)
    // The host's app now: straight through.
    http.execute(HttpRequest("GET", "orders"))
    assertEquals(listOf("session/start", "orders"), network.sent)
  }

  @Test
  fun aDroppedAppsRequestFailsUnsent() = runBlocking {
    val network = Network()
    val fence = held()
    val http = GatedHostHttp(network, fence.calls)
    val call = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { http.execute(HttpRequest("POST", "session/start")) } }
    delay(200)
    fence.drop()
    val failed = withTimeout(5_000) { call.await() }.exceptionOrNull()
    assertTrue("dropped: it fails ($failed)", failed is IllegalStateException && DROPPED_CALL in failed.message.orEmpty())
    assertTrue("a later one fails too", runCatching { http.execute(HttpRequest("GET", "orders")) }.isFailure)
    assertEquals("nothing reached the network", emptyList<String>(), network.sent)
  }

  @Test
  fun aLiveAppIsNotHeld() = runBlocking {
    val network = Network()
    val fence = LoadFence(held = false) { it() }
    GatedHostHttp(network, fence.calls).execute(HttpRequest("GET", "orders"))
    fence.drop() // an update replaced it: its calls go on until it is closed
    GatedHostHttp(network, fence.calls).execute(HttpRequest("GET", "more"))
    assertEquals(listOf("orders", "more"), network.sent)
  }

  /** As a generated bridge's guard runs the app's code: launched on the bridge's context. */
  private fun bridgeCall(scope: CoroutineScope, context: GatedDispatcher, ran: CountDownLatch, ended: CountDownLatch, cancelled: MutableList<Boolean>) {
    scope.launch(context) { ran.countDown() }.invokeOnCompletion {
      cancelled += it != null
      ended.countDown()
    }
  }

  @Test
  fun aHeldAppsBridgeCodeRunsOnceItShows() {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val fence = held()
      val context = GatedDispatcher(executor.asCoroutineDispatcher(), fence.calls)
      val scope = CoroutineScope(SupervisorJob())
      val ran = CountDownLatch(1)
      val ended = CountDownLatch(1)
      val cancelled = mutableListOf<Boolean>()
      bridgeCall(scope, context, ran, ended, cancelled)
      assertFalse("held: the app's code waits", ran.await(200, TimeUnit.MILLISECONDS))
      fence.goLive()
      assertTrue("shown: it runs", ran.await(5, TimeUnit.SECONDS))
      assertTrue(ended.await(5, TimeUnit.SECONDS))
      assertEquals(listOf(false), cancelled)
    } finally {
      executor.shutdown()
    }
  }

  @Test
  fun aDroppedAppsBridgeCodeNeverRuns() {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val fence = held()
      val context = GatedDispatcher(executor.asCoroutineDispatcher(), fence.calls)
      val scope = CoroutineScope(SupervisorJob())
      val ran = CountDownLatch(1)
      val ended = CountDownLatch(2)
      val cancelled = mutableListOf<Boolean>()
      bridgeCall(scope, context, ran, ended, cancelled)
      fence.drop()
      bridgeCall(scope, context, ran, ended, cancelled) // a call after the drop
      assertTrue("both calls end", ended.await(5, TimeUnit.SECONDS))
      assertFalse("the app's code never ran", ran.await(200, TimeUnit.MILLISECONDS))
      assertEquals("each ended cancelled (the guard answers the guest with a failure)", listOf(true, true), cancelled)
    } finally {
      executor.shutdown()
    }
  }

  @Test
  fun aWaitingCallIsNotLost() = runBlocking {
    // The guest stops waiting (its call is cancelled) while held: nothing is sent, ever.
    val network = Network()
    val fence = held()
    val http = GatedHostHttp(network, fence.calls)
    try {
      withTimeout(100) { http.execute(HttpRequest("POST", "session/start")) }
      fail("held")
    } catch (_: TimeoutCancellationException) {
    }
    fence.drop()
    assertEquals(emptyList<String>(), network.sent)
  }
}
