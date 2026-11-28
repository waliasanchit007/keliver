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
@file:OptIn(RedwoodCodegenApi::class, KeliverFrameFoldApi::class)

package dev.keliver.treehouse

import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.leaks.LeakDetector
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierElement
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.WidgetTag
import dev.keliver.protocol.host.GeneratedHostProtocol
import dev.keliver.protocol.host.HostProtocolAdapter
import dev.keliver.protocol.host.ProtocolChildren
import dev.keliver.protocol.host.ProtocolMismatchHandler
import dev.keliver.protocol.host.ProtocolNode
import dev.keliver.protocol.host.UiChange
import dev.keliver.protocol.host.UiEventSink
import dev.keliver.protocol.host.UiPropertyChange
import dev.keliver.protocol.host.WidgetHostProtocol
import dev.keliver.protocol.host.hostRedwoodVersion
import dev.keliver.widget.Widget
import dev.keliver.widget.WidgetSystem
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * A tiny schema for ChangeFolder's tests, applied by the real HostProtocolAdapter:
 *
 *   widget 1 Box (children 1 and 2; property 1 = a lazy item key (string), 2 = hidden flag (bool))
 *   widget 2 Text (1 = text, 2 = spans (structured), 3 = style token, 4 = image url, 5 = never cached,
 *                  6 = number)
 *   widget 3 Opaque (not replayable)
 *   modifier 7 (an object: "desc" = text, "tag" = item key, "secret" = never cached, "w" = number)
 *   modifier 8 (no value)
 *   modifier 9 (not replayable)
 */
object TestSchema {
  const val BOX = 1
  const val TEXT = 2
  const val OPAQUE = 3

  val rules = FrameFoldRules(
    replayableWidgets = setOf(BOX, TEXT),
    replayableModifiers = setOf(7, 8),
    textWidgetProperties = mapOf(TEXT to setOf(1, 2, 4)),
    textModifierProperties = mapOf(7 to setOf("desc")),
    neverCachedWidgetProperties = mapOf(TEXT to setOf(5)),
    neverCachedModifierProperties = mapOf(7 to setOf("secret")),
    urlWidgetProperties = mapOf(TEXT to setOf(4)),
    positionKeyWidgetProperties = mapOf(BOX to setOf(1)),
    positionKeyModifierProperties = mapOf(7 to setOf("tag")),
  )

  /** Box's property 2 true: an entry that is not shown. */
  val hiddenWhenFlagged = HiddenEntryPolicy { _, _, child, _ ->
    child.widgetTag.value == BOX && child.property(2) == JsonPrimitive(true)
  }
}

class TestElement(val tag: Int, val value: JsonElement) : Modifier.UnscopedElement {
  override fun toString() = "M$tag=$value"
}

class TestWidget(val id: Id, val tag: Int) : Widget<TestWidget> {
  override val value: TestWidget get() = this
  override var modifier: Modifier = Modifier
  val props = mutableMapOf<Int, JsonElement>()
  val children = mutableMapOf<Int, TestChildren>()

  fun elements(): List<TestElement> {
    val out = mutableListOf<TestElement>()
    modifier.forEachUnscoped { out += it as TestElement }
    return out
  }
}

class TestChildren : Widget.Children<TestWidget> {
  override val widgets = mutableListOf<Widget<TestWidget>>()

  override fun insert(index: Int, widget: Widget<TestWidget>) {
    widgets.add(index, widget)
  }

  // The same list operation as the adapter's own bookkeeping (build-support's composeHelpers.kt).
  override fun move(fromIndex: Int, toIndex: Int, count: Int) {
    val dest = if (fromIndex > toIndex) toIndex else toIndex - count
    if (count == 1) {
      if (fromIndex == toIndex + 1 || fromIndex == toIndex - 1) {
        val fromEl = widgets[fromIndex]
        widgets[fromIndex] = widgets.set(toIndex, fromEl)
      } else {
        val el = widgets.removeAt(fromIndex)
        widgets.add(dest, el)
      }
    } else {
      val view = widgets.subList(fromIndex, fromIndex + count)
      val copy = view.toMutableList()
      view.clear()
      widgets.addAll(dest, copy)
    }
  }

  override fun remove(index: Int, count: Int) {
    repeat(count) { widgets.removeAt(index) }
  }

  override fun onModifierUpdated(index: Int, widget: Widget<TestWidget>) = Unit
  override fun detach() = Unit
}

private class TestNode(id: Id, override val widgetTag: WidgetTag) : ProtocolNode<TestWidget>(id) {
  override val widget = TestWidget(id, widgetTag.value)
  private val protocolChildren = mutableMapOf<Int, ProtocolChildren<TestWidget>>()

  override val widgetName: String get() = "Test"

  override fun apply(change: UiPropertyChange, eventSink: UiEventSink) {
    widget.props[change.tag.value] = change.value as JsonElement
  }

  override fun children(tag: ChildrenTag): ProtocolChildren<TestWidget>? {
    if (widgetTag.value != TestSchema.BOX || tag.value !in 1..2) return null
    return protocolChildren.getOrPut(tag.value) {
      val children = TestChildren()
      widget.children[tag.value] = children
      ProtocolChildren(children)
    }
  }

  override fun detach() = Unit
}

private class TagWidgetProtocol(private val tag: Int) : WidgetHostProtocol {
  @Suppress("UNCHECKED_CAST")
  override fun <W : Any> createNode(id: Id, widgetSystem: WidgetSystem<W>): ProtocolNode<W> =
    TestNode(id, WidgetTag(tag)) as ProtocolNode<W>

  @Suppress("UNCHECKED_CAST")
  override fun propertyDeserializer(tag: PropertyTag): DeserializationStrategy<Any?> =
    JsonElement.serializer() as DeserializationStrategy<Any?>

  override val childrenTags: IntArray? = null
}

private object TestProtocol : GeneratedHostProtocol {
  override val json: Json = Json.Default
  override val mismatchHandler: ProtocolMismatchHandler = ProtocolMismatchHandler.Throwing

  override fun widget(tag: WidgetTag): WidgetHostProtocol? = when (tag.value) {
    1, 2, 3 -> TagWidgetProtocol(tag.value)
    else -> null
  }

  override fun createModifier(element: ModifierElement): Modifier =
    TestElement(element.tag.value, element.value)
}

/** The real `HostProtocolAdapter` over the test schema. */
class TestHost {
  val root = TestChildren()
  private val adapter = HostProtocolAdapter(
    guestVersion = hostRedwoodVersion,
    container = root,
    protocol = TestProtocol,
    widgetSystem = object : WidgetSystem<TestWidget> {
      override fun apply(value: TestWidget, element: Modifier.UnscopedElement) = Unit
    },
    eventSink = UiEventSink { throw AssertionError("unexpected event: $it") },
    leakDetector = LeakDetector.none(),
  )

  fun apply(changes: List<Change>) {
    adapter.sendChanges(changes.mapNotNull { UiChange.fromProtocol(TestProtocol, it) })
  }

  fun top(): List<TestWidget> = root.widgets.map { it as TestWidget }
}

/** What the dump of a raw tree must leave out or change, as I2 and 2.4 say. */
class DumpRules(
  val shell: Boolean,
  val cached: Set<Int> = emptySet(),
  val notCached: Set<Int> = emptySet(),
  val hidden: (TestWidget) -> Boolean = { false },
)

/** The test's own statement of a position key (not the fold's code): the key's kind, the position. */
private fun positionKey(original: JsonElement, position: Int): JsonElement =
  if (original is JsonPrimitive && !original.isString) JsonPrimitive(position) else JsonPrimitive(position.toString())

private fun lineCount(element: JsonElement): JsonElement = when (element) {
  is JsonArray -> JsonArray(element.map { lineCount(it) })
  is JsonObject -> JsonObject(element.mapValues { (_, v) -> lineCount(v) })
  is JsonPrimitive -> if (element.isString) JsonPrimitive("L${element.content.count { it == '\n' } + 1}") else element
}

/**
 * A canonical text of the widgets under [widgets]. [raw] is the host that was fed the guest's
 * batches: it applies what the rules say (drops what is never stored, a `notCached` subtree and a
 * hidden entry, replaces a lazy key by its position). The other side prints what the snapshot
 * made. Strings of text properties in `Shell` are reduced to their line count on both sides.
 */
fun dump(widgets: List<TestWidget>, rules: DumpRules, raw: Boolean): String = buildString {
  fun write(w: TestWidget, position: Int, depth: Int) {
    val pad = "  ".repeat(depth)
    val cached = w.id.value in rules.cached
    append(pad).append("W").append(w.tag).append("#").append(w.id.value).append('\n')
    for (tag in w.props.keys.sorted()) {
      var v = w.props.getValue(tag)
      if (w.tag == TestSchema.TEXT) {
        if (raw && tag == 5) continue
        if (raw && rules.shell && !cached && tag == 4) continue
        if (rules.shell && !cached && tag in setOf(1, 2, 4)) v = lineCount(v)
      }
      if (raw && w.tag == TestSchema.BOX && tag == 1) v = positionKey(v, position)
      append(pad).append("  p").append(tag).append('=').append(v).append('\n')
    }
    for (e in w.elements()) {
      if (raw && e.tag == -4_543_827) continue // The adapter's reuse hint: not part of the drawn tree.
      var v = e.value
      if (e.tag == 7 && v is JsonObject) {
        val entries = v.entries.filterNot { raw && it.key == "secret" }.sortedBy { it.key }.associate { (k, x) ->
          k to when {
            raw && k == "tag" -> positionKey(x, position)
            rules.shell && !cached && k == "desc" -> lineCount(x)
            else -> x
          }
        }
        v = JsonObject(entries)
      }
      append(pad).append("  m").append(e.tag).append('=').append(v).append('\n')
    }
    for (tag in w.children.keys.sorted()) {
      val kept = w.children.getValue(tag).widgets.map { it as TestWidget }
        .filterNot { raw && (it.id.value in rules.notCached || rules.hidden(it)) }
      if (kept.isEmpty()) continue // A slot with no child is the same tree, created or not.
      append(pad).append("  children").append(tag).append(":\n")
      kept.forEachIndexed { i, child -> write(child, i, depth + 2) }
    }
  }
  widgets.forEachIndexed { i, w -> write(w, i, 0) }
}
