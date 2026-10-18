package dev.keliver.portal

/**
 * C3 transparent preview expansion (pure, so it is unit-testable without
 * Compose). Turns a component-instance node into a primitive tree by
 * substituting the instance's argument values into the definition body's
 * parameter binds/actions, applying signature defaults for omitted arguments,
 * and recursing through nested component calls with a cycle guard. K3: a
 * condition over a parameter (a conditional prop value, conditional children)
 * is decided against the instance's argument — or, when the argument is a
 * screen binding, left for the renderer to decide against the preview mocks. The result
 * contains NO component nodes, so the renderer never re-enters expansion.
 *
 * The SCREEN document itself is never expanded — only what the renderer asks to
 * draw for a specific instance.
 */
public sealed interface Expansion {
  /** Fully-primitive tree ready to render. */
  public data class Transparent(val tree: WidgetNode) : Expansion
  /** Render a labeled placeholder (opaque component or unknown type). */
  public data class Opaque(val name: String, val reason: String) : Expansion
  /** Render an error chip; [path] is the dependency cycle. */
  public data class Cycle(val path: List<String>) : Expansion
}

/** Expand a single component-instance [node] against [registry]. */
public fun expandForPreview(node: WidgetNode, registry: ComponentRegistry, stack: List<String> = emptyList()): Expansion {
  val name = node.type
  if (name in stack) return Expansion.Cycle(stack + name)
  val spec = registry.spec(name) ?: return Expansion.Opaque(name, "unknown component")
  val body = spec.body
  if (!spec.transparent || body == null) return Expansion.Opaque(name, spec.diagnostic ?: "opaque component")

  val args = node.props
  val defaults = spec.defaults
  val paramNames = spec.props.map { it.name }.toSet()
  val eventNames = spec.events.map { it.name }.toSet()
  val slot = spec.slots.singleOrNull()

  /**
   * K3: a parameter's value for this instance — its argument, else its
   * signature default, else (a required scalar previewed with no argument)
   * the editor's sample value. `first` = known at all.
   */
  fun argOf(param: String): Pair<Boolean, Any?> = when {
    param in args -> true to args[param]
    param in defaults -> true to defaults[param]
    else -> spec.props.firstOrNull { it.name == param }?.let { true to spec.sampleFor(it) } ?: (false to null)
  }

  /**
   * K3: a condition over a parameter, decided for this instance: a Boolean
   * when the argument is a value (a literal, a token, an event, null), or the
   * condition re-pointed at the screen binding the argument is (decided at
   * render time against the preview's mocks). A condition over anything else
   * is returned unchanged.
   */
  fun decide(c: Cond): Any {
    if (c.field !in paramNames && c.field !in eventNames) return c
    val (present, v) = argOf(c.field)
    return when (v) {
      is Bind -> c.copy(field = v.field)
      is Conditional -> c.holds(null, present = false) // a value chosen at render time: read as unknown
      else -> c.holds(v, present)
    }
  }

  fun resolveValue(v: Any?): Any? = when (v) {
    is Bind -> if ('.' in v.field) v // item.field pass-through
    else if (v.field in paramNames) (args[v.field] ?: defaults[v.field]) else v
    is Action -> if (v.name in eventNames) (args[v.name] ?: Action(v.name)) else v
    is Conditional -> when (val d = decide(v.cond)) {
      is Boolean -> resolveValue(if (d) v.whenTrue else v.whenFalse)
      else -> Conditional(d as Cond, resolveValue(v.whenTrue), resolveValue(v.whenFalse))
    }
    else -> v
  }

  /**
   * K3: a `Condition` over a parameter is decided here (its chosen branch's
   * nodes are spliced in place — `if` adds no layout of its own) or re-pointed
   * at the screen binding its argument is; null for any other node.
   */
  fun conditionBranch(n: WidgetNode, recurse: (WidgetNode) -> List<WidgetNode>): List<WidgetNode>? {
    if (n.type != "Condition") return null
    val c = condOf(n) ?: return null
    return when (val d = decide(c)) {
      is Boolean -> (if (d) n.thenChildren() else n.elseNode()?.children.orEmpty()).flatMap(recurse)
      else -> listOf(n.copy(props = n.props + ("field" to (d as Cond).field), children = n.children.flatMap(recurse)))
    }
  }

  fun source(n: WidgetNode, handle: Int): WidgetNode =
    n.copy(props = n.props + (COMPONENT_SOURCE_HANDLE_PROP to handle))

  fun substituteSlotContent(n: WidgetNode): WidgetNode {
    if (registry.isComponent(n.type)) {
      // Slot children execute at the component call site, not inside the
      // wrapper's definition. Carry the caller's definition stack, but do not
      // add the wrapper itself: Wrapper { Child() } is finite even when Child's
      // own body uses Wrapper.
      return when (val r = expandForPreview(n, registry, stack)) {
        is Expansion.Transparent -> source(r.tree, n.id)
        is Expansion.Opaque -> source(placeholder(r.name, r.reason), n.id)
        is Expansion.Cycle -> source(cycleChip(r.path), n.id)
      }
    }
    return source(n.copy(children = n.children.map(::substituteSlotContent)), n.id)
  }

  fun resolveDefinitionChildren(n: WidgetNode): List<WidgetNode> =
    conditionBranch(n, ::resolveDefinitionChildren) ?: listOf(
      n.copy(
        props = n.props.mapValues { resolveValue(it.value) },
        children = n.children.flatMap(::resolveDefinitionChildren),
      ),
    )

  fun resolveDefinitionTree(n: WidgetNode): WidgetNode = n.copy(
    props = n.props.mapValues { resolveValue(it.value) },
    children = n.children.flatMap(::resolveDefinitionChildren),
  )

  fun substitute(n: WidgetNode): List<WidgetNode> {
    if (n.type == "Slot" && (n.props["name"] as? String) == slot?.name) {
      return node.children.map(::substituteSlotContent)
    }
    conditionBranch(n, ::substitute)?.let { return it }
    // A nested component call inside the body: resolve ITS args against the
    // outer params, then expand it recursively (splicing the result).
    if (registry.isComponent(n.type)) {
      val resolved = resolveDefinitionTree(n)
      return listOf(when (val r = expandForPreview(resolved, registry, stack + name)) {
        is Expansion.Transparent -> r.tree
        is Expansion.Opaque -> placeholder(r.name, r.reason)
        is Expansion.Cycle -> cycleChip(r.path)
      })
    }
    return listOf(n.copy(
      props = n.props.mapValues { resolveValue(it.value) },
      children = n.children.flatMap { substitute(it) },
    ))
  }

  val expanded = substitute(body)
  return Expansion.Transparent(
    expanded.singleOrNull() ?: WidgetNode("Column", children = expanded),
  )
}

/** Placeholder node for an opaque component (renders as a labeled box). */
public fun placeholder(name: String, reason: String): WidgetNode =
  WidgetNode(
    "StyledBox",
    mapOf("colorArgb" to -1_579_033, "cornerRadiusDp" to 8, "paddingDp" to 12, "fillWidth" to true),
    listOf(
      WidgetNode("StyledText", mapOf("text" to "▢ $name", "fontSize" to 13, "bold" to true, "colorArgb" to -10_395_295)),
      WidgetNode("StyledText", mapOf("text" to reason, "fontSize" to 11, "colorArgb" to -6_842_473)),
    ),
  )

/** Error chip for a component cycle. */
public fun cycleChip(path: List<String>): WidgetNode =
  WidgetNode(
    "StyledBox",
    mapOf("colorArgb" to -74_910, "cornerRadiusDp" to 8, "paddingDp" to 12, "fillWidth" to true),
    listOf(
      WidgetNode("StyledText", mapOf("text" to "⚠ component cycle", "fontSize" to 13, "bold" to true, "colorArgb" to -3_407_872)),
      WidgetNode("StyledText", mapOf("text" to path.joinToString(" → "), "fontSize" to 11, "colorArgb" to -6_842_473)),
    ),
  )
