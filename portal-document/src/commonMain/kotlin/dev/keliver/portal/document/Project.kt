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
fun UiDocument.toWidgetTree(mocks: Map<String, Any?> = emptyMap()): WidgetNode = nodeToTree(root, mocks)

private fun nodeToTree(n: DocNode, mocks: Map<String, Any?>): WidgetNode = when (n) {
  is DocNode.RawCode -> WidgetNode(
    type = "RawCode",
    props = buildMap {
      put("text", n.text)
      n.kindHint?.let { put("kindHint", it) }
    },
  )
  is DocNode.Widget -> WidgetNode(
    type = n.type,
    props = buildMap {
      n.props.forEach { (name, v) ->
        when (v) {
          is PropValue.Lit -> put(name, v.toAny())
          is PropValue.Bind -> put(name, if (v.field in mocks) mocks[v.field] else Bind(v.field))
          is PropValue.Action -> put(name, Action(v.name))
        }
      }
      n.modifiers.forEach { (name, v) -> if (v is PropValue.Lit) put("mod.$name", v.toAny()) }
    },
    children = n.children.map { nodeToTree(it, mocks) },
  )
}
