package dev.keliver.portal.presenterspike

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A presenter in the M8 Live-Presenter shape: pure logic producing Bindings
 * state, crossing the wasm↔JS boundary as serialized JSON (the preview
 * renders it via the interpreter's mock-injection path; actions come back the
 * same way). No keliver deps — this tier runs logic, not widgets.
 */
@Serializable
data class CheckoutState(
  val title: String,
  val count: Int,
  val total: String,
)

class CounterPresenter {
  private var count = 0

  fun state(): CheckoutState = CheckoutState(
    title = "Cart",
    count = count,
    total = "$" + (count * 4999) / 100.0,
  )

  /** The action reverse-channel: named action in, new state out. */
  fun onAction(action: String): CheckoutState {
    when (action) {
      "add" -> count++
      "remove" -> if (count > 0) count--
    }
    return state()
  }
}

private val json = Json

/** JS-boundary surface: state out / actions in as JSON strings. */
fun presenterStateJson(p: CounterPresenter): String = json.encodeToString(p.state())
fun presenterActionJson(p: CounterPresenter, action: String): String = json.encodeToString(p.onAction(action))

fun main() {
  // Executable entry for the dist-loading path (M8 loads this bundle beside
  // the preview and wires window callbacks); tests exercise the same surface.
  println("presenter-spike ready: " + presenterStateJson(CounterPresenter()))
}
