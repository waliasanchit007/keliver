package dev.keliver.portal.ingest

import dev.keliver.portal.ComponentEventSpec
import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.ComponentSlotSpec
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.RESERVED_COMPONENT_NAMES
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.widgetSpec
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * The Portal Compose recognizer (design §3/§5): turns a screen .kt into a
 * parsed DocNode tree (TEMP handles — the Reconciler maps them onto the live
 * document) + the Contract from the sibling Bindings interface.
 * INVARIANT: anything outside the grammar lands in RawCode VERBATIM.
 */
data class Recognized(
  val root: DocNode.Widget,
  val contract: Contract,
  /** null when the file has no recognizable screen function. */
  val screenName: String?,
  /** M4: temp-handle -> the PSI expression it came from (write-back targeting). Transient. */
  val psiByHandle: Map<Long, KtExpression> = emptyMap(),
  /** M4: the KtFile the PSI refs belong to — mutate then read [KtFile.getText]. Transient. */
  val file: KtFile? = null,
  /** M4: the screen function's Bindings interface, if present, for contract write-back. */
  val bindingsInterface: KtClass? = null,
)

/**
 * Name-resolution context for a body. Screens resolve `b.field`/`b.action()`;
 * component definitions resolve bare parameter names (`title` -> Bind,
 * `onClick` -> Action). Shared by both recognition modes.
 */
internal sealed interface NameCtx {
  data class Screen(val bindingsParam: String?) : NameCtx
  data class Component(
    val valueParams: Set<String>,
    val eventParams: Set<String>,
    val slotParams: Set<String>,
  ) : NameCtx
}

/** C1: a recognized component definition — its signature spec + body tree. */
data class RecognizedComponent(
  val spec: ComponentSpec,
  val root: DocNode.Widget?,
  val functionName: String,
  val psiByHandle: Map<Long, KtExpression> = emptyMap(),
  val file: KtFile? = null,
)

object Recognizer {
  /**
   * Recognize a SCREEN. [components] lets calls to known project components
   * (e.g. `MenuRow(...)`) become Widget nodes instead of RawCode; default empty
   * keeps every existing caller primitive-only.
   */
  fun recognize(
    fileName: String,
    source: String,
    components: ComponentRegistry = EmptyComponentRegistry,
  ): Recognized? {
    val file = PsiEnv.parse(fileName, source)
    val fn = file.declarations.filterIsInstance<KtNamedFunction>()
      .firstOrNull { f -> f.annotationEntries.any { it.shortName?.asString() == "Composable" } }
      ?: return null
    val bindingsParam = fn.valueParameters.firstOrNull()?.name // "b" by convention
    val body = fn.bodyExpression as? KtBlockExpression ?: return null
    val walk = Walker(NameCtx.Screen(bindingsParam), components)
    val root = walk.rootOf(body)

    val ifaceClass = file.declarations.filterIsInstance<KtClass>()
      .firstOrNull { it.isInterface() && it.name?.endsWith("Bindings") == true }
    val contract = ifaceClass?.let { iface ->
      Contract(
        fields = iface.declarations.filterIsInstance<KtProperty>()
          .associate { (it.name ?: "?") to (it.typeReference?.text ?: "String") },
        actions = iface.declarations.filterIsInstance<KtNamedFunction>().mapNotNull { it.name },
        actionParams = iface.declarations.filterIsInstance<KtNamedFunction>()
          .mapNotNull { f ->
            val p = f.valueParameters.firstOrNull()?.typeReference?.text ?: return@mapNotNull null
            (f.name ?: return@mapNotNull null) to p
          }.toMap(),
      )
    } ?: Contract()

    return Recognized(root, contract, fn.name, walk.psiByHandle, file, ifaceClass)
  }

  /**
   * C1: recognize a COMPONENT definition file. Derives the [ComponentSpec] from
   * the @Composable signature (String/Int/Boolean/Double params -> props;
   * `() -> Unit`/`(T) -> Unit` params -> events; literal defaults parsed) and
   * the body tree (params act as binds/actions). A signature-valid component
   * whose body contains unsupported code registers as OPAQUE (still a real
   * device composable). [components] carries already-known components so nested
   * component calls in the body recognize.
   */
  fun recognizeComponent(
    fileName: String,
    source: String,
    components: ComponentRegistry = EmptyComponentRegistry,
  ): RecognizedComponent? {
    val file = PsiEnv.parse(fileName, source)
    val pkg = file.packageFqName.asString().takeIf { it.isNotEmpty() }
    val fn = file.declarations.filterIsInstance<KtNamedFunction>()
      .firstOrNull { f -> f.annotationEntries.any { it.shortName?.asString() == "Composable" } }
      ?: return null
    val name = fn.name ?: return null
    if (name in RESERVED_COMPONENT_NAMES || widgetSpec(name) != null) {
      return RecognizedComponent(
        ComponentSpec(name, emptyList(), emptyList(), emptyMap(),
          diagnostic = "name '$name' collides with a reserved/primitive widget", transparent = false, packageName = pkg),
        null, name, emptyMap(), file,
      )
    }

    val props = mutableListOf<PropSpec>()
    val events = mutableListOf<ComponentEventSpec>()
    val paramTypes = LinkedHashMap<String, String>()
    val defaults = LinkedHashMap<String, Any?>()
    val valueParams = LinkedHashSet<String>()
    val eventParams = LinkedHashSet<String>()
    val slots = mutableListOf<ComponentSlotSpec>()
    val optionalSlots = mutableListOf<String>()
    for (p in fn.valueParameters) {
      val pName = p.name ?: continue
      val typeRef = p.typeReference
      val typeText = typeRef?.text ?: continue
      paramTypes[pName] = typeText
      p.defaultValue?.let { defaults[pName] = parseLiteral(it.text.trim())?.let { l -> litValue(l) } }
      val fnType = typeRef.typeElement as? KtFunctionType
      if (fnType != null) {
        if (typeText.contains("@Composable")) {
          if (p.defaultValue == null) slots += ComponentSlotSpec(pName)
          else optionalSlots += pName
          continue
        }
        val ret = fnType.returnTypeReference?.text
        if (ret == "Unit" || ret == null) {
          val argType = fnType.parameters.firstOrNull()?.typeReference?.text
          events += ComponentEventSpec(pName, argType, required = p.defaultValue == null)
          eventParams += pName
        }
        continue
      }
      val kind = scalarKind(typeText)
      if (kind != null) {
        props += PropSpec(pName, kind, pName.replaceFirstChar { it.uppercaseChar() })
        valueParams += pName
      }
    }

    if (optionalSlots.isNotEmpty()) {
      return RecognizedComponent(
        ComponentSpec(name, props, events, paramTypes, defaults = defaults,
          diagnostic = "optional content slots are not supported (found: ${optionalSlots.joinToString()})",
          transparent = false, packageName = pkg),
        null, name, emptyMap(), file,
      )
    }

    if (slots.size > 1) {
      return RecognizedComponent(
        ComponentSpec(name, props, events, paramTypes, slots, defaults,
          diagnostic = "multiple content slots are not supported (found: ${slots.joinToString { it.name }})",
          transparent = false, packageName = pkg),
        null, name, emptyMap(), file,
      )
    }

    val body = fn.bodyExpression as? KtBlockExpression
    if (body == null) {
      return RecognizedComponent(
        ComponentSpec(name, props, events, paramTypes, slots, defaults,
          diagnostic = "component has no block body", transparent = false, packageName = pkg),
        null, name, emptyMap(), file,
      )
    }
    val walk = Walker(NameCtx.Component(valueParams, eventParams, slots.mapTo(linkedSetOf()) { it.name }), components)
    val root = walk.rootOf(body)
    val hasRaw = containsRawCode(root)
    val slotCalls = countSlotCalls(root)
    val invalidSlotUse = slots.singleOrNull()?.let { slot ->
      when {
        slotCalls != 1 -> "content slot '${slot.name}' must be invoked exactly once (found $slotCalls)"
        root.type == "Slot" -> "content slot '${slot.name}' must be nested inside a grammar container"
        else -> null
      }
    }
    val deps = collectComponentDeps(root, components.names())
    val spec = ComponentSpec(
      name = name,
      props = props,
      events = events,
      paramTypes = paramTypes,
      slots = slots,
      defaults = defaults,
      body = if (hasRaw || invalidSlotUse != null) null else docNodeToWidget(root),
      transparent = !hasRaw && invalidSlotUse == null,
      dependencies = deps,
      diagnostic = invalidSlotUse ?: if (hasRaw) "body contains code outside the portal grammar (opaque)" else null,
      packageName = pkg,
    )
    return RecognizedComponent(spec, root, name, walk.psiByHandle, file)
  }

  private fun containsRawCode(n: DocNode): Boolean = when (n) {
    is DocNode.RawCode -> true
    is DocNode.Widget -> n.children.any { containsRawCode(it) }
  }

  private fun countSlotCalls(n: DocNode): Int = when (n) {
    is DocNode.RawCode -> 0
    is DocNode.Widget -> (if (n.type == "Slot") 1 else 0) + n.children.sumOf(::countSlotCalls)
  }

  private fun collectComponentDeps(n: DocNode, names: Set<String>): Set<String> {
    val out = mutableSetOf<String>()
    fun walk(x: DocNode) {
      if (x is DocNode.Widget) {
        if (x.type in names) out += x.type
        x.children.forEach { walk(it) }
      }
    }
    walk(n)
    return out
  }

  private fun docNodeToWidget(n: DocNode.Widget): WidgetNode =
    UiDocument("_", n, Contract(), 0, 0).toWidgetTree()

  private fun scalarKind(typeText: String): PropKind? = when (typeText.removeSuffix("?").trim()) {
    "String" -> PropKind.Text
    "Int" -> PropKind.Int
    "Boolean" -> PropKind.Bool
    "Double" -> PropKind.Double
    else -> null
  }

  private fun litValue(l: PropValue.Lit): Any? = l.s ?: l.i ?: l.d ?: l.b

  /**
   * Shared body walker for both recognition modes. Holds the temp-handle
   * counter + psiByHandle map; [ctx] drives bind/action resolution.
   */
  internal class Walker(private val ctx: NameCtx, private val components: ComponentRegistry) {
    private val componentNames = components.names()
    private var temp = -1L
    val psiByHandle = mutableMapOf<Long, KtExpression>()
    private fun nextTemp() = Handle(temp--)
    private fun track(h: Handle, e: KtExpression): Handle { psiByHandle[h.v] = e; return h }

    fun rootOf(body: KtBlockExpression): DocNode.Widget {
      val statements = body.statements.map { statementToNode(it, emptySet()) }
      return statements.singleOrNull() as? DocNode.Widget
        ?: DocNode.Widget(nextTemp(), "Column", children = statements)
    }

    private fun statementToNode(expr: KtExpression, itemScope: Set<String>): DocNode {
      recognizeCondition(expr, ctx)?.let { (field, thenStmts) ->
        return DocNode.Widget(track(nextTemp(), expr), "Condition", mapOf("field" to PropValue.Lit("s", s = field)),
          children = thenStmts.map { statementToNode(it, itemScope) })
      }
      recognizeRepeat(expr, ctx)?.let { (items, itemVar, bodyStmts) ->
        return DocNode.Widget(track(nextTemp(), expr), "Repeat",
          mapOf("items" to PropValue.Lit("s", s = items), "item" to PropValue.Lit("s", s = itemVar)),
          children = bodyStmts.map { statementToNode(it, itemScope + itemVar) })
      }

      val call = expr as? KtCallExpression
      val type = call?.calleeExpression?.text
      if (call != null && ctx is NameCtx.Component && type in ctx.slotParams &&
        call.valueArguments.isEmpty() && call.lambdaArguments.isEmpty()
      ) {
        return DocNode.Widget(
          track(nextTemp(), expr),
          "Slot",
          mapOf("name" to PropValue.Lit("s", s = type)),
        )
      }
      // Primitive widget OR a known project component → editable Widget node.
      val known = type != null && (widgetSpec(type) != null || type in componentNames)
      if (call == null || !known) return rawNode(expr, ::nextTemp).let { it.copy(handle = track(it.handle, expr)) }

      val component = type?.let(components::spec)
      if (component != null) {
        val hasTrailingContent = call.lambdaArguments.isNotEmpty()
        val slot = component.slots.singleOrNull()
        if ((hasTrailingContent && slot == null) || (!hasTrailingContent && slot?.required == true)) {
          return rawNode(expr, ::nextTemp).let { it.copy(handle = track(it.handle, expr)) }
        }
      }

      val props = mutableMapOf<String, PropValue>()
      val modifiers = mutableMapOf<String, PropValue>()
      for (arg in call.valueArguments) {
        if (arg is KtLambdaArgument) continue
        val name = arg.getArgumentName()?.asName?.asString() ?: return rawNode(expr, ::nextTemp).let { it.copy(handle = track(it.handle, expr)) }
        val ve = arg.getArgumentExpression() ?: return rawNode(expr, ::nextTemp).let { it.copy(handle = track(it.handle, expr)) }
        if (name == "modifier") {
          val mods = parseModifierChain(ve.text)
            ?: return rawNode(expr, ::nextTemp).let { it.copy(handle = track(it.handle, expr)) }
          modifiers += mods
          continue
        }
        val value = parseValue(ve, ctx, itemScope, componentNames)
          ?: return rawNode(expr, ::nextTemp).let { it.copy(handle = track(it.handle, expr)) }
        props[name] = value
      }
      val children = call.lambdaArguments.firstOrNull()
        ?.getLambdaExpression()?.bodyExpression?.statements.orEmpty()
        .map { statementToNode(it, itemScope) }
      return DocNode.Widget(track(nextTemp(), expr), type, props, modifiers, children)
    }
  }

  /** Extracts the bound field from `b.field` (screen) or a bare Boolean param (component). */
  private fun recognizeCondition(expr: KtExpression, ctx: NameCtx): Pair<String, List<KtExpression>>? {
    val ifExpr = expr as? KtIfExpression ?: return null
    if (ifExpr.`else` != null) return null
    val cond = ifExpr.condition?.text?.trim() ?: return null
    val field = when (ctx) {
      is NameCtx.Screen -> ctx.bindingsParam?.let {
        Regex("^${Regex.escape(it)}\\.([A-Za-z_][A-Za-z0-9_]*)$").find(cond)?.groupValues?.get(1)
      }
      is NameCtx.Component -> cond.takeIf { it in ctx.valueParams }
    } ?: return null
    val block = ifExpr.then as? KtBlockExpression ?: return null
    return field to block.statements
  }

  /** Extracts (items, itemVar, body) from `b.items.forEach {..}` or a bare `items.forEach {..}`. */
  private fun recognizeRepeat(expr: KtExpression, ctx: NameCtx): Triple<String, String, List<KtExpression>>? {
    val dot = expr as? KtDotQualifiedExpression ?: return null
    val recv = dot.receiverExpression.text.trim()
    val items = when (ctx) {
      is NameCtx.Screen -> ctx.bindingsParam?.let {
        Regex("^${Regex.escape(it)}\\.([A-Za-z_][A-Za-z0-9_]*)$").find(recv)?.groupValues?.get(1)
      }
      is NameCtx.Component -> recv.takeIf { it in ctx.valueParams }
    } ?: return null
    val call = dot.selectorExpression as? KtCallExpression ?: return null
    if (call.calleeExpression?.text != "forEach") return null
    val lambda = call.lambdaArguments.firstOrNull()?.getLambdaExpression() as? KtLambdaExpression ?: return null
    val itemVar = lambda.valueParameters.firstOrNull()?.name ?: "it"
    val body = lambda.bodyExpression?.statements ?: return null
    return Triple(items, itemVar, body)
  }

  private fun rawNode(expr: KtExpression, nextTemp: () -> Handle): DocNode.RawCode {
    val text = expr.text
    val hint = when {
      text.startsWith("if") || text.startsWith("when") -> "condition"
      ".forEach" in text || text.startsWith("for ") || text.startsWith("repeat") -> "loop"
      "remember" in text || "LaunchedEffect" in text -> "effect"
      else -> null
    }
    return DocNode.RawCode(nextTemp(), text, hint)
  }

  /** Literal / bind / action argument expressions. Null = not in the grammar. */
  private fun parseValue(
    expr: KtExpression,
    ctx: NameCtx,
    itemScope: Set<String> = emptySet(),
    componentNames: Set<String> = emptySet(),
  ): PropValue? {
    val t = expr.text.trim()
    // ── Component mode: bare parameter names are the binds/actions. ──
    if (ctx is NameCtx.Component) {
      if (t in ctx.valueParams) return PropValue.Bind(t)
      // `onClick = onClick` (event passed through) / `{ onClick() }` / `{ onClick(it) }`.
      if (t in ctx.eventParams) return PropValue.Action(t)
      Regex("^\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\(\\)\\s*}$").find(t)?.let {
        if (it.groupValues[1] in ctx.eventParams) return PropValue.Action(it.groupValues[1])
      }
      Regex("^\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\(it\\)\\s*}$").find(t)?.let {
        if (it.groupValues[1] in ctx.eventParams) return PropValue.Action(it.groupValues[1], arg = "it")
      }
      Regex("^\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\((.+)\\)\\s*}$", RegexOption.DOT_MATCHES_ALL).find(t)?.let { m ->
        val raw = m.groupValues[2].trim()
        if (m.groupValues[1] in ctx.eventParams && parseLiteral(raw) != null) {
          return PropValue.Action(m.groupValues[1], arg = raw)
        }
      }
      // item.subfield inside a Repeat, then literals — shared tail below.
      Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)$").find(t)?.let { m ->
        if (m.groupValues[1] in itemScope) return PropValue.Bind("${m.groupValues[1]}.${m.groupValues[2]}")
      }
      return parseLiteral(t)
    }
    val bindingsParam = (ctx as NameCtx.Screen).bindingsParam
    // b.field / b::action / { b.action() }
    if (bindingsParam != null) {
      Regex("^${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)$").find(t)
        ?.let { return PropValue.Bind(it.groupValues[1]) }
      Regex("^${Regex.escape(bindingsParam)}::([A-Za-z_][A-Za-z0-9_]*)$").find(t)
        ?.let { return PropValue.Action(it.groupValues[1]) }
      Regex("^\\{\\s*${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)\\(\\)\\s*}$").find(t)
        ?.let { return PropValue.Action(it.groupValues[1]) }
      // P2: single-arg actions — { b.name(it) } (event payload) and
      // { b.name(item.field) } (item-scoped data, e.g. a row id).
      Regex("^\\{\\s*${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)\\(it\\)\\s*}$").find(t)
        ?.let { return PropValue.Action(it.groupValues[1], arg = "it") }
      Regex("^\\{\\s*${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)\\(([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\)\\s*}$").find(t)
        ?.let { m ->
          if (m.groupValues[2] in itemScope) {
            return PropValue.Action(m.groupValues[1], arg = "${m.groupValues[2]}.${m.groupValues[3]}")
          }
        }
      // P1-4: LITERAL action args — { b.open("ROUTE") } / { b.pick(3) } — the
      // commonest idiom in ported native code. The arg keeps its SOURCE text so
      // export/write-back stay byte-identical. Also matches the `{ _ -> ... }`
      // form the exporter emits when the event has an ignored payload param.
      Regex("^\\{\\s*(?:_\\s*->\\s*)?${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)\\((.+)\\)\\s*}$", RegexOption.DOT_MATCHES_ALL).find(t)
        ?.let { m ->
          val raw = m.groupValues[2].trim()
          if (parseLiteral(raw) != null) return PropValue.Action(m.groupValues[1], arg = raw)
        }
    }
    // P1-B: item.subfield inside a Repeat → an item-scoped Bind ("item.subfield").
    Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)$").find(t)?.let { m ->
      if (m.groupValues[1] in itemScope) return PropValue.Bind("${m.groupValues[1]}.${m.groupValues[2]}")
    }
    return parseLiteral(t)
  }

  internal fun parseLiteral(t: String): PropValue.Lit? = when {
    t == "true" -> PropValue.Lit("b", b = true)
    t == "false" -> PropValue.Lit("b", b = false)
    Regex("^-?\\d+$").matches(t) -> PropValue.Lit("i", i = t.toInt())
    Regex("^-?\\d*\\.\\d+$").matches(t) -> PropValue.Lit("d", d = t.toDouble())
    t.length >= 2 && t.startsWith('"') && t.endsWith('"') ->
      PropValue.Lit("s", s = t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\"))
    t.startsWith("Dp(") && t.endsWith(")") ->
      t.removePrefix("Dp(").removeSuffix(")").toDoubleOrNull()?.let { PropValue.Lit("d", d = it) }
    t.startsWith("listOf(") && t.endsWith(")") -> parseList(t.removePrefix("listOf(").removeSuffix(")"))
    t.startsWith("Constraint.") -> PropValue.Lit("i", i = if (t.endsWith("Fill")) 1 else 0)
    t.startsWith("CrossAxisAlignment.") -> enumLit(t, listOf("Start", "Center", "End", "Stretch"))
    t.startsWith("MainAxisAlignment.") -> enumLit(t, listOf("Start", "Center", "End", "SpaceBetween", "SpaceAround", "SpaceEvenly"))
    t.startsWith("Overflow.") -> PropValue.Lit("i", i = if (t.endsWith("Scroll")) 1 else 0)
    else -> null
  }

  private fun enumLit(t: String, names: List<String>): PropValue.Lit? =
    names.indexOf(t.substringAfterLast('.')).takeIf { it >= 0 }?.let { PropValue.Lit("i", i = it) }

  private fun parseList(inner: String): PropValue.Lit? {
    val items = inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (items.isEmpty()) return PropValue.Lit("li", li = emptyList())
    return when {
      items.all { it.length >= 2 && it.startsWith('"') && it.endsWith('"') } -> {
        // STRING_LIST ("ls"): listOf("A", "B") — commas inside strings aren't in
        // the exporter's output grammar, so the simple split is round-trip safe.
        PropValue.Lit("ls", ls = items.map { it.substring(1, it.length - 1).replace("\\\"", "\"").replace("\\\\", "\\") })
      }
      items.all { it.endsWith("f") } -> {
        val floats = items.map { it.removeSuffix("f").toFloatOrNull() ?: return null }
        PropValue.Lit("lf", lf = floats)
      }
      else -> {
        val ints = items.map { it.toIntOrNull() ?: return null }
        PropValue.Lit("li", li = ints)
      }
    }
  }

  /** "Modifier.padding(8).animateContentSize()" → {"Padding.allDp": 8, "AnimateContentSize": true}. */
  internal fun parseModifierChain(text: String): Map<String, PropValue>? {
    val t = text.trim()
    if (t == "Modifier") return emptyMap()
    if (!t.startsWith("Modifier.")) return null
    val out = mutableMapOf<String, PropValue>()
    // split top-level chain segments: name(args)
    for (m in Regex("([A-Za-z_][A-Za-z0-9_]*)\\(([^()]*)\\)").findAll(t.removePrefix("Modifier."))) {
      val ext = m.groupValues[1]
      val argsText = m.groupValues[2].trim()
      val spec = modifierSpecs.firstOrNull { it.name.replaceFirstChar { c -> c.lowercase() } == ext } ?: return null
      if (spec.props.isEmpty()) {
        out[spec.name] = PropValue.Lit("b", b = true)
      } else {
        val args = if (argsText.isEmpty()) emptyList() else splitTopLevel(argsText)
        if (args.size != spec.props.size) return null
        spec.props.forEachIndexed { i, p ->
          val lit = parseLiteral(args[i].trim()) ?: return null
          out["${spec.name}.${p.name}"] = coerce(lit, p.kind)
        }
      }
    }
    return out
  }

  /** Exported modifier args are positional primitives; coerce to catalog kind. */
  private fun coerce(lit: PropValue.Lit, kind: PropKind): PropValue.Lit = when {
    kind == PropKind.Double && lit.tag == "i" -> PropValue.Lit("d", d = lit.i?.toDouble())
    else -> lit
  }

  private fun splitTopLevel(s: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    s.forEachIndexed { i, c ->
      when (c) {
        '(', '[' -> depth++
        ')', ']' -> depth--
        ',' -> if (depth == 0) { parts += s.substring(start, i); start = i + 1 }
      }
    }
    parts += s.substring(start)
    return parts
  }
}
