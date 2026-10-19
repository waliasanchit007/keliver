package dev.keliver.portal.ingest

import dev.keliver.portal.COMPONENT_FORWARDS_MODIFIER_PROP
import dev.keliver.portal.Cond
import dev.keliver.portal.CondTest
import dev.keliver.portal.ComponentEventSpec
import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.ComponentSlotSpec
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.ELSE_TYPE
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.RESERVED_COMPONENT_NAMES
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.isModifierType
import dev.keliver.portal.listElementType
import dev.keliver.portal.listKindOf
import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.marginProps
import dev.keliver.portal.propSourceType
import dev.keliver.portal.tokenFits
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
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtParameter

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
  /** K2: design-token resolution in this file (shadowing honoured), for write-back. Transient. */
  internal val tokenLookup: TokenLookup? = null,
  /** K3: condition reading in this file's screen context, for write-back. Transient. */
  internal val condParser: CondParser? = null,
  /** The screen function's bindings parameter (`b` by convention) — write-back spells a new condition with it. */
  internal val bindingsParam: String? = null,
  /** Why each RawCode node is not grammar, in source order — the screen's diagnostics. */
  val rawReasons: List<String> = emptyList(),
)

/**
 * Name-resolution context for a body. Screens resolve `b.field`/`b.action()`;
 * component definitions resolve bare parameter names (`title` -> Bind,
 * `onClick` -> Action). Shared by both recognition modes.
 */
internal sealed interface NameCtx {
  data class Screen(
    val bindingsParam: String?,
    /** K4: each field the screen's Bindings interface declares → its declared type text. */
    val fieldTypes: Map<String, String> = emptyMap(),
    /**
     * K4: the type of a field [fieldTypes] does not list — null (an undeclared
     * field; contract write-back adds it) when the interface is read whole,
     * [UNKNOWN_TYPE] when it is not in the file or inherits from elsewhere.
     */
    val undeclared: String? = null,
    /**
     * K4: why a field [fieldTypes] does not list has a type that cannot be read
     * (no Bindings parameter, its interface not in the file or extending one
     * declared elsewhere) — a diagnostic, and the contract's
     * `unlistedFieldsUnknown`. Null = the interface is read whole.
     */
    val unknownWhy: String? = null,
  ) : NameCtx
  data class Component(
    val valueParams: Set<String>,
    val eventParams: Set<String>,
    val slotParams: Set<String>,
    /** K3: parameters whose declared type is nullable (a `== null` / `!= null` condition reads them). */
    val nullableParams: Set<String> = emptySet(),
    /**
     * K5: the signature declares `modifier: Modifier`, so the body's root may
     * forward it (`Column(modifier = modifier)`), as a component built to take
     * a caller's modifiers does.
     */
    val modifierParam: Boolean = false,
    /** K4: each parameter → its declared type text (a template reads only some types). */
    val paramTypes: Map<String, String> = emptyMap(),
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
   * keeps every existing caller primitive-only. [tokens] (K2) lets references
   * to the app's design-token constants (`C.Ink`) be values instead of RawCode.
   */
  fun recognize(
    fileName: String,
    source: String,
    components: ComponentRegistry = EmptyComponentRegistry,
    tokens: TokenTable = TokenTable.EMPTY,
  ): Recognized? {
    val file = PsiEnv.parse(fileName, LineEndings.toLf(source))
    val fn = screenFunction(file) ?: return null
    val bindingsParam = fn.valueParameters.firstOrNull()?.name // "b" by convention
    val body = fn.bodyExpression as? KtBlockExpression ?: return null
    val lookup = tokens.lookupFor(file, fileName, TokenTable.shadowedNames(fn))
    // K4: the contract is the interface the screen TAKES (its parameter's type, `?`
    // stripped), declared in this file — never another one the file declares first.
    val ifaceClass = screenInterface(fn, file)
    // A template reads a field only of a type it can read — in that interface, with
    // its same-file supertypes; a field it does not list is unknown when the interface
    // is not here or extends one declared elsewhere ([unknownWhy] says which).
    val decls = typeDecls(file)
    val ifaceTypes = ifaceClass?.let { memberTypes(it, decls) }
    val unknownWhy = unlistedFieldsWhy(fn, file, ifaceClass, ifaceTypes)
    val screenCtx = NameCtx.Screen(
      bindingsParam,
      fieldTypes = ifaceTypes?.types.orEmpty(),
      undeclared = if (unknownWhy != null) UNKNOWN_TYPE else null,
      unknownWhy = unknownWhy,
    )
    val walk = Walker(screenCtx, components, lookup, decls)
    val root = walk.rootOf(body)
    val contract = ifaceClass?.let { iface ->
      Contract(
        // A property without a declared type is not silently a String: its type is unknown.
        fields = ifaceTypes?.types.orEmpty(),
        actions = iface.declarations.filterIsInstance<KtNamedFunction>().mapNotNull { it.name },
        actionParams = iface.declarations.filterIsInstance<KtNamedFunction>()
          .mapNotNull { f ->
            val p = f.valueParameters.firstOrNull()?.typeReference?.text ?: return@mapNotNull null
            (f.name ?: return@mapNotNull null) to p
          }.toMap(),
        unlistedFieldsUnknown = unknownWhy,
      )
    } ?: Contract(unlistedFieldsUnknown = unknownWhy)

    return Recognized(
      root, contract, fn.name, walk.psiByHandle, file, ifaceClass, lookup,
      CondParser { parseCond(it, NameCtx.Screen(bindingsParam)) }, bindingsParam, walk.rawReasons.toList(),
    )
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
    tokens: TokenTable = TokenTable.EMPTY,
  ): RecognizedComponent? {
    val file = PsiEnv.parse(fileName, LineEndings.toLf(source))
    val pkg = file.packageFqName.asString().takeIf { it.isNotEmpty() }
    val fn = file.declarations.filterIsInstance<KtNamedFunction>()
      .firstOrNull { f -> f.annotationEntries.any { it.shortName?.asString() == "Composable" } }
      ?: return null
    val lookup = tokens.lookupFor(file, fileName, TokenTable.shadowedNames(fn))
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
    val nullableSlots = mutableListOf<String>()
    val nullableParams = LinkedHashSet<String>()
    for (p in fn.valueParameters) {
      val pName = p.name ?: continue
      val typeRef = p.typeReference
      val typeText = typeRef?.text ?: continue
      paramTypes[pName] = typeText
      // A default that references a design token (`color: Int = C.Ink`) previews as its value.
      p.defaultValue?.let {
        defaults[pName] = (parseLiteral(it.text.trim()) ?: lookup.lookup(it.text)?.value)?.let { l -> litValue(l) }
      }
      // K3: a nullable function type — `(() -> Unit)?` — is an optional event;
      // a nullable content slot is reported, never silently dropped.
      val nullable = typeRef.typeElement is KtNullableType
      if (nullable) nullableParams += pName
      val fnType = typeRef.typeElement as? KtFunctionType
        ?: (typeRef.typeElement as? KtNullableType)?.innerType as? KtFunctionType
      if (fnType != null) {
        if (typeText.contains("@Composable")) {
          when {
            nullable -> nullableSlots += pName
            p.defaultValue == null -> slots += ComponentSlotSpec(pName)
            else -> optionalSlots += pName
          }
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

    if (nullableSlots.isNotEmpty()) {
      return RecognizedComponent(
        ComponentSpec(name, props, events, paramTypes, defaults = defaults,
          diagnostic = "nullable content slots are not supported (found: ${nullableSlots.joinToString()}): " +
            "declare a required `@Composable () -> Unit` slot, or keep the component opaque",
          transparent = false, packageName = pkg),
        null, name, emptyMap(), file,
      )
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
    val acceptsModifier = paramTypes["modifier"]?.let(::isModifierType) == true
    val walk = Walker(
      NameCtx.Component(
        valueParams, eventParams, slots.mapTo(linkedSetOf()) { it.name }, nullableParams,
        modifierParam = acceptsModifier,
        paramTypes = paramTypes,
      ),
      components,
      lookup,
      typeDecls(file),
    )
    val root = walk.rootOf(body)
    val hasRaw = containsRawCode(root)
    val slotCalls = countSlotCalls(root)
    // K3: when code outside the grammar hides the slot call, say what that code
    // is — "invoked exactly once (found 0)" would blame the slot instead.
    val invalidSlotUse = if (hasRaw) null else slots.singleOrNull()?.let { slot ->
      when {
        slotCalls != 1 -> "content slot '${slot.name}' must be invoked exactly once (found $slotCalls)"
        root.type == "Slot" -> "content slot '${slot.name}' must be nested inside a grammar container"
        else -> null
      }
    }
    val rawDiagnostic = "body contains code outside the portal grammar (opaque)" +
      (walk.rawReasons.firstOrNull()?.let { ": $it" } ?: "")
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
      diagnostic = invalidSlotUse ?: if (hasRaw) rawDiagnostic else null,
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
  internal class Walker(
    private val ctx: NameCtx,
    private val components: ComponentRegistry,
    private val tokens: TokenLookup = TokenLookup { null },
    /** K4: the file's top-level classes and interfaces — a Repeat's item type is read here. */
    private val typeDecls: Map<String, KtClass> = emptyMap(),
  ) {
    private val componentNames = components.names()
    /**
     * K4: each item variable in scope → its item type's members (null when the
     * type is not declared in this file: its members' types are not read).
     */
    private val itemTypes = HashMap<String, MemberTypes?>()
    /** K4: why the last template was refused for a reference's type — the argument's RawCode reason. */
    private var templateWhy: String? = null
    private var temp = -1L
    val psiByHandle = mutableMapOf<Long, KtExpression>()
    /** K3: why each RawCode node is not grammar, in source order (component diagnostics). */
    val rawReasons = mutableListOf<String>()
    private fun nextTemp() = Handle(temp--)
    private fun track(h: Handle, e: KtExpression): Handle { psiByHandle[h.v] = e; return h }

    /** [expr] verbatim as RawCode, recording [reason]. */
    private fun raw(expr: KtExpression, reason: String): DocNode.RawCode {
      rawReasons += reason
      return rawNode(expr, ::nextTemp).let { it.copy(handle = track(it.handle, expr)) }
    }

    fun rootOf(body: KtBlockExpression): DocNode.Widget {
      // Top-level statements have no layout parent: no scope receiver in source.
      val single = body.statements.size == 1
      val statements = body.statements.map { statementToNode(it, emptySet(), null, isRoot = single) }
      return statements.singleOrNull() as? DocNode.Widget
        ?: DocNode.Widget(nextTemp(), "Column", children = statements)
    }

    /**
     * [parentType] is the widget whose content lambda holds [expr] — what a
     * scoped layout modifier (`Modifier.flex(…)`) resolves against. `if` and
     * `forEach` add no receiver, so Condition/Repeat pass it straight through.
     */
    private fun statementToNode(
      expr: KtExpression,
      itemScope: Set<String>,
      parentType: String?,
      isRoot: Boolean = false,
    ): DocNode {
      recognizeCondition(expr, ctx)?.let { (cond, thenStmts, elseBlock) ->
        val handle = track(nextTemp(), expr)
        val props = buildMap {
          put("field", PropValue.Lit("s", s = cond.field))
          cond.test.wire?.let { put("test", PropValue.Lit("s", s = it)) }
          if (cond.negate) put("negate", PropValue.Lit("b", b = true))
        }
        val thenNodes = thenStmts.map { statementToNode(it, itemScope, parentType) }
        // K3: `else { … }` is the Condition's last child, an Else node holding its statements.
        val elseNode = elseBlock?.let { block ->
          DocNode.Widget(track(nextTemp(), block), ELSE_TYPE,
            children = block.statements.map { statementToNode(it, itemScope, parentType) })
        }
        return DocNode.Widget(handle, "Condition", props, children = thenNodes + listOfNotNull(elseNode))
      }
      recognizeRepeat(expr, ctx)?.let { (items, itemVar, bodyStmts) ->
        // K4: the item's members are typed by the list's element type, if it is declared here.
        val had = itemVar in itemTypes
        val outer = itemTypes[itemVar]
        itemTypes[itemVar] = itemMemberTypes(items)
        val children = try {
          bodyStmts.map { statementToNode(it, itemScope + itemVar, parentType) }
        } finally {
          if (had) itemTypes[itemVar] = outer else itemTypes.remove(itemVar)
        }
        return DocNode.Widget(track(nextTemp(), expr), "Repeat",
          mapOf("items" to PropValue.Lit("s", s = items), "item" to PropValue.Lit("s", s = itemVar)),
          children = children)
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
      if (call == null || !known) return raw(expr, rawReason(expr, call, type))

      val component = type?.let(components::spec)
      if (component != null) {
        val hasTrailingContent = call.lambdaArguments.isNotEmpty()
        val slot = component.slots.singleOrNull()
        if (hasTrailingContent && slot == null) return raw(expr, "`$type` takes no content, but is called with a trailing lambda")
        if (!hasTrailingContent && slot?.required == true) return raw(expr, "`$type` needs its content slot '${slot.name}' as a trailing lambda")
      }

      val props = mutableMapOf<String, PropValue>()
      val modifiers = mutableMapOf<String, PropValue>()
      // K1: Margin-valued widget props (Row/Column/Box `margin`) ride as four sides.
      val margins = widgetSpec(type!!)?.let { marginProps(it.props) }.orEmpty()
      val conds = CondParser { parseCond(it, ctx, itemScope) }
      for (arg in call.valueArguments) {
        if (arg is KtLambdaArgument) continue
        val name = arg.getArgumentName()?.asName?.asString()
          ?: return raw(expr, "`$type(…)` has a positional argument `${arg.text.oneLine()}`; grammar arguments are named")
        val ve = arg.getArgumentExpression() ?: return raw(expr, "`$type(…)` has an empty argument `$name`")
        templateWhy = null
        fun offending() = raw(
          expr,
          templateWhy?.let { "`$type(…)` argument `$name`: $it" }
            ?: "`$type(…)` argument `$name = ${ve.text.oneLine()}` is outside the portal grammar",
        )
        if (name == "modifier") {
          // K5: a component forwarding its own `modifier` parameter to its root.
          // The root is marked so preview expansion puts the instance's
          // modifiers first, then the body's own chain (`modifier.padding(8)`).
          if (ctx is NameCtx.Component && ctx.modifierParam) {
            val own = forwardedModifier(ve.text, conds)
            if (own != null && isRoot) {
              props[COMPONENT_FORWARDS_MODIFIER_PROP] = PropValue.Lit("b", b = true)
              modifiers += own
              continue
            }
            // Forwarding anywhere else is opaque (correct) — say that is why.
            if (own != null) {
              return raw(
                expr,
                "`$type(modifier = ${ve.text.oneLine()})`: a component's `modifier` parameter is forwarded only at " +
                  "the body's single root call (not under `if`/`forEach`, in a nested call, or beside other statements)",
              )
            }
          }
          val mods = parseModifierChain(ve.text, parentType, tokens, conds) ?: return offending()
          modifiers += mods
          continue
        }
        if (name in margins) {
          val sides = MarginSyntax.parseValues(ve.text, conds) ?: return offending()
          dev.keliver.portal.MARGIN_SIDES.forEachIndexed { i, side -> props["$name.$side"] = sides[i] }
          continue
        }
        val value = parseValue(ve, ctx, itemScope, componentNames)
          ?: tokenArg(type, name, ve.text)
          ?: templateArg(type, name, ve, itemScope)
          ?: listArg(type, name, ve, itemScope)
          ?: conditionalArg(type, name, ve, itemScope)
          ?: return offending()
        props[name] = value
      }
      val children = call.lambdaArguments.firstOrNull()
        ?.getLambdaExpression()?.bodyExpression?.statements.orEmpty()
        .map { statementToNode(it, itemScope, type) }
      return DocNode.Widget(track(nextTemp(), expr), type, props, modifiers, children)
    }

    /**
     * K5: `modifier` (nothing of its own) or `modifier.ext(…)…` (the body's own
     * chain after the caller's). The root has no layout parent in the body, so
     * a scoped modifier in that chain is not grammar. Null = neither.
     */
    private fun forwardedModifier(text: String, conds: CondParser): Map<String, PropValue>? {
      val t = text.trim()
      if (t == "modifier") return emptyMap()
      val rest = Regex("^modifier\\s*(\\..*)$", RegexOption.DOT_MATCHES_ALL).find(t)?.groupValues?.get(1) ?: return null
      // K3: the own chain takes a conditional value as a `Modifier.…` chain does.
      return parseModifierChain("Modifier$rest", null, tokens, conds)
    }

    /**
     * K2: a design-token reference as the value of [type]'s [name] — only
     * where it resolves and its kind is the argument's plain literal kind;
     * otherwise (unknown, private elsewhere, cyclic, a kind mismatch) null,
     * so the call stays RawCode rather than becoming silently wrong.
     */
    private fun tokenArg(type: String, name: String, text: String): PropValue.Token? {
      val token = tokens.lookup(text) ?: return null
      return token.takeIf { tokenFits(propSourceType(type, name, components), litValue(it.value)) }
    }

    /**
     * K4: a string template as the value of [type]'s [name] — only on a String
     * prop or parameter, and only when every embedded expression is a grammar
     * reference ([templateRef]); otherwise null (the call stays RawCode).
     */
    private fun templateArg(type: String, name: String, ve: KtExpression, itemScope: Set<String>): PropValue.Template? {
      if (propSourceType(type, name, components) != "String") return null
      return TemplateSyntax.parse(ve) { templateRef(it, itemScope) }
    }

    /**
     * K4: a reference inside a template — a binding (a screen field `b.x`, an
     * item field `item.x`, a component parameter) or a design token of a
     * literal kind. Anything else (`${a + b}`, `${x.uppercase()}`, a literal)
     * is not grammar.
     */
    private fun templateRef(e: KtExpression, itemScope: Set<String>): PropValue? =
      when (val v = parseValue(e, ctx, itemScope, componentNames)) {
        // K4: only a type the device (Kotlin/JS) and the preview print alike — never a
        // Double/Float (`1` vs `1.0`) or a nullable value (`null` vs nothing).
        is PropValue.Bind -> {
          val type = declaredType(v)
          if (dev.keliver.portal.templateRefTypeFits(type)) v
          else null.also { templateWhy = refusedRefWhy(e.text.trim(), type!!) }
        }
        null -> tokens.lookup(e.text)
        else -> null
      }

    /** K4: why a screen field spelled [ref] (`b.x`) has no readable type, when the screen's interface does not list it. */
    private fun screenUnknownWhy(ref: String): String? {
      val screen = ctx as? NameCtx.Screen ?: return null
      if (ref.substringBefore('.') != screen.bindingsParam || ref.substringAfter('.') in screen.fieldTypes) return null
      return screen.unknownWhy
    }

    /** K4: why a template does not read the reference spelled [ref], declared [type]. */
    private fun refusedRefWhy(ref: String, type: String): String = when {
      type == UNKNOWN_TYPE -> "a template does not read `$ref`: " +
        (screenUnknownWhy(ref) ?: "its type is not declared where ingest reads it (a property without a type, or inherited from a type in another file)") +
        " — only a String, Int, Long or Boolean prints alike on the device and in the preview"
      else -> "a template does not read `$ref`, declared $type — only a String, Int, Long or Boolean prints alike on the device and in the preview"
    }

    /**
     * K4: the declared type of a binding — a screen field's in the Bindings
     * interface, a component parameter's, an item field's in its item type;
     * [UNKNOWN_TYPE] when it cannot be read; null when undeclared (an item
     * type not declared in this file, a field contract write-back adds).
     */
    private fun declaredType(b: PropValue.Bind): String? = when {
      '.' in b.field -> itemTypes[b.field.substringBefore('.')]?.typeOf(b.field.substringAfter('.'))
      ctx is NameCtx.Screen -> ctx.fieldTypes[b.field] ?: ctx.undeclared
      ctx is NameCtx.Component -> ctx.paramTypes[b.field]
      else -> null
    }

    /** K4: the members of the element type of the list field [items], when that type is declared in this file. */
    private fun itemMemberTypes(items: String): MemberTypes? {
      val listType = when (ctx) {
        is NameCtx.Screen -> ctx.fieldTypes[items]
        is NameCtx.Component -> ctx.paramTypes[items]
      }
      val cls = listElementName(listType)?.let(typeDecls::get) ?: return null
      return memberTypes(cls, typeDecls)
    }

    /**
     * K4: `listOf(…)` as the value of a catalogued widget's `List<Int>` /
     * `List<String>` prop — each element a literal or a design token of the
     * element kind, or a binding. A list of literals is the plain literal.
     */
    private fun listArg(type: String, name: String, ve: KtExpression, itemScope: Set<String>): PropValue? {
      if (widgetSpec(type) == null) return null
      val tag = listKindOf(propSourceType(type, name, components)) ?: return null
      val element = listElementType(tag)
      val items = ListSyntax.elements(ve)?.map { e ->
        val lit = parseLiteral(e.text.trim())
        when {
          lit != null -> lit.takeIf { it.tag == (if (tag == "ls") "s" else "i") } ?: return null
          // A bound element is declared as the element type, if at all.
          else -> (parseValue(e, ctx, itemScope, componentNames) as? PropValue.Bind)
            ?.let { b -> b.takeIf { declaredType(b)?.takeIf { it != UNKNOWN_TYPE }?.let { plainType(it) == element } ?: true } ?: return null }
            ?: tokens.lookup(e.text)?.takeIf { tokenFits(element, litValue(it.value)) }
            ?: return null
        }
      } ?: return null
      return dev.keliver.portal.document.canonicalValue(PropValue.ListOf(tag, items))
    }

    /**
     * K3: `if (<condition>) A else B` as the value of [type]'s [name] — the
     * condition in the grammar ([parseCond]) and each branch a value the
     * argument takes by itself (a literal, token, bind or action) or `null`.
     * A branch never nests another `if`; a lambda branch is written in
     * parentheses or as a reference (a bare `{ … }` would be the if's block).
     */
    private fun conditionalArg(type: String, name: String, ve: KtExpression, itemScope: Set<String>): PropValue.Conditional? {
      val ifExpr = ve as? KtIfExpression ?: return null
      val cond = parseCond(ifExpr.condition?.text ?: return null, ctx, itemScope) ?: return null
      fun branch(e: KtExpression?): Pair<Boolean, PropValue?> {
        val x = (e as? KtParenthesizedExpression)?.expression ?: e ?: return false to null
        if (x is KtIfExpression || x is KtBlockExpression) return false to null
        if (x.text.trim() == "null") return true to null
        val v = parseValue(x, ctx, itemScope, componentNames) ?: tokenArg(type, name, x.text)
          ?: templateArg(type, name, x, itemScope) ?: return false to null
        return true to v
      }
      val (okT, whenTrue) = branch(ifExpr.then)
      val (okF, whenFalse) = branch(ifExpr.`else`)
      if (!okT || !okF || (whenTrue == null && whenFalse == null)) return null
      if ((whenTrue == null || whenFalse == null) && !nullFits(type, name)) return null
      return PropValue.Conditional(cond.field, cond.test.wire, cond.negate, whenTrue, whenFalse)
    }

    /**
     * K3: whether `null` is a value of [type]'s [name] — the rule edits apply
     * too: a catalog widget's event (every catalog event is nullable), or a
     * project component parameter whose declared type is nullable.
     */
    private fun nullFits(type: String, name: String): Boolean {
      widgetSpec(type)?.let { return name in it.events }
      return isNullableType(components.spec(type)?.paramTypes?.get(name))
    }

    /** Why [expr] — not a widget, component or grammar construct — is RawCode. */
    private fun rawReason(expr: KtExpression, call: KtCallExpression?, type: String?): String = when {
      expr is KtIfExpression -> {
        val c = expr.condition?.text?.oneLine()
        when {
          c != null && parseCond(c, ctx) == null -> "condition `$c` is outside the portal grammar (a Boolean field, `!field`, " +
            "`field.isEmpty()`/`isNotEmpty()`/`isBlank()`/`isNotBlank()`" +
            (if (ctx is NameCtx.Component) ", or `param == null`/`!= null` on a nullable parameter)" else ")")
          expr.`else` is KtIfExpression -> "`else if` chains are outside the portal grammar (nest an `if` in the else block)"
          else -> "`if (${c ?: "…"})` needs block branches `{ … }`"
        }
      }
      call != null && type != null -> "`$type(…)` is not a widget or project component"
      else -> "`${expr.text.oneLine()}` is not a widget call or grammar construct"
    }
  }

  /** One line, shortened, for a diagnostic. */
  private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim().let { if (it.length > 80) it.take(77) + "…" else it }

  /**
   * K3: a condition's source text as a [Cond] in [ctx]: the field is a screen
   * binding `b.x` (or, for a conditional VALUE inside a Repeat, an item field
   * `item.x`) or a component parameter. Forms (each spelled one way):
   * `x`, `!x`, `x.isEmpty()`, `x.isNotEmpty()`, `x.isBlank()`, `x.isNotBlank()`,
   * and — on a component parameter declared nullable — `x == null`, `x != null`.
   */
  internal fun parseCond(text: String, ctx: NameCtx, itemScope: Set<String> = emptySet()): Cond? {
    val t = text.trim()
    val id = "[A-Za-z_][A-Za-z0-9_]*"
    val ref = when (ctx) {
      is NameCtx.Screen -> ctx.bindingsParam?.let { "${Regex.escape(it)}\\.$id" + if (itemScope.isEmpty()) "" else "|$id\\.$id" } ?: return null
      is NameCtx.Component -> "$id(?:\\.$id)?"
    }
    fun field(r: String): String? = when (ctx) {
      is NameCtx.Screen -> {
        val head = r.substringBefore('.')
        when {
          head == ctx.bindingsParam -> r.substringAfter('.')
          head in itemScope -> r
          else -> null
        }
      }
      is NameCtx.Component -> when {
        '.' !in r -> r.takeIf { it in ctx.valueParams || it in ctx.eventParams }
        r.substringBefore('.') in itemScope -> r
        else -> null
      }
    }
    Regex("^($ref)\\.(isEmpty|isNotEmpty|isBlank|isNotBlank)\\(\\)$").find(t)?.let { m ->
      val f = field(m.groupValues[1]) ?: return null
      return when (m.groupValues[2]) {
        "isEmpty" -> Cond(f, CondTest.Empty)
        "isNotEmpty" -> Cond(f, CondTest.Empty, negate = true)
        "isBlank" -> Cond(f, CondTest.Blank)
        else -> Cond(f, CondTest.Blank, negate = true)
      }
    }
    Regex("^($ref)\\s*(==|!=)\\s*null$").find(t)?.let { m ->
      // A screen contract cannot declare a nullable field; a parameter must be declared nullable.
      if (ctx !is NameCtx.Component || m.groupValues[1] !in ctx.nullableParams) return null
      return Cond(m.groupValues[1], CondTest.Null, negate = m.groupValues[2] == "!=")
    }
    Regex("^(!\\s*)?($ref)$").find(t)?.let { m ->
      val f = field(m.groupValues[2]) ?: return null
      // An event parameter is a function: only a null test reads it.
      if (ctx is NameCtx.Component && f in ctx.eventParams) return null
      return Cond(f, negate = m.groupValues[1].isNotEmpty())
    }
    return null
  }

  /**
   * `if (<condition>) { … }` with an optional `else { … }` (K3) — the
   * condition over a screen binding `b.x` or a component parameter (never an
   * item field: a Condition's field is a screen contract member). An
   * `else if` chain is not grammar.
   */
  private fun recognizeCondition(expr: KtExpression, ctx: NameCtx): Triple<Cond, List<KtExpression>, KtBlockExpression?>? {
    val ifExpr = expr as? KtIfExpression ?: return null
    val cond = parseCond(ifExpr.condition?.text ?: return null, ctx) ?: return null
    if ('.' in cond.field) return null
    val block = ifExpr.then as? KtBlockExpression ?: return null
    val elseBlock = when (val e = ifExpr.`else`) {
      null -> null
      is KtBlockExpression -> e
      else -> return null
    }
    return Triple(cond, block.statements, elseBlock)
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
      // `{ _ -> b.x() }`: the exporter's form for an event whose payload the action ignores.
      Regex("^\\{\\s*(?:_\\s*->\\s*)?${Regex.escape(bindingsParam)}\\.([A-Za-z_][A-Za-z0-9_]*)\\(\\)\\s*}$").find(t)
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
    // A template, raw string or concatenation is not a literal (null -> preserved as code).
    t.length >= 2 && t.startsWith('"') && t.endsWith('"') ->
      dev.keliver.portal.parseKotlinStringLiteral(t)?.let { PropValue.Lit("s", s = it) }
    // A Dp: `Dp(16.0)`, or (K1) `16.dp` / `16.5.dp`. `Dp(16)` does not compile (Dp takes a Double).
    t.startsWith("Dp(") || t.endsWith(".dp") -> MarginSyntax.parseDp(t)?.let { PropValue.Lit("d", d = it) }
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
        PropValue.Lit("ls", ls = items.map { dev.keliver.portal.parseKotlinStringLiteral(it) ?: return null })
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

  /**
   * "Modifier.padding(8).animateContentSize()" → {"Padding.allDp": 8, "AnimateContentSize": true}.
   * [parentType] is the node's layout parent (null = none): scoped layout
   * modifiers (`flex`, `margin`, `size(Dp, Dp)`, …) only resolve under a
   * parent that is one of their scopes. Null = not in the grammar.
   */
  internal fun parseModifierChain(
    text: String,
    parentType: String? = null,
    tokens: TokenLookup? = null,
    conds: CondParser? = null,
  ): Map<String, PropValue>? {
    val segments = ModifierChain.segments(text) ?: return null
    val resolved = ModifierChain.resolve(segments, parentType, tokens, conds) ?: return null
    val out = LinkedHashMap<String, PropValue>()
    resolved.forEach { out += it.props }
    return out
  }
}
