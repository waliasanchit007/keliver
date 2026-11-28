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
package dev.keliver.treehouse

import dev.keliver.protocol.Change
import dev.keliver.protocol.ChildrenChange
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Create
import dev.keliver.protocol.FrameCacheTags
import dev.keliver.protocol.FrameMarks
import dev.keliver.protocol.Id
import dev.keliver.protocol.KeliverFrameMarksApi
import dev.keliver.protocol.ModifierChange
import dev.keliver.protocol.ModifierElement
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.PropertyChange
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.SnapshotChangeList
import dev.keliver.protocol.WidgetTag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * NF5 A3a: folds the raw [Change] batches a content receives (before `HostProtocolAdapter`) into one
 * snapshot that recreates the tree the host drew for the subtree under the `Modifier.cachedFrame`
 * marker, and only that (docs/SHOW_BEFORE_COMPUTE.md 2.3, 2.4, 5.2, I2 and I11).
 *
 * Feed every batch, in order, to [apply]; call [fold] at the `ready` edge. The fold keeps its own
 * tree (Create, properties, modifiers, Add, Move, Remove with and without `detach`), so a node
 * removed and re-added, or moved, is where the host has it. It then writes the subtree, in an order a
 * fresh host can apply (a node's Create, its modifier and properties, then each child followed by its
 * Add), applying these rules:
 *
 * - **Pruning.** Children the entry policy calls hidden (NF1's retained entries below the top, (B)'s
 *   prepared entries) are dropped with their subtrees. So are `notCached()` subtrees, in both
 *   levels. Detached nodes are not attached, so they never appear.
 * - **Replayable only.** A widget or modifier that the rules do not list blocks the fold
 *   ([FoldResult.Blocked]); nothing is replayable by default. The framework markers and the reuse
 *   modifier are not written.
 * - **`@NeverCached`** values are never written, in either level.
 * - **Position keys:** lazy item keys are replaced by the node's position, in both levels.
 * - **[FrameFoldContent.Shell]:** every string of a text- or URL-bearing property is replaced by a
 *   placeholder with the same line count (URLs are omitted), unless its node is `cached()`. For a
 *   structured value (a `RichText`'s spans) each string inside is replaced and the rest kept, so the
 *   widget keeps its height. Every property the lists do not name is kept as it is, `@StyleToken`
 *   ones included.
 *
 * A batch the fold cannot follow (an unknown id, an index out of range) never throws: the fold is
 * marked inconsistent and [fold] blocks from then on, because a stored frame must be the drawn tree.
 *
 * The snapshot keeps the guest's node ids. The marked node must be a direct child of the root and
 * the only marked one in what is shown; the recorder (A3c) holds the other conditions of 2.3.
 *
 * Limit: the keys of a JSON object inside a structured text value are kept (they are the value
 * class's field names). A `Map<String, String>` typed text value would keep its keys.
 */
@KeliverFrameFoldApi
@OptIn(KeliverFrameMarksApi::class)
public class ChangeFolder(
  private val rules: FrameFoldRules,
  private val entryPolicy: HiddenEntryPolicy = HiddenEntryPolicy.NothingHidden,
  private val textShapes: TextLineShapes? = null,
) {
  private val nodes = mutableMapOf<Int, Node>()
  private val root = Node(Id.Root, WidgetTag(-1), attached = true)
  private var inconsistent = false

  init {
    nodes[Id.Root.value] = root
  }

  /** Applies one batch of the guest's changes, as the host does. */
  public fun apply(changes: List<Change>) {
    if (inconsistent) return
    for (change in changes) {
      if (!applyOne(change)) {
        inconsistent = true
        return
      }
    }
  }

  private fun applyOne(change: Change): Boolean {
    when (change) {
      is Create -> {
        if (nodes.containsKey(change.id.value)) return false
        nodes[change.id.value] = Node(change.id, change.tag, attached = false)
      }

      is PropertyChange -> {
        val node = nodes[change.id.value] ?: return false
        if (node === root) return false
        node.properties[change.propertyTag.value] = change.value
      }

      is ModifierChange -> {
        val node = nodes[change.id.value] ?: return false
        if (node === root) return false
        node.modifier = change.elements
      }

      is ChildrenChange.Add -> {
        val parent = nodes[change.id.value] ?: return false
        val child = nodes[change.childId.value] ?: return false
        if (child === root || child.attached) return false
        val list = parent.children.getOrPut(change.tag.value) { mutableListOf() }
        if (change.index !in 0..list.size) return false
        list.add(change.index, child)
        child.attached = true
      }

      is ChildrenChange.Move -> {
        val list = nodes[change.id.value]?.children?.get(change.tag.value) ?: return false
        val from = change.fromIndex
        val to = change.toIndex
        val count = change.count
        if (count < 1 || from < 0 || from + count > list.size || to < 0 || to > list.size) return false
        val dest = if (from > to) to else to - count
        if (dest < 0 || dest + count > list.size) return false
        move(list, from, to, count, dest)
      }

      is ChildrenChange.Remove -> {
        val list = nodes[change.id.value]?.children?.get(change.tag.value) ?: return false

        @Suppress("DEPRECATION") // Old guests send counts above one.
        val count = change.count
        if (count < 1 || change.index < 0 || change.index + count > list.size) return false
        val removed = list.subList(change.index, change.index + count).toList()
        list.subList(change.index, change.index + count).clear()
        for (node in removed) {
          node.attached = false
          if (!change.detach) forget(node)
        }
      }
    }
    return true
  }

  /** The same moves `HostProtocolAdapter` makes (its `MutableList.move`), including the swap. */
  private fun move(list: MutableList<Node>, from: Int, to: Int, count: Int, dest: Int) {
    if (count == 1) {
      if (from == to + 1 || from == to - 1) {
        val fromEl = list[from]
        list[from] = list.set(to, fromEl)
      } else {
        val el = list.removeAt(from)
        list.add(dest, el)
      }
    } else {
      val view = list.subList(from, from + count)
      val copy = view.toMutableList()
      view.clear()
      list.addAll(dest, copy)
    }
  }

  /** A node removed without `detach` is gone, with everything under it. */
  private fun forget(node: Node) {
    nodes.remove(node.id.value)
    for (list in node.children.values) {
      for (child in list) forget(child)
    }
  }

  /** The marked subtree as a snapshot, or why nothing may be recorded. */
  public fun fold(): FoldResult {
    if (inconsistent) return FoldResult.Blocked("inconsistent")

    val marked = mutableListOf<Node>()
    collectMarked(root, marked)
    if (marked.isEmpty()) return FoldResult.Blocked("noMark")
    if (marked.size > 1) return FoldResult.Blocked("multipleMarks")
    val top = marked.single()
    if (!root.children.values.any { list -> list.any { it === top } }) {
      return FoldResult.Blocked("notRootChild")
    }
    val marks = marksOf(top)!!
    if (marks.notCached) return FoldResult.Blocked("notCachedRoot")
    val mark = marks.cachedFrame!!
    val content = if (mark.content == "shell") FrameFoldContent.Shell else FrameFoldContent.Whole

    val out = mutableListOf<Change>()
    try {
      Emitter(content, out).emit(top, position = 0)
    } catch (blocked: BlockedException) {
      return FoldResult.Blocked(blocked.reason)
    }
    out += ChildrenChange.Add(Id.Root, ChildrenTag.Root, top.id, 0)
    return FoldResult.Folded(SnapshotChangeList(out), content, mark.maxAgeMillis, mark.ready, mark.paneTitle)
  }

  private fun marksOf(node: Node): FrameMarks? = FrameMarks.read(node.modifier)

  private fun collectMarked(node: Node, into: MutableList<Node>) {
    if (node !== root && marksOf(node)?.cachedFrame != null) into += node
    for ((tag, list) in node.children) {
      for ((index, child) in list.withIndex()) {
        if (entryPolicy.isHidden(node, tag, child, index)) continue
        collectMarked(child, into)
      }
    }
  }

  private class BlockedException(val reason: String) : RuntimeException(reason)

  private inner class Emitter(
    content: FrameFoldContent,
    private val out: MutableList<Change>,
  ) {
    private val shell = content == FrameFoldContent.Shell

    fun emit(node: Node, position: Int) {
      val tag = node.widgetTag.value
      if (tag !in rules.replayableWidgets) throw BlockedException("notReplayable:widget:$tag")
      val cached = marksOf(node)?.cached == true
      out += Create(node.id, node.widgetTag)

      val modifier = modifierElements(node, position, cached)
      if (modifier.isNotEmpty()) out += ModifierChange(node.id, modifier)

      val never = rules.neverCachedWidgetProperties[tag].orEmpty()
      val text = rules.textWidgetProperties[tag].orEmpty()
      val urls = rules.urlWidgetProperties[tag].orEmpty()
      val keys = rules.positionKeyWidgetProperties[tag].orEmpty()
      for (propertyTag in node.properties.keys.sorted()) {
        if (propertyTag in never) continue
        var value = node.properties.getValue(propertyTag)
        if (propertyTag in keys) {
          value = positionKey(value, position)
        } else if (shell && !cached && propertyTag in text) {
          if (propertyTag in urls) continue
          value = replaceStrings(value) { string ->
            placeholder(string, textShapes?.lineLengths(node.id, propertyTag, string))
          }
        }
        out += PropertyChange(node.id, node.widgetTag, PropertyTag(propertyTag), value)
      }

      for (childrenTag in node.children.keys.sorted()) {
        val kept = node.children.getValue(childrenTag).withIndex()
          .filter { (index, child) -> !entryPolicy.isHidden(node, childrenTag, child, index) }
          .map { it.value }
          .filter { marksOf(it)?.notCached != true }
        for ((index, child) in kept.withIndex()) {
          emit(child, index)
          out += ChildrenChange.Add(node.id, ChildrenTag(childrenTag), child.id, index)
        }
      }
    }

    private fun modifierElements(node: Node, position: Int, cached: Boolean): List<ModifierElement> {
      val kept = mutableListOf<ModifierElement>()
      for (element in node.modifier) {
        val tag = element.tag.value
        if (FrameCacheTags.isReserved(tag) || tag == REUSE_MODIFIER_TAG) continue
        if (tag !in rules.replayableModifiers) throw BlockedException("notReplayable:modifier:$tag")
        val value = element.value
        if (value !is JsonObject) {
          kept += element
          continue
        }
        val never = rules.neverCachedModifierProperties[tag].orEmpty()
        val text = rules.textModifierProperties[tag].orEmpty()
        val urls = rules.urlModifierProperties[tag].orEmpty()
        val keys = rules.positionKeyModifierProperties[tag].orEmpty()
        val entries = LinkedHashMap<String, JsonElement>()
        for ((name, v) in value) {
          if (name in never) continue
          entries[name] = when {
            name in keys -> positionKey(v, position)
            shell && !cached && name in text -> {
              if (name in urls) continue
              replaceStrings(v) { string -> placeholder(string, null) }
            }
            else -> v
          }
        }
        kept += ModifierElement(ModifierTag(tag), JsonObject(entries))
      }
      return kept
    }
  }

  private class Node(
    override val id: Id,
    override val widgetTag: WidgetTag,
    var attached: Boolean,
  ) : FoldNode {
    val properties = mutableMapOf<Int, JsonElement>()
    var modifier: List<ModifierElement> = emptyList()
    val children = mutableMapOf<Int, MutableList<Node>>()

    override fun property(tag: Int): JsonElement? = properties[tag]
  }
}

/** The host adapter's widget-reuse modifier; a recycling hint, not part of the drawn tree. */
private const val REUSE_MODIFIER_TAG = -4_543_827

/** The figure space (the code point 0x2007), as wide as a digit and not collapsed. Spelled as a number. */
private val FIGURE_SPACE: Char = 0x2007.toChar()

private fun replaceStrings(value: JsonElement, replace: (String) -> String): JsonElement = when (value) {
  is JsonPrimitive -> if (value.isString) JsonPrimitive(replace(value.content)) else value
  is JsonArray -> JsonArray(value.map { replaceStrings(it, replace) })
  is JsonObject -> JsonObject(value.mapValues { (_, v) -> replaceStrings(v, replace) })
}

private fun positionKey(value: JsonElement, position: Int): JsonElement = when {
  value is JsonNull -> value
  value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null -> JsonPrimitive(position)
  else -> JsonPrimitive(position.toString())
}

/**
 * A placeholder with the same lines as [text] (or [lineLengths]' count): each line is groups of four
 * figure spaces joined by spaces, with at most that line's own characters. Empty lines stay empty,
 * so the line count survives and nothing else of the text does.
 */
private fun placeholder(text: String, lineLengths: List<Int>?): String {
  val lengths = lineLengths ?: text.split('\n').map { it.length }
  return lengths.joinToString("\n") { placeholderLine(it) }
}

private fun placeholderLine(length: Int): String {
  val builder = StringBuilder()
  var i = 0
  while (builder.length < length) {
    builder.append(if (i % 5 == 4) ' ' else FIGURE_SPACE)
    i++
  }
  if (builder.isNotEmpty() && builder.last() == ' ') builder.setLength(builder.length - 1)
  return builder.toString()
}
