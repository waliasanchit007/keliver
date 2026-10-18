import kotlin.js.Promise
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a fake fetch saw of its signal. */
private external interface FetchRecord : JsAny {
  val aborted: Boolean
}

private fun newRecord(): FetchRecord = js("({ aborted: false })")

/** A fetch that never answers, and rejects (as fetch does) when [signal] aborts, noting it in [record]. */
private fun fakeHangingFetch(signal: AbortSignal, record: FetchRecord): Promise<JsAny?> =
  js("new Promise(function (resolve, reject) { signal.addEventListener('abort', function () { record.aborted = true; reject(new Error('aborted')); }); })")

/** A fetch that answers at once. */
private fun fakeAnsweringFetch(): Promise<JsAny?> = js("Promise.resolve(null)")

/** K5: a cancelled image load aborts its browser request, so the connection is freed with the permit. */
class NetworkImageAbortTest {
  private val relay = "http://localhost:18077"

  @Test fun cancellingTheLoadAbortsTheRequest() = runTest {
    val record = newRecord()
    val job = launch { withAbortSignal { signal -> fakeHangingFetch(signal, record).await<JsAny?>() } }
    runCurrent()
    assertFalse(record.aborted, "running, not aborted")
    job.cancel()
    job.join()
    assertTrue(record.aborted, "the fetch saw its signal abort")
  }

  @Test fun aLoadThatFinishesIsNotAborted() = runTest {
    var seen: AbortSignal? = null
    withAbortSignal { signal ->
      seen = signal
      fakeAnsweringFetch().await<JsAny?>()
    }
    assertFalse(seen!!.aborted)
  }

  @Test fun aLoadThatFailsIsAborted() = runTest {
    var seen: AbortSignal? = null
    assertFailsWith<IllegalStateException> {
      withAbortSignal { signal ->
        seen = signal
        error("HTTP 504")
      }
    }
    assertTrue(seen!!.aborted, "a body left unread does not keep the connection")
  }

  @Test fun theRequestIsAbortedAndThePermitFreedTogether() = runTest {
    val gate = PreviewAssetHostGate()
    gate.loaded(setOf("img.example.com"))
    val record = newRecord()
    val job = launch {
      gate.load("https://img.example.com/a.png", relay) { withAbortSignal { signal -> fakeHangingFetch(signal, record).await<JsAny?>() } }
    }
    runCurrent()
    assertEquals(1, gate.hostsInUse())
    job.cancel()
    job.join()
    assertTrue(record.aborted)
    assertEquals(0, gate.hostsInUse())
    val next = CompletableDeferred<String>().apply { complete("ok") }
    assertEquals("ok", gate.load("https://img.example.com/b.png", relay) { next.await() })
  }
}
