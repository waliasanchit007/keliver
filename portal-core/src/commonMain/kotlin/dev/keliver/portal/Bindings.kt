package dev.keliver.portal

/**
 * P3 bindings: a prop VALUE can be a literal, a [Bind] to a named data field,
 * or (for event props) an [Action] naming the handler. The screen's contract
 * is DERIVED from the tree — no separate contract store to drift.
 */
data class Bind(val field: String)

/** [arg]: null -> `{ b.name() }`; "it" -> event payload; "item.field" -> item-scoped data (P2). */
data class Action(val name: String, val arg: String? = null)

/** The derived contract of a screen tree. */
data class ScreenContract(
  /** field name -> the editor kind of the prop it's bound at (first bind site wins). */
  val fields: Map<String, PropKind>,
  val actions: List<String>,
  /** action name -> its single param's Kotlin type (P2 single-arg actions). */
  val actionParams: Map<String, String> = emptyMap(),
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
  PropKind.StringList -> "List<String>"
}

/** Walks the tree collecting Bind fields (typed via the generated catalog) and Action names. */
fun collectContract(tree: WidgetNode): ScreenContract {
  val fields = LinkedHashMap<String, PropKind>()
  val actions = LinkedHashSet<String>()
  val actionParams = LinkedHashMap<String, String>()
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
        is Action -> {
          actions += value.name
          when {
            value.arg == "it" -> actionParams[value.name] = eventParamType["${n.type}.$key"] ?: "String"
            value.arg != null -> actionParams[value.name] = "String" // item-scoped data (ids etc.)
          }
        }
        else -> {}
      }
    }
    n.children.forEach(::walk)
  }
  walk(tree)
  return ScreenContract(fields, actions.toList(), actionParams)
}
