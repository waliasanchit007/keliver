/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.compose.ui.unit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * The guest mirror of `androidx.compose.ui.unit.IntSize`. Gate B-e: only what animation specs name
 * (`IntSize.VisibilityThreshold` in `animateContentSize`'s and `expandIn`'s defaults). It never
 * travels on its own; an animation spec's visibility threshold carries it as `[w,h]`.
 */
@Immutable
public data class IntSize(public val width: Int, public val height: Int) {
  public companion object {
    @Stable public val Zero: IntSize = IntSize(0, 0)
  }
}

/** The guest mirror of `androidx.compose.ui.unit.IntOffset` (Gate B-e: what slide transitions name). */
@Immutable
public data class IntOffset(public val x: Int, public val y: Int) {
  public companion object {
    @Stable public val Zero: IntOffset = IntOffset(0, 0)
  }
}
