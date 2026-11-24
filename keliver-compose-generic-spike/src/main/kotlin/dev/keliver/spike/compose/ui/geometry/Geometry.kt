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
package dev.keliver.spike.compose.ui.geometry

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Arithmetic value classes stay hand codecs (docs/COMPOSE_GENERIC_MECHANISMS.md): the guest does
// arithmetic on them, so they are values, not recorded calls. Minimal for the spike.

@Immutable
public data class Offset(val x: Float, val y: Float) {
  public operator fun plus(o: Offset): Offset = Offset(x + o.x, y + o.y)
  public operator fun minus(o: Offset): Offset = Offset(x - o.x, y - o.y)
  public operator fun times(f: Float): Offset = Offset(x * f, y * f)
  public fun toJson(): JsonElement = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y)))

  public companion object {
    public val Zero: Offset = Offset(0f, 0f)
  }
}

@Immutable
public data class Size(val width: Float, val height: Float) {
  public val minDimension: Float get() = minOf(width, height)
  public val center: Offset get() = Offset(width / 2f, height / 2f)
  public fun toJson(): JsonElement = JsonArray(listOf(JsonPrimitive(width), JsonPrimitive(height)))
}
