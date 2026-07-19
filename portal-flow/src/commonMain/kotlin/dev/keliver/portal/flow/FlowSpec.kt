package dev.keliver.portal.flow

/**
 * Roadmap #13 (decision §0.1): a flow is declared ONCE as data — string route
 * keys mapping to target screens — and that single declaration serves both
 * sides: the app composes real navigation from it at runtime (preview + device),
 * and the relay PSI-parses the same source to derive the nav graph statically.
 *
 * A route [routes] key matches a nav action's LITERAL ARG (`b.open("SETTINGS")`)
 * or, for data-carrying navs whose arg is dynamic (`b.openNote(item.id)`), the
 * ACTION NAME itself. Typed sealed-interface routes (params) are the F4 layer
 * on top of this wire — see the design doc.
 */
data class FlowSpec(
  val name: String,
  /** The screen the flow begins on. */
  val start: String,
  /** route key (literal action arg, or action name) -> target screen. */
  val routes: Map<String, String>,
)

class FlowBuilder internal constructor() {
  internal val routes = LinkedHashMap<String, String>()

  /** Declare an edge: a nav action matching [key] navigates to screen [to]. */
  fun route(key: String, to: String) {
    routes[key] = to
  }
}

/**
 * Declare a flow. The portal recognizes exactly this shape in a flows/ file:
 *
 * ```kotlin
 * val FieldNotesFlow = flow("FieldNotes", start = "feed") {
 *   route("openNote", to = "detail")
 * }
 * ```
 */
fun flow(name: String, start: String, build: FlowBuilder.() -> Unit = {}): FlowSpec =
  FlowSpec(name, start, FlowBuilder().apply(build).routes.toMap())
