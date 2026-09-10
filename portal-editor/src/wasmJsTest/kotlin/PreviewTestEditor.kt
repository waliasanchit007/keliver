import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.render.strB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The editor's host/guest arrangement, close enough to schedule like the real one.
 *
 * An earlier version of this harness ran the frame pump as a plain coroutine on
 * the host clock and delivered a frame only when something had invalidated the
 * HOST composition. That is not what production does, and it suppressed frames
 * production delivers — see
 * `docs/superpowers/evidence/adopter-preview-route/U19-RECONCILIATION.md`.
 *
 * What production does, traced through Compose 1.8.2:
 *
 *  - `EditorShell`'s pump is a `LaunchedEffect` inside the host composition, so
 *    its `withFrameNanos` resolves to the host **Recomposer's** own
 *    `broadcastFrameClock` (`Recomposer.effectCoroutineContext` appends it).
 *  - A parked awaiter there makes `hasBroadcastFrameClockAwaiters` true, so
 *    `awaitWorkAvailable()` returns immediately and the recomposer asks its
 *    PARENT clock for a frame — every frame, with no composition invalidation
 *    anywhere.
 *  - On web the parent is `BaseComposeScene`'s
 *    `BroadcastFrameClock(onNewAwaiters = ::updateInvalidations)`, whose
 *    `invalidate` is `SkiaLayer::needRedraw` — a `requestAnimationFrame`.
 *
 * Measured in a real editor: **60 host frames per second, continuously**, before
 * Live is pressed, while Live is running with an idle presenter, and after Live
 * is stopped, with zero recompositions of the host content.
 *
 * So [pump] models a browser: it keeps delivering frames. The host recomposer
 * always has pending work here for the same reason it does in production.
 */
internal class PreviewTestEditor {
  private val hostJob = Job()
  private val guestJob = Job()

  /** Stands in for the browser's rAF clock — the recomposer's PARENT clock. */
  private val hostClock = BroadcastFrameClock()

  /** From production: whatever coupling the editor ships is what runs here. */
  private val guestClock = newGuestFrameClock()

  private val hostRecomposer = Recomposer(Dispatchers.Unconfined + hostJob + hostClock)
  private val guestRecomposer = Recomposer(Dispatchers.Unconfined + guestJob + guestClock)
  private val hostScope = CoroutineScope(Dispatchers.Unconfined + hostJob + hostClock)
  private val guestScope = CoroutineScope(Dispatchers.Unconfined + guestJob + guestClock)

  private var hostComposition: Composition? = null
  private var guestComposition: Composition? = null
  private var frameTime = 0L

  /** Host frames delivered so far — the harness's equivalent of `FrameProbe`. */
  internal var hostFrames = 0
    private set

  /** What the preview would draw, most recent last. */
  internal val rendered = mutableListOf<String>()

  /**
   * @param bindField the binding the recording composable resolves, the same way
   *   RenderNode resolves one — through [strB].
   */
  internal suspend fun start(bindField: String) {
    hostScope.launch { hostRecomposer.runRecomposeAndApplyChanges() }
    guestScope.launch { guestRecomposer.runRecomposeAndApplyChanges() }
    yield()

    val host = Composition(UnitApplier(), hostRecomposer)
    host.setContent {
      // Both of runPortalEditor's relevant pieces: the wake read, and the pump.
      HostWakeSignal.state.value
      LaunchedEffect(Unit) {
        while (true) {
          withFrameNanos { nanos -> guestClock.sendFrame(nanos) }
          hostFrames++
        }
      }
    }
    hostComposition = host

    val node = WidgetNode(type = "Text", props = mapOf("text" to dev.keliver.portal.Bind(bindField)))
    val guest = Composition(UnitApplier(), guestRecomposer)
    guest.setContent {
      LivePresenterHost()
      Rendered(node)
    }
    guestComposition = guest
    Snapshot.sendApplyNotifications()
    pump()
  }

  @Composable
  private fun Rendered(node: WidgetNode) {
    val text = node.strB("text")
    SideEffect { rendered += text }
  }

  /**
   * Run the browser for [frames] frames. Production delivers these whether or
   * not anything is pending, so this does too.
   */
  internal suspend fun pump(frames: Int = 12) {
    repeat(frames) {
      Snapshot.sendApplyNotifications()
      yield()
      hostClock.sendFrame(frameTime++)
      yield()
    }
  }

  /** True while the host keeps asking for frames — production's steady state. */
  internal val hostHasPendingWork: Boolean get() = hostRecomposer.hasPendingWork

  internal fun dispose() {
    guestComposition?.dispose()
    hostComposition?.dispose()
    guestJob.cancel()
    hostJob.cancel()
  }
}

/** Minimal applier: these tests assert on resolved bindings, not on a widget tree. */
internal class UnitApplier : androidx.compose.runtime.AbstractApplier<Unit>(Unit) {
  override fun insertBottomUp(index: Int, instance: Unit) {}
  override fun insertTopDown(index: Int, instance: Unit) {}
  override fun move(from: Int, to: Int, count: Int) {}
  override fun remove(index: Int, count: Int) {}
  override fun onClear() {}
}
