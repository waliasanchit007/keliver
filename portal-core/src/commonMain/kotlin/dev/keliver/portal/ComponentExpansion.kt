package dev.keliver.portal

/**
 * C3 transparent preview expansion (pure, so it is unit-testable without
 * Compose). Turns a component-instance node into a primitive tree by
 * substituting the instance's argument values into the definition body's
 * parameter binds/actions, applying signature defaults for omitted arguments,
 * and recursing through nested component calls with a cycle guard. The result
 * contains NO component nodes, so the renderer never re-enters expansion.
 *
 * The SCREEN document itself is never expanded — only what the renderer asks to
 * draw for a specific instance.
 */
sealed interface Expansion {
  /** Fully-primitive tree ready to render. */
  data class Transparent(val tree: WidgetNode) : Expansion
  /** Render a labeled placeholder (opaque component or unknown type). */
  data class Opaque(val name: String, val reason: String) : Expansion
  /** Render an error chip; [path] is the dependency cycle. */
  data class Cycle(val path: List<String>) : Expansion
}

/** Expand a single component-instance [node] against [registry]. */
fun expandForPreview(node: WidgetNode, registry: ComponentRegistry, stack: List<String> = emptyList()): Expansion {
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

  fun resolveValue(v: Any?): Any? = when (v) {
    is Bind -> if ('.' in v.field) v // item.field pass-through
    else if (v.field in paramNames) (args[v.field] ?: defaults[v.field]) else v
    is Action -> if (v.name in eventNames) (args[v.name] ?: Action(v.name)) else v
    else -> v
  }

  fun source(n: WidgetNode, handle: Int): WidgetNode =
    n.copy(props = n.props + (COMPONENT_SOURCE_HANDLE_PROP to handle))

  fun substituteSlotContent(n: WidgetNode): WidgetNode {
    if (registry.isComponent(n.type)) {
      return when (val r = expandForPreview(n, registry, stack + name)) {
        is Expansion.Transparent -> source(r.tree, n.id)
        is Expansion.Opaque -> source(placeholder(r.name, r.reason), n.id)
        is Expansion.Cycle -> source(cycleChip(r.path), n.id)
      }
    }
    return source(n.copy(children = n.children.map(::substituteSlotContent)), n.id)
  }

  fun resolveDefinitionTree(n: WidgetNode): WidgetNode = n.copy(
    props = n.props.mapValues { resolveValue(it.value) },
    children = n.children.map(::resolveDefinitionTree),
  )

  fun substitute(n: WidgetNode): List<WidgetNode> {
    if (n.type == "Slot" && (n.props["name"] as? String) == slot?.name) {
      return node.children.map(::substituteSlotContent)
    }
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
fun placeholder(name: String, reason: String): WidgetNode =
  WidgetNode(
    "StyledBox",
    mapOf("colorArgb" to -1_579_033, "cornerRadiusDp" to 8, "paddingDp" to 12, "fillWidth" to true),
    listOf(
      WidgetNode("StyledText", mapOf("text" to "▢ $name", "fontSize" to 13, "bold" to true, "colorArgb" to -10_395_295)),
      WidgetNode("StyledText", mapOf("text" to reason, "fontSize" to 11, "colorArgb" to -6_842_473)),
    ),
  )

/** Error chip for a component cycle. */
fun cycleChip(path: List<String>): WidgetNode =
  WidgetNode(
    "StyledBox",
    mapOf("colorArgb" to -74_910, "cornerRadiusDp" to 8, "paddingDp" to 12, "fillWidth" to true),
    listOf(
      WidgetNode("StyledText", mapOf("text" to "⚠ component cycle", "fontSize" to 13, "bold" to true, "colorArgb" to -3_407_872)),
      WidgetNode("StyledText", mapOf("text" to path.joinToString(" → "), "fontSize" to 11, "colorArgb" to -6_842_473)),
    ),
  )
