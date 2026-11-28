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
package dev.keliver.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the guest's cached-frame marker modifiers (`FrameCacheTags`) said about one node. It lives in
 * keliver-protocol so the recorder (NF5 A3's `ChangeFolder` in keliver-treehouse-host) can read the
 * raw [ModifierChange] elements itself: the host adapter drops the reserved tags before widget
 * dispatch and keeps nothing.
 *
 * A marker that cannot be read (a newer guest's, or malformed) is dropped and the node is simply
 * unmarked: a frame is then never recorded, which is the safe side (I1).
 */
@KeliverFrameMarksApi
public class FrameMarks internal constructor(
  /** The `cachedFrame` marker on this node, if any. */
  public val cachedFrame: CachedFrameMark?,
  /** `Modifier.cached()` is on this node. */
  public val cached: Boolean,
  /** `Modifier.notCached()` is on this node. */
  public val notCached: Boolean,
) {
  override fun toString(): String =
    "FrameMarks(cachedFrame=$cachedFrame, cached=$cached, notCached=$notCached)"

  public companion object {
    /** Reads the reserved elements among [elements] (others are ignored); null when none is understood. */
    public fun read(elements: List<ModifierElement>): FrameMarks? {
      var cachedFrame: CachedFrameMark? = null
      var cached = false
      var notCached = false
      for (element in elements) {
        if (!FrameCacheTags.isReserved(element.tag.value)) continue
        when (element.tag.value) {
          FrameCacheTags.CACHED_FRAME -> cachedFrame = CachedFrameMark.read(element.value) ?: cachedFrame
          FrameCacheTags.CACHED -> cached = true
          FrameCacheTags.NOT_CACHED -> notCached = true
          else -> Unit // Reserved, but from a newer guest: consumed, not understood.
        }
      }
      if (cachedFrame == null && !cached && !notCached) return null
      return FrameMarks(cachedFrame, cached, notCached)
    }
  }
}

@KeliverFrameMarksApi
public class CachedFrameMark internal constructor(
  /** `"shell"` or `"whole"`. */
  public val content: String,
  public val maxAgeMillis: Long,
  public val ready: Boolean,
  public val paneTitle: String,
) {
  override fun toString(): String =
    "CachedFrameMark(content=$content, maxAgeMillis=$maxAgeMillis, ready=$ready, paneTitle=$paneTitle)"

  public companion object {
    public fun read(value: JsonElement): CachedFrameMark? {
      val obj = value as? JsonObject ?: return null
      val content = (obj["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
      if (content != "shell" && content != "whole") return null
      val maxAge = (obj["maxAgeMillis"] as? JsonPrimitive)?.longOrNull ?: return null
      val ready = (obj["ready"] as? JsonPrimitive)?.booleanOrNull ?: return null
      val paneTitle = (obj["paneTitle"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
      return CachedFrameMark(content, maxAge, ready, paneTitle)
    }
  }
}
