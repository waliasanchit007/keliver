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
 * contains NO component nodes (except opaque ones the editor draws compiled — see
 * the overload taking `compiled`), so the renderer never re-enters expansion for
 * a transparent component.
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
public fun expandForPreview(node: WidgetNode, registry: ComponentRegistry, stack: List<String> = emptyList()): Expansion =
  expandForPreview(node, registry, emptySet(), stack)

/**
 * As above. [compiled] names the components the editor can render compiled
 * (docs/COMPILED_COMPONENT_PREVIEW.md): a nested instance of one of them that
 * is OPAQUE stays in the expanded tree as a component node — its arguments
 * resolved against this instance's — so the renderer draws it compiled
 * instead of a placeholder. Only such nodes are left; every other component
 * call is expanded (or replaced by a placeholder / cycle chip) as before.
 */
public fun expandForPreview(
  node: WidgetNode,
  registry: ComponentRegistry,
  compiled: Set<String>,
  stack: List<String> = emptyList(),
): Expansion {
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
      // K4: a template reading a binding is known only at render time; otherwise its text.
      is Template -> if (referencedBinds(v).isEmpty() && v.parts.none { it is Conditional }) c.holds(templateText(v) { null }, present) else c.holds(null, present = false)
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
    // K4: a template's / a list's parameter references take the instance's
    // values (a required parameter with no argument previews its sample value).
    is Template -> Template(v.parts.map { p -> if (p is Bind && '.' !in p.field && p.field in paramNames) argOf(p.field).second else resolveValue(p) })
    is ListOf -> ListOf(v.kind, v.items.map { p -> if (p is Bind && '.' !in p.field && p.field in paramNames) argOf(p.field).second else resolveValue(p) })
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
      return when (val r = expandForPreview(n, registry, compiled, stack)) {
        is Expansion.Transparent -> source(r.tree, n.id)
        // Drawn compiled by the renderer; its own slot content is the caller's too.
        is Expansion.Opaque -> if (r.name in compiled) source(n.copy(children = n.children.map(::substituteSlotContent)), n.id)
        else source(placeholder(r.name, r.reason), n.id)
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
      return listOf(when (val r = expandForPreview(resolved, registry, compiled, stack + name)) {
        is Expansion.Transparent -> r.tree
        // Drawn compiled by the renderer: the call with its arguments resolved
        // against this instance's, its slot content substituted like any body node.
        is Expansion.Opaque -> if (r.name in compiled) resolved.copy(children = n.children.flatMap { substitute(it) }) else placeholder(r.name, r.reason)
        is Expansion.Cycle -> cycleChip(r.path)
      })
    }
    return listOf(n.copy(
      props = n.props.mapValues { resolveValue(it.value) },
      children = n.children.flatMap { substitute(it) },
    ))
  }

  val expanded = substitute(forwardInstanceModifiers(body, node, spec))
  return Expansion.Transparent(
    expanded.singleOrNull() ?: WidgetNode("Column", children = expanded),
  )
}

/**
 * K5: when the body's root forwards the component's `modifier` parameter, the
 * instance's modifiers (`MyCard(modifier = Modifier.flex(1.0))`) go on that
 * root FIRST, then the body's own chain — the order `modifier.padding(8)`
 * builds on a device. Only for a component that declares `modifier: Modifier`.
 * A modifier both sides set previews once, with the instance's value (a device
 * applies both; the tree holds one value per modifier).
 */
private fun forwardInstanceModifiers(body: WidgetNode, instance: WidgetNode, spec: ComponentSpec): WidgetNode {
  if (body.props[COMPONENT_FORWARDS_MODIFIER_PROP] != true) return body
  val own = body.props - COMPONENT_FORWARDS_MODIFIER_PROP
  if (!spec.acceptsModifier) return body.copy(props = own)
  fun modName(key: String) = key.removePrefix("mod.").substringBefore('.')
  val fromInstance = instance.props.filterKeys { it.startsWith("mod.") }
  val instanceNames = fromInstance.keys.mapTo(HashSet(), ::modName)
  val props = LinkedHashMap<String, Any?>()
  own.forEach { (k, v) -> if (!k.startsWith("mod.")) props[k] = v }
  props.putAll(fromInstance)
  own.forEach { (k, v) -> if (k.startsWith("mod.") && modName(k) !in instanceNames) props[k] = v }
  return body.copy(props = props)
}

/**
 * Placeholder node for an opaque component (renders as a labeled box).
 *
 * K5: a StyledBox stacks its children, so the name and the reason used to be
 * drawn on top of each other. They sit in a Column now: the name on one line,
 * the reason below it (two lines at most), each ellipsized rather than
 * overflowing the box.
 */
public fun placeholder(name: String, reason: String): WidgetNode =
  labeledBox(-1_579_033, "▢ $name", -10_395_295, reason)

/** Error chip for a component cycle. */
public fun cycleChip(path: List<String>): WidgetNode =
  labeledBox(-74_910, "⚠ component cycle", -3_407_872, path.joinToString(" → "))

private fun labeledBox(background: Int, title: String, titleColor: Int, detail: String): WidgetNode =
  WidgetNode(
    "StyledBox",
    mapOf("colorArgb" to background, "cornerRadiusDp" to 8, "paddingDp" to 12, "fillWidth" to true),
    listOf(
      WidgetNode(
        "Column",
        mapOf("width" to 1),
        listOf(
          WidgetNode(
            "StyledText",
            mapOf("text" to title, "fontSize" to 13, "bold" to true, "colorArgb" to titleColor, "maxLines" to 1, "overflow" to 1),
          ),
          WidgetNode(
            "StyledText",
            mapOf("text" to detail, "fontSize" to 11, "colorArgb" to -6_842_473, "maxLines" to 2, "overflow" to 1),
          ),
        ),
      ),
    ),
  )
