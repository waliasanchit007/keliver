/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
