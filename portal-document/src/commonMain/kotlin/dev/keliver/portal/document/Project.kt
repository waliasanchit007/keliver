package dev.keliver.portal.document

import dev.keliver.portal.Action
import dev.keliver.portal.Arith
import dev.keliver.portal.ArithOp
import dev.keliver.portal.Bind
import dev.keliver.portal.Cond
import dev.keliver.portal.CondJoin
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.IntDp
import dev.keliver.portal.ListOf
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.condOfTerms
import dev.keliver.portal.conditionProps
import dev.keliver.portal.isConditionKey

/**
 * Projects the Document onto the V1 interchange tree the whole existing
 * pipeline consumes (editor preview, devices, exporter): binds resolve via
 * [mocks] or stay as V1 typed [Bind] values, actions become V1 [Action]
 * values, modifiers ride as "mod.<Name>.<prop>" keys, RawCode becomes the
 * "RawCode" placeholder type. A design-token reference (K2) becomes a V1
 * [Token] carrying its resolved value (the preview renders the value, the
 * exporter writes the reference). A conditional value (K3) becomes a V1
 * [Conditional] whose branches are projected the same way. A template or a
 * list with references (K4) becomes a V1 [Template] / [ListOf] whose parts
 * are projected the same way (a literal text segment is a plain String).
 * An arithmetic value or an Int used as a Dp (K7) becomes a V1 [Arith] /
 * [IntDp] whose operands are projected the same way.
 */
public fun UiDocument.toWidgetTree(
  mocks: Map<String, Any?> = emptyMap(),
  /** true = WidgetNode.id carries the node HANDLE (editor mode: panels key ops off ids). */
  handleIds: Boolean = false,
): WidgetNode = nodeToTree(root, mocks, handleIds)

private fun nodeToTree(n: DocNode, mocks: Map<String, Any?>, handleIds: Boolean): WidgetNode = when (n) {
  is DocNode.RawCode -> {
    val props = buildMap<String, Any?> {
      put("text", n.text)
      n.kindHint?.let { put("kindHint", it) }
    }
    if (handleIds) WidgetNode("RawCode", props, emptyList(), id = n.handle.v.toInt()) else WidgetNode("RawCode", props)
  }
  is DocNode.Widget -> {
    val props = buildMap<String, Any?> {
      n.props.forEach { (name, v) -> put(name, v.toTreeValue(mocks)) }
      n.modifiers.forEach { (name, v) ->
        when (v) {
          is PropValue.Lit, is PropValue.Token, is PropValue.Conditional, is PropValue.Template, is PropValue.ListOf,
          is PropValue.Arith, is PropValue.IntDp ->
            put("mod.$name", v.toTreeValue(mocks))
          else -> {}
        }
      }
    }
    val children = n.children.map { nodeToTree(it, mocks, handleIds) }
    if (handleIds) WidgetNode(n.type, props, children, id = n.handle.v.toInt()) else WidgetNode(n.type, props, children)
  }
}

/** One document value as the V1 tree carries it (a bind the [mocks] know becomes the mock). */
private fun PropValue.toTreeValue(mocks: Map<String, Any?>): Any? = when (this) {
  is PropValue.Lit -> toAny()
  is PropValue.Bind -> if (field in mocks) mocks[field] else Bind(field)
  is PropValue.Action -> Action(name, arg)
  is PropValue.Token -> toTreeValue()
  is PropValue.Conditional -> Conditional(cond(), whenTrue?.toTreeValue(mocks), whenFalse?.toTreeValue(mocks))
  is PropValue.Template -> Template(parts.map { it.toTreeValue(mocks) })
  is PropValue.ListOf -> ListOf(tag, items.map { it.toTreeValue(mocks) })
  // K7: an unknown operator (never stored: the document refuses it) projects to no value.
  is PropValue.Arith -> ArithOp.fromSymbol(op)?.let { Arith(it, left.toTreeValue(mocks), right.toTreeValue(mocks)) }
  is PropValue.IntDp -> IntDp(value.toTreeValue(mocks), ctor = form == DP_CTOR_FORM)
}

/** K7: [PropValue.IntDp.form] for the `Dp(x.toDouble())` spelling (null = `x.dp`). */
public const val DP_CTOR_FORM: String = "Dp"

/** Editor→server direction: lift a V1 node (palette sample / duplicate) into a DocNode for InsertNode. */
public fun WidgetNode.toDocNode(): DocNode.Widget = DocNode.Widget(
  handle = Handle(0), // server allocates
  type = type,
  props = props.filterKeys { !it.startsWith("mod.") }.mapValues { (_, v) -> treeToPropValue(v) },
  modifiers = props.filterKeys { it.startsWith("mod.") }
    .map { (k, v) -> k.removePrefix("mod.") to treeToPropValue(v) }.toMap(),
  children = children.map { it.toDocNode() },
)

/** A V1 tree value lifted into the document's [PropValue]. */
public fun treeToPropValue(v: Any?): PropValue = when (v) {
  is Bind -> PropValue.Bind(v.field)
  is Action -> PropValue.Action(v.name, v.arg)
  is Token -> v.toPropValue()
  is Conditional -> v.toPropValue()
  is Template -> PropValue.Template(v.parts.map(::treeToPropValue))
  is ListOf -> PropValue.ListOf(v.kind, v.items.map(::treeToPropValue))
  is Arith -> PropValue.Arith(v.op.symbol, treeToPropValue(v.left), treeToPropValue(v.right))
  is IntDp -> PropValue.IntDp(treeToPropValue(v.value), if (v.ctor) DP_CTOR_FORM else null)
  else -> lit(v)
}

/**
 * K3: the condition a conditional value tests (an unknown test name reads as
 * the Boolean field itself; K6: an unknown join as `&&`). K6: every term, an
 * equality's operand as a tree value.
 */
public fun PropValue.Conditional.cond(): Cond {
  fun term(field: String, test: String?, negate: Boolean, operand: PropValue?): Cond {
    val t = CondTest.fromWire(test) ?: CondTest.Bool
    return Cond(field, t, negate, if (t == CondTest.Eq) operand?.toTreeValue(emptyMap()) else null)
  }
  val terms = listOf(term(field, test, negate, operand)) + more.map { term(it.field, it.test, it.negate, it.operand) }
  return condOfTerms(terms, CondJoin.fromWire(join) ?: CondJoin.And)
}

/** K6: the document's form of [this] condition with the branches [whenTrue] and [whenFalse]. */
public fun Cond.toConditional(whenTrue: PropValue?, whenFalse: PropValue?): PropValue.Conditional = PropValue.Conditional(
  field,
  test.wire,
  negate,
  whenTrue,
  whenFalse,
  operand = operand?.takeIf { test == CondTest.Eq }?.let(::treeToPropValue),
  more = more.map { t -> CondTerm(t.field, t.test.wire, t.negate, t.operand?.takeIf { t.test == CondTest.Eq }?.let(::treeToPropValue)) },
  join = if (more.isEmpty()) null else join.wire,
)

/** K3: a V1 [Conditional] lifted back into the document's [PropValue.Conditional]. */
public fun Conditional.toPropValue(): PropValue.Conditional =
  cond.toConditional(whenTrue?.let(::treeToPropValue), whenFalse?.let(::treeToPropValue))

/**
 * K6: the [Cond] a `Condition` node tests — its condition props
 * ([dev.keliver.portal.conditionKey]) read as [dev.keliver.portal.condOf]
 * reads them in the tree — or null when it has no field or a part this build
 * cannot read.
 */
public fun DocNode.Widget.condition(): Cond? =
  dev.keliver.portal.condOf(WidgetNode(type, props.filterKeys(::isConditionKey).mapValues { it.value.toTreeValue(emptyMap()) }))

/** K6: a `Condition` node's props for [cond] (defaults left out) — what [condition] reads. */
public fun conditionNodeProps(cond: Cond): Map<String, PropValue> = conditionProps(cond) { v -> treeToPropValue(v) }

/** K6: whether [this] uses a K6 form — an equality, or more than one term — which a K3 reader cannot decode. */
public fun PropValue.Conditional.isExtended(): Boolean = test == CondTest.Eq.wire || more.isNotEmpty() || join != null || operand != null

/**
 * K6 compatibility: [doc] as an editor that knows K3 conditionals but not
 * their K6 forms can decode it — every conditional VALUE with an equality or
 * several terms ([isExtended]) replaced by its true branch (a prop whose true
 * branch is `null` is left out), as an editor that predates conditionals is
 * served. The relay serves this unless the client advertises them
 * (`extendedConditions=1`); an edit such a client makes to another prop
 * leaves the conditional in the document and the file (only a prop it
 * rewrites loses it). A `Condition` NODE reaches it unchanged: it sees the
 * first term's `field`/`test`/`negate`, and its edits of those are checked
 * by the document as any other client's.
 */
public fun UiDocument.withoutExtendedConditions(): UiDocument {
  fun values(m: Map<String, PropValue>): Map<String, PropValue> = buildMap {
    m.forEach { (k, v) ->
      val plain = if (v is PropValue.Conditional && v.isExtended()) v.whenTrue else v
      if (plain != null) put(k, plain)
    }
  }
  fun node(n: DocNode): DocNode = when (n) {
    is DocNode.RawCode -> n
    is DocNode.Widget -> n.copy(props = values(n.props), modifiers = values(n.modifiers), children = n.children.map(::node))
  }
  return copy(root = node(root))
}

/**
 * K3 compatibility: [doc] as an editor that predates conditional values can
 * decode it — every [PropValue.Conditional] replaced by its true branch (a
 * prop whose true branch is `null` is left out). The relay serves this unless
 * the client advertises them (`conditionals=1`); as with design tokens, an
 * edit such a client makes to another prop leaves the conditional in the
 * document and the file (only a prop it rewrites loses it).
 */
public fun UiDocument.withoutConditionals(): UiDocument {
  fun values(m: Map<String, PropValue>): Map<String, PropValue> = buildMap {
    m.forEach { (k, v) ->
      val plain = if (v is PropValue.Conditional) v.whenTrue else v
      if (plain != null) put(k, plain)
    }
  }
  fun node(n: DocNode): DocNode = when (n) {
    is DocNode.RawCode -> n
    is DocNode.Widget -> n.copy(props = values(n.props), modifiers = values(n.modifiers), children = n.children.map(::node))
  }
  return copy(root = node(root))
}

/** K2: the V1 tree value of a token reference. */
public fun PropValue.Token.toTreeValue(): Token = Token(ref, value.toAny(), import)

/** K2: a V1 [Token] lifted back into the document's [PropValue.Token]. */
public fun Token.toPropValue(): PropValue.Token = PropValue.Token(ref, lit(value), importFq)
