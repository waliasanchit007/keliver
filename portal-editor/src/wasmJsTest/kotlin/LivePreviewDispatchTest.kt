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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/**
 * Three actions must move the PREVIEWED value `0 → 1 → 2 → 3`, not just the
 * presenter's own state.
 *
 * **A correction.** This file used to model the host as producing frames only
 * when something woke it, and its failure without `HostWakeSignal` was reported
 * as proof of a production scheduling defect. That model was wrong: the editor's
 * frame pump parks in `withFrameNanos` on the host recomposer's clock, which
 * makes the recomposer request a browser frame every frame regardless of
 * invalidation — measured at 60/s in a real editor. The old failure was the
 * harness withholding frames production delivers. See
 * `docs/superpowers/evidence/adopter-preview-route/U19-RECONCILIATION.md`.
 *
 * What remains here is worth keeping on its own terms: actions accumulate, and
 * the boundaries that are meant to reset do.
 */
class LivePreviewDispatchTest {

  private class CountingPreview : AppPreviewEntry {
    override val label = "test"
    override val screens: Map<String, ScreenPreview> = mapOf(
      "home" to ScreenPreview {
        var count by remember { mutableStateOf(0) }
        PreviewFrame(
          values = mapOf("tally" to "$count tallied"),
          dispatch = { action, _ -> if (action == "add") count += 1 },
        )
      },
    )
  }

  @AfterTest
  fun cleanUp() {
    LiveEngine.stop()
    appPreviewEntry = null
    PreviewBindings.mocks.clear()
  }

  @Test
  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  fun threeActionsAdvanceThePreviewedValue(): kotlin.js.Promise<kotlin.js.JsAny?> = GlobalScope.promise {
    appPreviewEntry = CountingPreview()

    val editor = PreviewTestEditor()
    LiveEngine.request.value = "home"      // Live is on before the first composition
    editor.start("tally")
    assertEquals("0 tallied", editor.rendered.last(), "initial live frame")

    val seen = mutableListOf<String?>(editor.rendered.last())
    repeat(3) {
      LiveEngine.dispatch("add", null)
      editor.pump()
      seen += editor.rendered.last()
    }

    assertEquals(
      listOf<String?>("0 tallied", "1 tallied", "2 tallied", "3 tallied"),
      seen.toList(),
      "three actions must be visible in the preview, not just in the presenter",
    )

    editor.dispose()
    null
  }

  /**
   * The resets the design intends: [LivePresenterHost] keys the presenter on
   * "screen:persona", and [LiveEngine.stop] ends the session. Those boundaries
   * SHOULD discard state; ordinary actions must not.
   */
  @Test
  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  fun intendedResetBoundariesStillReset(): kotlin.js.Promise<kotlin.js.JsAny?> = GlobalScope.promise {
    appPreviewEntry = TwoScreenPreview()

    val editor = PreviewTestEditor()
    LiveEngine.request.value = "home"
    editor.start("tally")

    LiveEngine.dispatch("add", null); editor.pump()
    LiveEngine.dispatch("add", null); editor.pump()
    assertEquals("2 tallied", PreviewBindings.mocks["tally"], "state accumulates across actions")

    // 1. switching screen disposes the presenter
    LiveEngine.request.value = "other"
    editor.pump()
    assertEquals("other 0", PreviewBindings.mocks["label"], "a different screen starts fresh")
    LiveEngine.request.value = "home"
    editor.pump()
    assertEquals("0 tallied", PreviewBindings.mocks["tally"], "returning to a screen starts it fresh")

    // 2. stopping the session clears live values
    LiveEngine.dispatch("add", null); editor.pump()
    assertEquals("1 tallied", PreviewBindings.mocks["tally"])
    LiveEngine.stop()
    assertEquals(null, PreviewBindings.mocks["tally"], "stop() removes the live values")
    editor.pump()

    // 3. restarting is a fresh session
    LiveEngine.request.value = "home"
    editor.pump()
    assertEquals("0 tallied", PreviewBindings.mocks["tally"], "a restarted session starts fresh")

    editor.dispose()
    null
  }

  /** Two screens, so the screen-change boundary can be exercised. */
  private class TwoScreenPreview : AppPreviewEntry {
    override val label = "test"
    override val screens: Map<String, ScreenPreview> = mapOf(
      "home" to ScreenPreview {
        var count by remember { mutableStateOf(0) }
        PreviewFrame(
          values = mapOf("tally" to "$count tallied"),
          dispatch = { action, _ -> if (action == "add") count += 1 },
        )
      },
      "other" to ScreenPreview {
        var n by remember { mutableStateOf(0) }
        PreviewFrame(
          values = mapOf("label" to "other $n"),
          dispatch = { _, _ -> n += 1 },
        )
      },
    )
  }
}
