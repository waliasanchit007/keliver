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

import dev.keliver.protocol.Id
import dev.keliver.protocol.SnapshotChangeList
import dev.keliver.protocol.WidgetTag
import kotlinx.serialization.json.JsonElement

/**
 * What [ChangeFolder] may replay, as lists. The fold is generic: it knows no schema. The lists are
 * the generated `<Schema>FrameRules` (docs/SHOW_BEFORE_COMPUTE.md 2.4), passed in by the recorder
 * under the same names. Tags are as on the wire; a modifier's properties are named as in its
 * serialized value (an object's keys). **Nothing is replayable by default**: a widget or modifier
 * not listed blocks the fold (fail closed).
 */
@KeliverFrameFoldApi
public class FrameFoldRules(
  public val replayableWidgets: Set<Int> = emptySet(),
  public val replayableModifiers: Set<Int> = emptySet(),
  /**
   * Per widget, the properties whose strings `Shell` replaces (text and URLs; `@StyleToken` and
   * `@NeverCached` ones are not in it, so `Shell` keeps style tokens).
   */
  public val textWidgetProperties: Map<Int, Set<Int>> = emptyMap(),
  public val textModifierProperties: Map<Int, Set<String>> = emptyMap(),
  /** Per widget, the properties never stored, in either level (`@NeverCached`). */
  public val neverCachedWidgetProperties: Map<Int, Set<Int>> = emptyMap(),
  public val neverCachedModifierProperties: Map<Int, Set<String>> = emptyMap(),
  /**
   * Per widget, the URL-bearing properties. In `Shell` they are omitted instead of replaced (the
   * widget then uses its default: an image's placeholder). Omitting needs no placeholder because a
   * URL has no line count to keep. Each is also expected in [textWidgetProperties].
   */
  public val urlWidgetProperties: Map<Int, Set<Int>> = emptyMap(),
  public val urlModifierProperties: Map<Int, Set<String>> = emptyMap(),
  /**
   * Per widget, the properties that carry a lazy item's key, where the protocol carries one. Both
   * levels replace the value by the node's position among the children kept (the key's kind, text
   * or number, is preserved).
   */
  public val positionKeyWidgetProperties: Map<Int, Set<Int>> = emptyMap(),
  public val positionKeyModifierProperties: Map<Int, Set<String>> = emptyMap(),
)

/** A node of the tree the fold has built from the raw batches, as the entry policy sees it. */
@KeliverFrameFoldApi
public interface FoldNode {
  public val id: Id
  public val widgetTag: WidgetTag

  /** The latest value the guest sent for this property, or null if none was sent. */
  public fun property(tag: Int): JsonElement?
}

/**
 * Which children of a container are not shown: NF1's retained entries below the top and (B)'s
 * prepared entries. The fold prunes them with their subtrees (docs/SHOW_BEFORE_COMPUTE.md 2.3). The
 * rule uses the container's explicit entry state, not the children's order, so the retained
 * container's renderer (B0) supplies it; until then nothing is hidden.
 */
@KeliverFrameFoldApi
public fun interface HiddenEntryPolicy {
  /** True when [child], at [index] of [parent]'s children [childrenTag], is not shown. */
  public fun isHidden(parent: FoldNode, childrenTag: Int, child: FoldNode, index: Int): Boolean

  public companion object {
    public val NothingHidden: HiddenEntryPolicy = HiddenEntryPolicy { _, _, _, _ -> false }
  }
}

/**
 * How a text laid out, for `Shell`'s placeholders (docs/SHOW_BEFORE_COMPUTE.md 5.2). The recorder
 * (A3c) measures each text-bearing widget at the `ready` edge and passes the per-line character
 * counts here. Without it, the placeholder has the lines the string itself has (split at `\n`) and
 * never more characters on a line than the original line had.
 */
@KeliverFrameFoldApi
public fun interface TextLineShapes {
  /**
   * The laid-out line count is the list's size; each entry is the most characters that line's
   * placeholder may have. Null when the widget did not report (the fold then uses the string's own
   * lines).
   */
  public fun lineLengths(nodeId: Id, propertyTag: Int, text: String): List<Int>?
}

/** How much of the strings the fold keeps. */
@KeliverFrameFoldApi
public enum class FrameFoldContent {
  /** An allowlist for strings: only `cached()` nodes' strings survive, the rest become placeholders. */
  Shell,

  /** Every value is kept, except `notCached()` subtrees and `@NeverCached` values. */
  Whole,
}

@KeliverFrameFoldApi
public sealed interface FoldResult {
  /** The marked subtree, as a snapshot a fresh host can apply. */
  public class Folded(
    public val snapshot: SnapshotChangeList,
    public val content: FrameFoldContent,
    public val maxAgeMillis: Long,
    public val ready: Boolean,
    public val paneTitle: String,
  ) : FoldResult

  /**
   * Nothing may be recorded, for [reason]: `inconsistent` (a batch the fold could not follow),
   * `noMark`, `multipleMarks`, `notRootChild`, `notCachedRoot`, `notReplayable:widget:<tag>` or
   * `notReplayable:modifier:<tag>`.
   */
  public class Blocked(public val reason: String) : FoldResult
}
