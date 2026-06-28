package dev.keliver.portal

/** Immutable tree-edit helpers. They preserve untouched nodes' ids via copy(). */

fun WidgetNode.findNode(id: Int): WidgetNode? =
  if (this.id == id) this else children.firstNotNullOfOrNull { it.findNode(id) }

fun WidgetNode.updateProps(id: Int, props: Map<String, Any?>): WidgetNode =
  if (this.id == id) copy(props = props) else copy(children = children.map { it.updateProps(id, props) })

fun WidgetNode.removeNode(id: Int): WidgetNode =
  copy(children = children.filter { it.id != id }.map { it.removeNode(id) })

fun WidgetNode.insertChild(parentId: Int, child: WidgetNode, index: Int): WidgetNode =
  if (this.id == parentId) copy(children = children.toMutableList().apply { add(index.coerceIn(0, size), child) })
  else copy(children = children.map { it.insertChild(parentId, child, index) })

fun WidgetNode.moveNode(id: Int, newParentId: Int, index: Int): WidgetNode {
  val node = findNode(id) ?: return this
  if (id == newParentId || node.findNode(newParentId) != null) return this // no move into self/descendant
  return removeNode(id).insertChild(newParentId, node, index)
}
