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
package dev.keliver.compose.ui.graphics

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.wire.JsonCodec
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The guest mirror of `androidx.compose.ui.graphics.ColorFilter`: [tint] only (a colour matrix and
 * lighting filter are Gate B). Wire: `{"tint":<color>,"bm":<blend mode>}`.
 */
@Immutable
@Serializable(with = ColorFilterCodec::class)
public class ColorFilter internal constructor(public val color: Color, public val blendMode: BlendMode) {
  override fun equals(other: Any?): Boolean = other is ColorFilter && other.color == color && other.blendMode == blendMode
  override fun hashCode(): Int = color.hashCode() * 31 + blendMode.hashCode()
  override fun toString(): String = "ColorFilter.tint($color, $blendMode)"

  public companion object {
    @Stable
    public fun tint(color: Color, blendMode: BlendMode = BlendMode.SrcIn): ColorFilter = ColorFilter(color, blendMode)
  }
}

public object ColorFilterCodec : JsonCodec<ColorFilter>("dev.keliver.compose.ColorFilter") {
  override fun toJson(value: ColorFilter): JsonElement = buildJsonObject {
    put("tint", ColorCodec.toJson(value.color))
    put("bm", value.blendMode.value)
  }

  override fun fromJson(json: JsonElement): ColorFilter {
    val o = json.jsonObject
    return ColorFilter(ColorCodec.fromJson(o.getValue("tint")), BlendMode(o.getValue("bm").jsonPrimitive.int))
  }
}

/** The guest mirror of `androidx.compose.ui.graphics.BlendMode`: Compose's own int values. */
@Immutable
@JvmInline
public value class BlendMode internal constructor(public val value: Int) {
  override fun toString(): String = "BlendMode($value)"

  public companion object {
    @Stable public val Clear: BlendMode = BlendMode(0)

    @Stable public val Src: BlendMode = BlendMode(1)

    @Stable public val Dst: BlendMode = BlendMode(2)

    @Stable public val SrcOver: BlendMode = BlendMode(3)

    @Stable public val DstOver: BlendMode = BlendMode(4)

    @Stable public val SrcIn: BlendMode = BlendMode(5)

    @Stable public val DstIn: BlendMode = BlendMode(6)

    @Stable public val SrcOut: BlendMode = BlendMode(7)

    @Stable public val DstOut: BlendMode = BlendMode(8)

    @Stable public val SrcAtop: BlendMode = BlendMode(9)

    @Stable public val DstAtop: BlendMode = BlendMode(10)

    @Stable public val Xor: BlendMode = BlendMode(11)

    @Stable public val Plus: BlendMode = BlendMode(12)

    @Stable public val Modulate: BlendMode = BlendMode(13)

    @Stable public val Screen: BlendMode = BlendMode(14)

    @Stable public val Overlay: BlendMode = BlendMode(15)

    @Stable public val Darken: BlendMode = BlendMode(16)

    @Stable public val Lighten: BlendMode = BlendMode(17)

    @Stable public val ColorDodge: BlendMode = BlendMode(18)

    @Stable public val ColorBurn: BlendMode = BlendMode(19)

    @Stable public val Hardlight: BlendMode = BlendMode(20)

    @Stable public val Softlight: BlendMode = BlendMode(21)

    @Stable public val Difference: BlendMode = BlendMode(22)

    @Stable public val Exclusion: BlendMode = BlendMode(23)

    @Stable public val Multiply: BlendMode = BlendMode(24)

    @Stable public val Hue: BlendMode = BlendMode(25)

    @Stable public val Saturation: BlendMode = BlendMode(26)

    @Stable public val Color: BlendMode = BlendMode(27)

    @Stable public val Luminosity: BlendMode = BlendMode(28)
  }
}
