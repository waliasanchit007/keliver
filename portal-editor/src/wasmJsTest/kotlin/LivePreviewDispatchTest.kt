import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewBindings
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.ScreenPreview
import dev.keliver.portal.render.appPreviewEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield

/**
 * U19 regression: three actions must move the PREVIEWED value 0 -> 1 -> 2 -> 3.
 *
 * This drives the real Live path — [LivePresenterHost] composed in a guest
 * composition, [LiveEngine.dispatch] delivering actions, values landing in
 * [PreviewBindings.mocks] — under the editor's actual clock arrangement:
 *
 *   EditorShell runs the guest composition on its OWN BroadcastFrameClock and
 *   ticks it from the HOST's frames. A host that has nothing to do produces no
 *   frames, so before the fix a presenter state change invalidated only the
 *   guest, which could not ask the host for a frame, and the preview stalled on
 *   its first value while the presenter's own state advanced underneath.
 *
 * The host here is modelled the same way: it only runs a frame when it has
 * work. Any fix that lets a live dispatch wake the host satisfies this test;
 * it is not tied to a particular mechanism.
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

    val guestClock = BroadcastFrameClock()
    val ctx = kotlinx.coroutines.Dispatchers.Unconfined + Job() + guestClock
    val recomposer = Recomposer(ctx)
    val scope = CoroutineScope(ctx)
    scope.launch { recomposer.runRecomposeAndApplyChanges() }
    yield()

    LiveEngine.request.value = "home"      // Live is on before the first composition
    val composition = Composition(UnitApplier(), recomposer)
    composition.setContent { LivePresenterHost() }
    Snapshot.sendApplyNotifications()
    yield()

    // The host: it only produces frames when something invalidated IT. That is
    // what an idle browser host does — no invalidation, no rAF, no frame — and
    // it is deliberately NOT gated on the guest recomposer having work, because
    // the guest always has work here and the host cannot see it.
    var lastWake = -1
    suspend fun hostFrames(frames: Int = 6) {
      if (HostWakeSignal.state.value == lastWake) return   // host stayed asleep
      lastWake = HostWakeSignal.state.value
      repeat(frames) {
        guestClock.sendFrame(it.toLong())
        yield()
      }
    }

    HostWakeSignal.wake()          // the host is awake while starting Live
    hostFrames()
    assertEquals("0 tallied", PreviewBindings.mocks["tally"], "initial live frame")

    val seen = mutableListOf<String?>(PreviewBindings.mocks["tally"])
    repeat(3) {
      LiveEngine.dispatch("add", null)
      Snapshot.sendApplyNotifications()
      hostFrames()                 // only runs if the dispatch woke the host
      seen += PreviewBindings.mocks["tally"]
    }

    assertEquals(
      listOf<String?>("0 tallied", "1 tallied", "2 tallied", "3 tallied"),
      seen.toList(),
      "three actions must be visible in the preview, not just in the presenter",
    )

    composition.dispose()
    scope.coroutineContext[Job]?.cancel()
    null
  }

  /**
   * The resets the design intends must survive the fix: [LivePresenterHost]
   * keys the presenter on "screen:persona", and [LiveEngine.stop] ends the
   * session. Those boundaries SHOULD discard state; ordinary actions must not.
   */
  @Test
  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  fun intendedResetBoundariesStillReset(): kotlin.js.Promise<kotlin.js.JsAny?> = GlobalScope.promise {
    appPreviewEntry = TwoScreenPreview()

    val guestClock = BroadcastFrameClock()
    val ctx = kotlinx.coroutines.Dispatchers.Unconfined + Job() + guestClock
    val recomposer = Recomposer(ctx)
    val scope = CoroutineScope(ctx)
    scope.launch { recomposer.runRecomposeAndApplyChanges() }
    yield()

    LiveEngine.request.value = "home"
    val composition = Composition(UnitApplier(), recomposer)
    composition.setContent { LivePresenterHost() }
    Snapshot.sendApplyNotifications()
    yield()

    var lastWake = -1
    suspend fun hostFrames(frames: Int = 6) {
      if (HostWakeSignal.state.value == lastWake) return
      lastWake = HostWakeSignal.state.value
      repeat(frames) { guestClock.sendFrame(it.toLong()); yield() }
    }
    suspend fun pump() { HostWakeSignal.wake(); hostFrames() }

    pump()
    LiveEngine.dispatch("add", null); Snapshot.sendApplyNotifications(); hostFrames()
    LiveEngine.dispatch("add", null); Snapshot.sendApplyNotifications(); hostFrames()
    assertEquals("2 tallied", PreviewBindings.mocks["tally"], "state accumulates across actions")

    // 1. switching screen disposes the presenter
    LiveEngine.request.value = "other"
    Snapshot.sendApplyNotifications(); pump()
    assertEquals("other 0", PreviewBindings.mocks["label"], "a different screen starts fresh")
    LiveEngine.request.value = "home"
    Snapshot.sendApplyNotifications(); pump()
    assertEquals("0 tallied", PreviewBindings.mocks["tally"], "returning to a screen starts it fresh")

    // 2. stopping the session clears live values
    LiveEngine.dispatch("add", null); Snapshot.sendApplyNotifications(); hostFrames()
    assertEquals("1 tallied", PreviewBindings.mocks["tally"])
    LiveEngine.stop()
    assertEquals(null, PreviewBindings.mocks["tally"], "stop() removes the live values")
    // let the composition observe request == null, so the presenter is actually
    // disposed — in the editor frames keep running, here they are pumped.
    Snapshot.sendApplyNotifications(); pump()

    // 3. restarting is a fresh session
    LiveEngine.request.value = "home"
    Snapshot.sendApplyNotifications(); pump()
    assertEquals("0 tallied", PreviewBindings.mocks["tally"], "a restarted session starts fresh")

    composition.dispose()
    scope.coroutineContext[Job]?.cancel()
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

/** Minimal applier: this test asserts on applied VALUES, not on a widget tree. */
private class UnitApplier : androidx.compose.runtime.AbstractApplier<Unit>(Unit) {
  override fun insertBottomUp(index: Int, instance: Unit) {}
  override fun insertTopDown(index: Int, instance: Unit) {}
  override fun move(from: Int, to: Int, count: Int) {}
  override fun remove(index: Int, count: Int) {}
  override fun onClear() {}
}
