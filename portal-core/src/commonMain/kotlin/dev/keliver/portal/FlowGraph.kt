package dev.keliver.portal

/**
 * #13 F1 — derived nav graph. One edge per (screen, route key): the screen
 * contains a nav Action whose LITERAL ARG equals the key (`open("SETTINGS")`)
 * or whose NAME equals the key (`openNote(item.id)` — dynamic data arg, the
 * action name is the stable label). Literal-arg match wins when both apply.
 *
 * Takes primitives (routes map + trees), not FlowSpec, so portal-core needs no
 * dependency on :portal-flow — the relay marries the two.
 */
data class FlowEdge(val from: String, val key: String, val to: String)

fun deriveFlowEdges(routes: Map<String, String>, screens: Map<String, WidgetNode>): List<FlowEdge> {
  val edges = LinkedHashSet<FlowEdge>()
  fun walk(screen: String, n: WidgetNode) {
    for (v in n.props.values) {
      if (v !is Action) continue
      val literal = v.arg
        ?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
        ?.removeSurrounding("\"")
      val key = when {
        literal != null && literal in routes -> literal
        v.name in routes -> v.name
        else -> null
      }
      if (key != null) edges += FlowEdge(screen, key, routes.getValue(key))
    }
    n.children.forEach { walk(screen, it) }
  }
  for ((name, tree) in screens) walk(name, tree)
  return edges.toList()
}
