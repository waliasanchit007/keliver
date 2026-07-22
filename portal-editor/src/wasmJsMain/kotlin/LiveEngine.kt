import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import dev.keliver.portal.render.PreviewBindings
import dev.keliver.portal.render.PreviewEnv
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.appFlowEntry
import dev.keliver.portal.render.appPreviewEntry

/**
 * P3-12: the editor side of live-presenter preview. When [request] names a
 * screen with a registered [ScreenPreview], [LivePresenterHost] composes the
 * REAL presenter (keyed on the screen, so switching disposes it and cancels
 * its effects/coroutines) and mirrors each frame's values into
 * [PreviewBindings.mocks] — the single transport every downstream consumer
 * (typed getters, Repeat rows, component expansion) already understands.
 * Canvas actions route to the live frame's dispatch, guarded so a presenter
 * exception surfaces in the console instead of killing the canvas.
 *
 * #13 F2 — FLOW mode: [flowRequest] names a flow instead. The composition is
 * keyed by FLOW (not screen), so the app's FlowScope (back-stack +
 * flow-lifetime state) survives navigation; each frame reports which screen
 * the flow shows NOW, and [onFlowScreen] lets the chrome follow by loading
 * that screen's tree. Nav actions dispatched from the canvas run the real
 * presenter, which calls the flow's navigate — the screen swap IS navigation.
 */
object LiveEngine {
  val request = mutableStateOf<String?>(null) // screen name; null = live off
  val flowRequest = mutableStateOf<String?>(null) // #13 F2: flow name; wins over request
  var flowStartOverride: String? = null // #13 F4: node to begin the walkthrough on (null = declared start)
  var frame: PreviewFrame? = null
  private var lastKeys: Set<String> = emptySet()
  var onError: (String) -> Unit = {}

  /** #13 F2: chrome hook — the flow's CURRENT screen each frame (follow + load its tree). */
  var onFlowScreen: (String) -> Unit = {}

  fun dispatch(action: String, arg: String?) {
    val f = frame ?: return
    runCatching { f.dispatch(action, arg) }
      .onFailure { onError("presenter error on '$action': ${it.message}") }
  }

  fun applyValues(values: Map<String, String>) {
    for ((k, v) in values) if (PreviewBindings.mocks[k] != v) PreviewBindings.mocks[k] = v
    (lastKeys - values.keys).forEach { PreviewBindings.mocks.remove(it) }
    lastKeys = values.keys
  }

  fun stop() {
    frame = null
    lastKeys = emptySet()
    request.value = null
    flowRequest.value = null
    flowStartOverride = null
  }
}

/** Composed inside the canvas composition, BEFORE RenderNode reads the mocks. */
@Composable
fun LivePresenterHost() {
  // #13 F2: flow mode first — the flow owns which screen shows.
  val flowName = LiveEngine.flowRequest.value
  if (flowName != null) {
    val fp = appFlowEntry?.flows?.get(flowName) ?: return
    val startAt = LiveEngine.flowStartOverride
    // Key by flow + start so choosing a new start node RE-inits the FlowScope.
    key("flow:$flowName:$startAt") {
      val f = fp.present(PreviewEnv(log = { portalLiveLog(it) }, flowStart = startAt))
      LiveEngine.frame = PreviewFrame(f.values, f.dispatch)
      SideEffect {
        LiveEngine.applyValues(f.values)
        LiveEngine.onFlowScreen(f.screen)
      }
    }
    return
  }
  val screen = LiveEngine.request.value ?: return
  val sp = appPreviewEntry?.screens?.get(screen) ?: return
  key(screen) {
    val frame = sp.present(PreviewEnv(log = { portalLiveLog(it) }))
    LiveEngine.frame = frame
    SideEffect { LiveEngine.applyValues(frame.values) }
  }
}
