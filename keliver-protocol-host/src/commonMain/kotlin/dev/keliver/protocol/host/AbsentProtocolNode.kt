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
package dev.keliver.protocol.host

import dev.keliver.RedwoodCodegenApi
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Id
import dev.keliver.protocol.WidgetTag
import dev.keliver.widget.MutableListChildren
import dev.keliver.widget.Widget

/**
 * The node for a widget of a group this host was built without (docs/MS4_PLAN.md, MS4-03): the
 * generated host protocol reports its tag as unknown and creates this instead of a real widget.
 *
 * It keeps the tree's shape, so the guest's later changes still find a node: properties are
 * ignored, [widget] (the widget system's absent widget) is inserted where the real one would be,
 * and children of the widget's own children tags are kept, undrawn, so their ids resolve.
 *
 * @suppress
 */
@RedwoodCodegenApi
public class AbsentProtocolNode<W : Any>(
  id: Id,
  override val widgetTag: WidgetTag,
  override val widgetName: String,
  override val widget: Widget<W>,
  private val childrenTags: IntArray?,
) : ProtocolNode<W>(id) {
  private val children = mutableMapOf<Int, ProtocolChildren<W>>()

  override fun apply(change: UiPropertyChange, eventSink: UiEventSink) {
    // The widget is absent: there is nothing to set. Its tag was reported once, when created.
  }

  override fun children(tag: ChildrenTag): ProtocolChildren<W>? {
    if (childrenTags == null || tag.value !in childrenTags) return null
    return children.getOrPut(tag.value) { ProtocolChildren(MutableListChildren()) }
  }

  override fun visitIds(visitor: IdVisitor) {
    visitor.visit(id)
    for (child in children.values) child.visitIds(visitor)
  }

  override fun detach() {
    for (child in children.values) child.detach()
    children.clear()
  }
}
