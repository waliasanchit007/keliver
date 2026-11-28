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
package dev.keliver.calls.compose.ui.geometry

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// C1-lite (docs/MS4_PLAN.md decisions): the arithmetic value classes the call records need that
// keliver-compose-values does not have. They are hand codecs, like Dp and Color: a pair of floats on
// the wire, `[x,y]`. MS4-13 moves them into keliver-compose-values with the root rename.

/** The guest's `androidx.compose.ui.geometry.Offset`. Wire: `[x,y]`; the unspecified value is `[null,null]`. */
@Immutable
public class Offset(public val x: Float, public val y: Float) {
  public fun toJson(): JsonElement = pairToJson(x, y)

  override fun equals(other: Any?): Boolean = other is Offset && other.x.toRawBits() == x.toRawBits() && other.y.toRawBits() == y.toRawBits()
  override fun hashCode(): Int = x.hashCode() * 31 + y.hashCode()
  override fun toString(): String = if (this == Unspecified) "Offset.Unspecified" else "Offset($x, $y)"

  public companion object {
    public val Zero: Offset = Offset(0f, 0f)
    public val Infinite: Offset = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    public val Unspecified: Offset = Offset(Float.NaN, Float.NaN)
  }
}

/** The guest's `androidx.compose.ui.geometry.Size`. Wire: `[width,height]`. */
@Immutable
public class Size(public val width: Float, public val height: Float) {
  public fun toJson(): JsonElement = pairToJson(width, height)

  override fun equals(other: Any?): Boolean = other is Size && other.width.toRawBits() == width.toRawBits() && other.height.toRawBits() == height.toRawBits()
  override fun hashCode(): Int = width.hashCode() * 31 + height.hashCode()
  override fun toString(): String = if (this == Unspecified) "Size.Unspecified" else "Size($width, $height)"

  public companion object {
    public val Zero: Size = Size(0f, 0f)
    public val Unspecified: Size = Size(Float.NaN, Float.NaN)
  }
}

// A float that is not finite is not a JSON number: infinity travels as the string "inf", NaN as null.
private fun pairToJson(a: Float, b: Float): JsonElement = JsonArray(listOf(floatToJson(a), floatToJson(b)))

private fun floatToJson(f: Float): JsonElement = when {
  f.isNaN() -> kotlinx.serialization.json.JsonNull
  f == Float.POSITIVE_INFINITY -> JsonPrimitive("inf")
  f == Float.NEGATIVE_INFINITY -> JsonPrimitive("-inf")
  else -> JsonPrimitive(f)
}
