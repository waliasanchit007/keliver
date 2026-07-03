package dev.keliver.portal

/**
 * P3 bindings: a prop VALUE can be a literal, a [Bind] to a named data field,
 * or (for event props) an [Action] naming the handler. The screen's contract
 * is DERIVED from the tree — no separate contract store to drift.
 */
data class Bind(val field: String)

data class Action(val name: String)

/** The derived contract of a screen tree. */
data class ScreenContract(
  /** field name -> the editor kind of the prop it's bound at (first bind site wins). */
  val fields: Map<String, PropKind>,
  val actions: List<String>,
) {
  val isEmpty: Boolean get() = fields.isEmpty() && actions.isEmpty()
}

/** The exported Kotlin type for a bound field of editor kind [kind]. */
fun kotlinTypeOf(kind: PropKind): String = when (kind) {
  PropKind.Text -> "String"
  PropKind.Int, PropKind.Color -> "Int"
  PropKind.Bool -> "Boolean"
  PropKind.Double -> "Double"
  PropKind.IntList -> "List<Int>"
  PropKind.FloatList -> "List<Float>"
}

/** Walks the tree collecting Bind fields (typed via the generated catalog) and Action names. */
fun collectContract(tree: WidgetNode): ScreenContract {
  val fields = LinkedHashMap<String, PropKind>()
  val actions = LinkedHashSet<String>()
  fun kindOf(nodeType: String, propKey: String): PropKind? {
    return if (propKey.startsWith("mod.")) {
      val mod = propKey.removePrefix("mod.").substringBefore('.')
      val prop = propKey.removePrefix("mod.$mod.").takeIf { it != propKey }
      modifierSpecs.firstOrNull { it.name == mod }?.props?.firstOrNull { it.name == prop }?.kind
    } else {
      widgetSpec(nodeType)?.props?.firstOrNull { it.name == propKey }?.kind
    }
  }
  fun walk(n: WidgetNode) {
    for ((key, value) in n.props) {
      when (value) {
        is Bind -> kindOf(n.type, key)?.let { k -> if (value.field !in fields) fields[value.field] = k }
        is Action -> actions += value.name
        else -> {}
      }
    }
    n.children.forEach(::walk)
  }
  walk(tree)
  return ScreenContract(fields, actions.toList())
}
