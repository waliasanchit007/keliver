package dev.keliver.portal.document

import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode

/**
 * Projects the Document onto the V1 interchange tree the whole existing
 * pipeline consumes (editor preview, devices, exporter): binds resolve via
 * [mocks] or stay as V1 typed [Bind] values, actions become V1 [Action]
 * values, modifiers ride as "mod.<Name>.<prop>" keys, RawCode becomes the
 * "RawCode" placeholder type.
 */
fun UiDocument.toWidgetTree(
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
      n.props.forEach { (name, v) ->
        when (v) {
          is PropValue.Lit -> put(name, v.toAny())
          is PropValue.Bind -> put(name, if (v.field in mocks) mocks[v.field] else Bind(v.field))
          is PropValue.Action -> put(name, Action(v.name, v.arg))
        }
      }
      n.modifiers.forEach { (name, v) -> if (v is PropValue.Lit) put("mod.$name", v.toAny()) }
    }
    val children = n.children.map { nodeToTree(it, mocks, handleIds) }
    if (handleIds) WidgetNode(n.type, props, children, id = n.handle.v.toInt()) else WidgetNode(n.type, props, children)
  }
}

/** Editor→server direction: lift a V1 node (palette sample / duplicate) into a DocNode for InsertNode. */
fun WidgetNode.toDocNode(): DocNode.Widget = DocNode.Widget(
  handle = Handle(0), // server allocates
  type = type,
  props = props.filterKeys { !it.startsWith("mod.") }.mapValues { (_, v) ->
    when (v) {
      is Bind -> PropValue.Bind(v.field)
      is Action -> PropValue.Action(v.name, v.arg)
      else -> lit(v)
    }
  },
  modifiers = props.filterKeys { it.startsWith("mod.") }
    .map { (k, v) -> k.removePrefix("mod.") to lit(v) }.toMap(),
  children = children.map { it.toDocNode() },
)
