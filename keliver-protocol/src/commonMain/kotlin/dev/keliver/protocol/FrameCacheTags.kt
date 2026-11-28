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

/**
 * The reserved framework modifier tags of the cached first frame (docs/SHOW_BEFORE_COMPUTE.md 2.2).
 *
 * The range is negative, as schema modifier tags cannot be (only the reserved reuse tag is), so no
 * schema can collide with it. The host consumes every tag in [range] before widget dispatch; they
 * never reach a widget or the mismatch handler.
 *
 * A tag never changes and is never reused: a new marker takes the next free number, and a retired
 * one stays here. `FrameCacheTagsTest` locks the values.
 */
public object FrameCacheTags {
  /** `CachedFrame(policy, ready, paneTitle)` on a screen's root. Value: an object. */
  public const val CACHED_FRAME: Int = -4_600_001

  /** `Modifier.cached()`: the node keeps its text in a `Shell` frame. No value. */
  public const val CACHED: Int = -4_600_002

  /** `Modifier.notCached()`: the node's subtree is never stored. No value. */
  public const val NOT_CACHED: Int = -4_600_003

  /** Every tag from [RANGE_START] to [RANGE_END] is reserved, assigned or not. */
  public const val RANGE_START: Int = -4_600_099
  public const val RANGE_END: Int = -4_600_000

  public val range: IntRange = RANGE_START..RANGE_END

  /** True for a tag in the reserved range, whether or not this version knows it. */
  public fun isReserved(tag: Int): Boolean = tag in RANGE_START..RANGE_END
}
