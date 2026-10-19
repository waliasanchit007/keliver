package dev.keliver.portal.document

import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.Cond
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.ListOf
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode

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
          is PropValue.Lit, is PropValue.Token, is PropValue.Conditional, is PropValue.Template, is PropValue.ListOf ->
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
}

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
  else -> lit(v)
}

/** K3: the condition a conditional value tests (an unknown test name reads as the Boolean field itself). */
public fun PropValue.Conditional.cond(): Cond = Cond(field, CondTest.fromWire(test) ?: CondTest.Bool, negate)

/** K3: a V1 [Conditional] lifted back into the document's [PropValue.Conditional]. */
public fun Conditional.toPropValue(): PropValue.Conditional = PropValue.Conditional(
  cond.field,
  cond.test.wire,
  cond.negate,
  whenTrue?.let(::treeToPropValue),
  whenFalse?.let(::treeToPropValue),
)

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
