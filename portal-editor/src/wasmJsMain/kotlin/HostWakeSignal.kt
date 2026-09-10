import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf

/**
 * U19: the link that lets the GUEST composition ask the HOST for a frame.
 *
 * EditorShell runs the live-preview guest composition on its own
 * [androidx.compose.runtime.BroadcastFrameClock] and ticks it from the host's
 * frames, so guest recomposition and animations stay in step with the browser.
 * That works in one direction only. When a preview action wrote presenter
 * state, the GUEST recomposer was invalidated and then waited for a frame that
 * never came: an idle host schedules no frames, and nothing told it to. The
 * presenter's own state advanced correctly while the canvas kept showing the
 * first frame, until some unrelated edit happened to re-render the host.
 *
 * [wake] bumps a state that the host composition reads, so the host recomposes,
 * produces a frame, and the guest gets its tick.
 */
internal object HostWakeSignal {
  /** Read by the host composition; every [wake] invalidates that read. */
  internal val state = mutableStateOf(0)

  internal fun wake() {
    state.value = state.value + 1
  }
}

/**
 * The clock EditorShell runs the guest composition on.
 *
 * [BroadcastFrameClock] reports the moment it gains its first awaiter — which is
 * precisely the moment the guest has work it cannot do without a frame, whether
 * that work came from an action, from a coroutine a presenter started, or from a
 * guest animation. Waking the host there covers every guest invalidation, not
 * just the ones that arrive through [LiveEngine.dispatch]. It is edge-triggered:
 * once the frame is delivered the awaiter is gone, so an idle editor stays idle.
 */
internal fun newGuestFrameClock(): BroadcastFrameClock = BroadcastFrameClock { HostWakeSignal.wake() }
