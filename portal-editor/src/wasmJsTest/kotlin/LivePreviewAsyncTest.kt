import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewBindings
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.ScreenPreview
import dev.keliver.portal.render.appPreviewEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield

/**
 * Presenter state that arrives from a coroutine rather than from an action must
 * reach the preview.
 *
 * **What these tests are, and are not.** They are integration tests of the
 * projection path — presenter state → [PreviewBindings] → a composable resolving
 * a `Bind`. They are *not* evidence for any host/guest wake mechanism. An
 * earlier version of them failed without the wake mechanism that was then in
 * place — since removed — but only because its harness withheld frames that
 * production delivers. See [PreviewTestEditor] and
 * `docs/superpowers/evidence/adopter-preview-route/U19-RECONCILIATION.md`.
 */
class LivePreviewAsyncTest {

  @AfterTest
  fun cleanUp() {
    LiveEngine.stop()
    appPreviewEntry = null
    PreviewBindings.mocks.clear()
  }

  /** Live starts on a placeholder; the value arrives later, with no interaction. */
  @Test
  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  fun anAsyncCompletionReachesThePreview(): kotlin.js.Promise<kotlin.js.JsAny?> =
    GlobalScope.promise {
      val gate = CompletableDeferred<String>()
      var presenterState = "?"          // recorded separately from what is displayed
      appPreviewEntry = object : AppPreviewEntry {
        override val label = "test"
        override val screens = mapOf(
          "home" to ScreenPreview {
            var status by remember { mutableStateOf("Loading") }
            LaunchedEffect(Unit) {
              val v = gate.await()
              presenterState = v
              status = v
            }
            PreviewFrame(values = mapOf("status" to status), dispatch = { _, _ -> })
          },
        )
      }

      val editor = PreviewTestEditor()
      LiveEngine.request.value = "home"
      editor.start("status")
      assertEquals("Loading", editor.rendered.last(), "the first live frame")

      val framesBefore = editor.hostFrames
      gate.complete("Ready")
      yield()
      assertEquals("Ready", presenterState, "presenter state advanced")

      editor.pump()
      assertEquals("Ready", editor.rendered.last(), "and the preview followed it")
      assertTrue(
        editor.hostFrames > framesBefore,
        "the harness must keep delivering frames the way the browser does",
      )

      editor.dispose()
      null
    }

  /** An action starts async work; the response lands after the action rendered. */
  @Test
  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  fun workStartedByAnActionLandsAfterTheActionRendered(): kotlin.js.Promise<kotlin.js.JsAny?> =
    GlobalScope.promise {
      val responses = Channel<String>(Channel.UNLIMITED)
      appPreviewEntry = object : AppPreviewEntry {
        override val label = "test"
        override val screens = mapOf(
          "home" to ScreenPreview {
            var requests by remember { mutableStateOf(0) }
            var status by remember { mutableStateOf("idle") }
            LaunchedEffect(requests) {
              if (requests > 0) {
                status = "loading"
                status = responses.receive()
              }
            }
            PreviewFrame(
              values = mapOf("status" to status),
              dispatch = { action, _ -> if (action == "load") requests += 1 },
            )
          },
        )
      }

      val editor = PreviewTestEditor()
      LiveEngine.request.value = "home"
      editor.start("status")
      assertEquals("idle", editor.rendered.last())

      LiveEngine.dispatch("load", null)
      editor.pump()
      assertEquals("loading", editor.rendered.last(), "the action's own rendering")

      responses.send("loaded 42")
      yield()
      editor.pump()
      assertEquals(
        "loaded 42",
        editor.rendered.last(),
        "a response that arrives after the action rendered must still show",
      )

      editor.dispose()
      null
    }

  /**
   * The session boundaries dispose pending work. Stopping Live, and switching
   * screen, both end a session; a response already in flight must never be
   * delivered or displayed.
   *
   * Each session gets its OWN response channel — one buffered channel shared
   * across sessions would hand session 1's queued message to session 2, which is
   * a property of the fixture's queue, not of the editor.
   */
  @Test
  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  fun pendingWorkFromAnEndedSessionIsNeverDelivered(): kotlin.js.Promise<kotlin.js.JsAny?> =
    GlobalScope.promise {
      var responses = Channel<String>(Channel.UNLIMITED)
      var deliveries = 0
      appPreviewEntry = object : AppPreviewEntry {
        override val label = "test"
        override val screens = mapOf(
          "home" to ScreenPreview {
            var status by remember { mutableStateOf("idle") }
            LaunchedEffect(Unit) {
              status = "loading"
              val v = responses.receive()
              deliveries += 1
              status = v
            }
            PreviewFrame(values = mapOf("status" to status), dispatch = { _, _ -> })
          },
          "other" to ScreenPreview {
            PreviewFrame(values = mapOf("status" to "other screen"), dispatch = { _, _ -> })
          },
        )
      }

      val editor = PreviewTestEditor()
      LiveEngine.request.value = "home"
      editor.start("status")
      assertEquals("loading", editor.rendered.last(), "the presenter's own async start showed")

      // 1. stop() ends the session while the response is still in flight.
      val session1 = responses
      LiveEngine.stop()
      editor.pump()
      assertEquals(null, PreviewBindings.mocks["status"], "stop() clears the live values")
      session1.send("late from session 1")
      yield()
      editor.pump()
      assertEquals(0, deliveries, "stop() must cancel the presenter's pending work")

      // 2. a restarted session is fresh, and takes only its own response.
      responses = Channel(Channel.UNLIMITED)
      val session2 = responses
      LiveEngine.request.value = "home"
      editor.pump()
      assertEquals("loading", editor.rendered.last(), "a restarted session starts fresh")
      session2.send("session 2")
      yield()
      editor.pump()
      assertEquals("session 2", editor.rendered.last())
      assertEquals(1, deliveries, "only the live session's response was delivered")

      // 3. switching screen ends the session the same way, mid-flight.
      responses = Channel(Channel.UNLIMITED)
      val session3 = responses
      LiveEngine.request.value = "other"
      editor.pump()
      assertEquals("other screen", editor.rendered.last())
      LiveEngine.request.value = "home"
      editor.pump()
      assertEquals("loading", editor.rendered.last(), "session 3 is waiting on its response")
      LiveEngine.request.value = "other"          // ends session 3 while it waits
      editor.pump()
      session3.send("late from session 3")
      yield()
      editor.pump()
      assertEquals(1, deliveries, "a screen switch must cancel the pending work it disposes")
      assertEquals("other screen", editor.rendered.last(), "a screen that ended must not be written into")

      assertTrue(
        editor.rendered.none { it.startsWith("late from") },
        "an ended session's response must never be displayed: ${editor.rendered}",
      )

      editor.dispose()
      null
    }
}
