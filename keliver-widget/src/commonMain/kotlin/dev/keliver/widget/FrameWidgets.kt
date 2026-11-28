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
package dev.keliver.widget

/**
 * Denotes the two interfaces a host widget implements for NF5's cached first frame
 * ([ScrollOrigin], [TextLines]). They are written for the recorder (A3c) and the widgets that
 * implement them (A4a), and their shape may change until those land, so they are not stable across
 * versions.
 *
 * @suppress
 */
@RequiresOptIn("The cached-frame widget hooks are internal to NF5 and not stable across versions")
public annotation class KeliverFrameWidgetApi

/**
 * A scrolling host widget says whether it is at its origin (docs/SHOW_BEFORE_COMPUTE.md 2.3,
 * condition 5): a lazy list or grid at its first item with offset 0, a scrolling column or row at
 * offset 0, a pager at its first page with no offset. It reads this from the widget's own state, so
 * a position the guest restored counts too.
 *
 * The recorder asks it on the UI thread at the `ready` edge. A widget whose tag the schema marks
 * `@Scrolls` and that does not implement this interface blocks the recording (it fails closed).
 */
@KeliverFrameWidgetApi
public interface ScrollOrigin {
  public val atOrigin: Boolean
}

/** One laid-out line of a text: how wide it came out and how many characters it holds. */
@KeliverFrameWidgetApi
public class TextLine(
  /** The line's laid-out width in pixels (`getLineRight(i) - getLineLeft(i)`). */
  public val widthPx: Float,
  /** The line's character count (`getLineEnd(i) - getLineStart(i)`). */
  public val characterCount: Int,
) {
  override fun equals(other: Any?): Boolean =
    other is TextLine && other.widthPx == widthPx && other.characterCount == characterCount

  override fun hashCode(): Int = 31 * widthPx.hashCode() + characterCount

  override fun toString(): String = "TextLine(widthPx=$widthPx, characterCount=$characterCount)"
}

/**
 * A text-bearing host widget says how each of its text properties laid out (docs/SHOW_BEFORE_COMPUTE.md
 * 5.2), so a `Shell` placeholder has the same lines and the swap does not move what is below it.
 * Host widgets read it from `onTextLayout`.
 *
 * The recorder asks it on the UI thread at the `ready` edge. A widget that has text to replace and
 * does not implement this interface blocks the recording (it fails closed).
 */
@KeliverFrameWidgetApi
public interface TextLines {
  /**
   * The lines [propertyTag]'s text laid out to, or null when the widget has not laid that property
   * out (the recorder then keeps the string's own lines).
   */
  public fun lines(propertyTag: Int): List<TextLine>?
}
