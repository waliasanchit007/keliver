package dev.keliver.portal

/**
 * P3 bindings: a prop VALUE can be a literal, a [Bind] to a named data field,
 * or (for event props) an [Action] naming the handler. The screen's contract
 * is DERIVED from the tree — no separate contract store to drift.
 */
public data class Bind(val field: String)

/** [arg]: null -> `{ b.name() }`; "it" -> event payload; "item.field" -> item-scoped data (P2). */
public data class Action(val name: String, val arg: String? = null)

/** The derived contract of a screen tree. */
public data class ScreenContract(
  /** field name -> the editor kind of the prop it's bound at (first bind site wins). */
  val fields: Map<String, PropKind>,
  val actions: List<String>,
  /** action name -> its single param's Kotlin type (P2 single-arg actions). */
  val actionParams: Map<String, String> = emptyMap(),
) {
  val isEmpty: Boolean get() = fields.isEmpty() && actions.isEmpty()
}

/** The exported Kotlin type for a bound field of editor kind [kind]. */
public fun kotlinTypeOf(kind: PropKind): String = when (kind) {
  PropKind.Text -> "String"
  PropKind.Int, PropKind.Color -> "Int"
  PropKind.Bool -> "Boolean"
  PropKind.Double -> "Double"
  PropKind.IntList -> "List<Int>"
  PropKind.FloatList -> "List<Float>"
  PropKind.StringList -> "List<String>"
}

/**
 * Walks the tree collecting Bind fields (typed via the generated catalog) and
 * Action names. [components] (C1) types binds/actions consumed by project
 * component instances from the component signature; primitive-only by default.
 */
public fun collectContract(tree: WidgetNode, components: ComponentRegistry = EmptyComponentRegistry): ScreenContract {
  val fields = LinkedHashMap<String, PropKind>()
  val actions = LinkedHashSet<String>()
  val actionParams = LinkedHashMap<String, String>()
  fun kindOf(nodeType: String, propKey: String): PropKind? {
    if (propKey.startsWith("mod.")) {
      val mod = propKey.removePrefix("mod.").substringBefore('.')
      val prop = propKey.removePrefix("mod.$mod.").takeIf { it != propKey }
      return modifierSpecs.firstOrNull { it.name == mod }?.props?.firstOrNull { it.name == prop }?.kind
    }
    widgetSpec(nodeType)?.props?.firstOrNull { it.name == propKey }?.kind?.let { return it }
    // C1: component instance prop → the component's declared param kind.
    return components.spec(nodeType)?.props?.firstOrNull { it.name == propKey }?.kind
  }
  /** For an Action on a component instance event, the handler's param type. */
  fun componentEventParamType(nodeType: String, propKey: String): String? =
    components.spec(nodeType)?.events?.firstOrNull { it.name == propKey }?.paramType
  // K3: a Repeat's items field is a List (the exporter types it); an emptiness
  // test reads a List as well as a String, so it never makes that field a String.
  val listFields = HashSet<String>()
  fun lists(n: WidgetNode) {
    if (n.type == "Repeat") (n.props["items"] as? String)?.takeIf { it.isNotBlank() }?.let(listFields::add)
    n.children.forEach(::lists)
  }
  lists(tree)
  // K4: a field read only by a template is a String (any value prints into one,
  // so a hand-declared type is kept); it is typed after every other site.
  val templateFields = LinkedHashSet<String>()
  fun walk(n: WidgetNode) {
    for ((key, prop) in n.props) {
      // K3: a conditional value contributes its condition's field and each branch.
      val values = if (prop is Conditional) {
        val c = prop.cond
        val readsList = c.test != CondTest.Bool && c.field in listFields
        if ('.' !in c.field && c.field !in fields && !readsList) {
          fields[c.field] = if (c.test == CondTest.Bool) PropKind.Bool else PropKind.Text
        }
        listOf(prop.whenTrue, prop.whenFalse)
      } else {
        listOf(prop)
      }
      for (value in values) when (value) {
        // Item-scoped binds ("item.label") belong to the Repeat's item interface
        // (collectLogicFields), NOT the screen contract — a dotted name here
        // would emit invalid Kotlin (`val item.label: String`).
        is Bind -> kindOf(n.type, key)?.let { k ->
          if ('.' !in value.field && value.field !in fields) fields[value.field] = k
        }
        is Template -> referencedBinds(value).forEach { b -> if ('.' !in b.field) templateFields += b.field }
        // K4: a list element bound to a field is one element of the list's kind.
        is ListOf -> value.items.filterIsInstance<Bind>().forEach { b ->
          val k = if (value.kind == "ls") PropKind.Text else PropKind.Int
          if ('.' !in b.field && b.field !in fields) fields[b.field] = k
        }
        is Action -> {
          actions += value.name
          when {
            value.arg == "it" -> actionParams[value.name] =
              eventParamType["${n.type}.$key"] ?: componentEventParamType(n.type, key) ?: "String"
            value.arg != null -> actionParams[value.name] = literalArgType(value.arg)
          }
        }
        else -> {}
      }
    }
    n.children.forEach(::walk)
  }
  walk(tree)
  templateFields.forEach { f -> if (f !in fields) fields[f] = PropKind.Text }
  return ScreenContract(fields, actions.toList(), actionParams)
}

/**
 * Every preview-mock key the current tree can read on its first composition.
 *
 * Live presenter values are applied after composition. Pre-seeding these keys
 * makes reads of newly introduced component/item bindings observable, so the
 * first real frame always schedules the RenderNode recomposition which consumes
 * those values. Repeat item keys use the same list namespace as [resolveItemRow].
 */
public fun collectPreviewBindingKeys(tree: WidgetNode): Set<String> {
  val keys = LinkedHashSet<String>()

  fun walk(node: WidgetNode, itemScope: Pair<String, String>? = null) {
    val scope = if (node.type == "Repeat") {
      val items = node.props["items"] as? String
      val item = node.props["item"] as? String
      if (!items.isNullOrBlank()) keys += items
      if (!items.isNullOrBlank() && !item.isNullOrBlank()) items to item else itemScope
    } else {
      itemScope
    }
    if (node.type == "Condition") {
      (node.props["field"] as? String)?.takeIf { it.isNotBlank() }?.let(keys::add)
    }
    // K3: a conditional value reads its condition's field and its branches' binds.
    val values = node.props.values.flatMap { v ->
      if (v is Conditional) listOf(Bind(v.cond.field), v.whenTrue, v.whenFalse) else listOf(v)
    }
    // K4: a template's and a list's references read their fields too.
    (values.filterIsInstance<Bind>() + values.flatMap(::referencedBinds)).forEach { bind ->
      val (items, item) = scope ?: ("" to "")
      if (items.isNotEmpty() && bind.field.startsWith("$item.")) {
        keys += "$items.${bind.field}"
      } else {
        keys += bind.field
      }
    }
    node.children.forEach { walk(it, scope) }
  }

  walk(tree)
  return keys
}

/** P1-4: the handler param type implied by an Action arg's SOURCE text —
 * `"ROUTE"` → String, `3` → Int, `2.5` → Double, `true` → Boolean;
 * item-scoped data (`item.id`) stays String. */
private fun literalArgType(arg: String): String = when {
  arg.startsWith('"') -> "String"
  arg == "true" || arg == "false" -> "Boolean"
  Regex("^-?\\d+$").matches(arg) -> "Int"
  Regex("^-?\\d*\\.\\d+$").matches(arg) -> "Double"
  else -> "String"
}
