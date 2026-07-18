import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import dev.keliver.portal.render.PreviewBindings
import dev.keliver.portal.render.PreviewEnv
import dev.keliver.portal.render.PreviewFrame
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
 */
object LiveEngine {
  val request = mutableStateOf<String?>(null) // screen name; null = live off
  var frame: PreviewFrame? = null
  private var lastKeys: Set<String> = emptySet()
  var onError: (String) -> Unit = {}

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
  }
}

/** Composed inside the canvas composition, BEFORE RenderNode reads the mocks. */
@Composable
fun LivePresenterHost() {
  val screen = LiveEngine.request.value ?: return
  val sp = appPreviewEntry?.screens?.get(screen) ?: return
  key(screen) {
    val frame = sp.present(PreviewEnv(log = { portalLiveLog(it) }))
    LiveEngine.frame = frame
    SideEffect { LiveEngine.applyValues(frame.values) }
  }
}
