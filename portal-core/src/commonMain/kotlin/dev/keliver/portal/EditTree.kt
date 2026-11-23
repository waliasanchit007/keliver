/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.portal

/** Immutable tree-edit helpers. They preserve untouched nodes' ids via copy(). */

public fun WidgetNode.findNode(id: Int): WidgetNode? =
  if (this.id == id) this else children.firstNotNullOfOrNull { it.findNode(id) }

public fun WidgetNode.updateProps(id: Int, props: Map<String, Any?>): WidgetNode =
  if (this.id == id) copy(props = props) else copy(children = children.map { it.updateProps(id, props) })

public fun WidgetNode.removeNode(id: Int): WidgetNode =
  copy(children = children.filter { it.id != id }.map { it.removeNode(id) })

public fun WidgetNode.insertChild(parentId: Int, child: WidgetNode, index: Int): WidgetNode =
  if (this.id == parentId) {
    copy(children = children.toMutableList().apply { add(index.coerceIn(0, size), child) })
  } else {
    copy(children = children.map { it.insertChild(parentId, child, index) })
  }

public fun WidgetNode.moveNode(id: Int, newParentId: Int, index: Int): WidgetNode {
  val node = findNode(id) ?: return this
  if (id == newParentId || node.findNode(newParentId) != null) return this // no move into self/descendant
  return removeNode(id).insertChild(newParentId, node, index)
}
