package dev.keliver.portal

/**
 * P2 preview fidelity for lists: resolve ONE mock row of a Repeat child
 * subtree. Item-scoped Binds ("note.title") become literal strings from
 * [mockOf] — pipe-separated per-row values ("First|Second"), clamped to the
 * last entry — falling back to a numbered "{note.title} N" placeholder.
 * Actions and screen binds pass through; a nested Repeat keeps its own scope.
 */
fun resolveItemRow(node: WidgetNode, itemVar: String, index: Int, mockOf: (String) -> String?): WidgetNode {
  if (node.type == "Repeat") return node
  val props = node.props.mapValues { (_, v) ->
    if (v is Bind && v.field.startsWith("$itemVar.")) {
      val rows = mockOf(v.field)?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }
      if (rows.isNullOrEmpty()) "{${v.field}} ${index + 1}" else rows[minOf(index, rows.size - 1)]
    } else {
      v
    }
  }
  return node.copy(props = props, children = node.children.map { resolveItemRow(it, itemVar, index, mockOf) })
}
