@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * NF4: HostPrefetch.kt's calls racing one another on the default dispatcher, many times: the
 * declared request is sent once, never twice. The SAME file is in the Android host's tests (JVM)
 * and the iOS host's (the simulator); keep them identical.
 */
class HostPrefetchRaceTest {
  private val items = KeliverPrefetch("/items", mapOf("page" to "1"))
  private val guests = HttpRequest("GET", "items", query = mapOf("page" to "1"))

  private class Run(val sends: Int, val took: Boolean)

  /** One run: every call starts at once on the default dispatcher; then what was sent. */
  private fun race(withDiscardAndTake: Boolean): Run = runBlocking {
    val sends = AtomicInt(0)
    val took = AtomicInt(0)
    val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Default)
    // 1 ms: the expiry each sent request waits for ends at once, so the run can wait for it.
    val prefetched = PrefetchedRequests(listOf(items), maxAgeMillis = 1, maxWaitMillis = 1_000)
    val send: suspend (HttpRequest) -> HttpResponse = {
      sends.addAndFetch(1)
      HttpResponse(200, "prefetched")
    }
    val calls = mutableListOf<suspend () -> Unit>(
      { prefetched.arm(scope, send) },
      { prefetched.arm(scope, send) },
      { prefetched.uiStarting() },
      { prefetched.uiStarting() },
    )
    if (withDiscardAndTake) {
      calls += { prefetched.discard("the race") }
      calls += {
        // A response taken before the winner started it starts when awaited: still once.
        val pending = prefetched.take(guests, emptyMap())
        if (pending != null && runCatching { pending.await() }.getOrNull()?.body == "prefetched") took.addAndFetch(1)
      }
    }
    val go = CompletableDeferred<Unit>()
    val racing = calls.map { call -> launch(Dispatchers.Default) { go.await(); call() } }
    go.complete(Unit)
    racing.joinAll()
    // Every request started (and its expiry) has finished: a lost race's request was cancelled unsent.
    job.children.toList().joinAll()
    // Arming and starting again later sends nothing more: once for the object's life.
    prefetched.arm(scope, send)
    prefetched.uiStarting()
    job.children.toList().joinAll()
    scope.cancel()
    Run(sends.load(), took.load() == 1)
  }

  @Test fun armAndUiStartRacingSendTheRequestExactlyOnce() {
    repeat(1_000) { run ->
      assertEquals(1, race(withDiscardAndTake = false).sends, "run $run")
    }
  }

  @Test fun aDiscardAndATakeInTheRaceNeverMakeItSendTwice() {
    var taken = 0
    repeat(1_000) { run ->
      val result = race(withDiscardAndTake = true)
      assertTrue(result.sends <= 1, "run $run: sent ${result.sends} times")
      if (result.took) {
        taken++
        assertEquals(1, result.sends, "run $run: a response was taken, so it was sent")
      }
    }
    // Not asserted (scheduling decides), only reported: how often the guest's request won it.
    println("HostPrefetchRaceTest: the guest's request took the response in $taken of 1000 runs")
  }
}
