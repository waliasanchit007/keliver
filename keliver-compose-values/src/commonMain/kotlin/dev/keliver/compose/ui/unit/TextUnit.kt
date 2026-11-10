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
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.compose.wire.toCompact
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** The type of a [TextUnit], as in Compose. */
@JvmInline
public value class TextUnitType(internal val type: Int) {
  override fun toString(): String = when (type) {
    1 -> "Sp"
    2 -> "Em"
    else -> "Unspecified"
  }
  public companion object {
    public val Unspecified: TextUnitType = TextUnitType(0)
    public val Sp: TextUnitType = TextUnitType(1)
    public val Em: TextUnitType = TextUnitType(2)
  }
}

/**
 * The guest mirror of `androidx.compose.ui.unit.TextUnit`. Wire: `"16sp"`, `"1.2em"`, or `"u"`
 * for [Unspecified].
 */
@Immutable
@JvmInline
@Serializable(with = TextUnitCodec::class)
public value class TextUnit internal constructor(internal val packed: Long) {
  public val type: TextUnitType get() = TextUnitType((packed ushr 32).toInt())
  public val value: Float get() = Float.fromBits((packed and 0xFFFFFFFFL).toInt())
  public val isSp: Boolean get() = type == TextUnitType.Sp
  public val isEm: Boolean get() = type == TextUnitType.Em

  @Stable public operator fun times(other: Float): TextUnit = pack(type, value * other)

  @Stable public operator fun times(other: Int): TextUnit = pack(type, value * other)

  @Stable public operator fun div(other: Float): TextUnit = pack(type, value / other)
  override fun toString(): String = when (type) {
    TextUnitType.Sp -> "$value.sp"
    TextUnitType.Em -> "$value.em"
    else -> "Unspecified"
  }

  public companion object {
    @Stable public val Unspecified: TextUnit = pack(TextUnitType.Unspecified, Float.NaN)
  }
}

internal fun pack(type: TextUnitType, value: Float): TextUnit =
  TextUnit((type.type.toLong() shl 32) or (value.toRawBits().toLong() and 0xFFFFFFFFL))

@Stable public fun TextUnit(value: Float, type: TextUnitType): TextUnit = pack(type, value)

@Stable public val TextUnit.isSpecified: Boolean get() = type != TextUnitType.Unspecified

@Stable public val TextUnit.isUnspecified: Boolean get() = type == TextUnitType.Unspecified

@Stable public val Int.sp: TextUnit get() = pack(TextUnitType.Sp, toFloat())

@Stable public val Float.sp: TextUnit get() = pack(TextUnitType.Sp, this)

@Stable public val Double.sp: TextUnit get() = pack(TextUnitType.Sp, toFloat())

@Stable public val Int.em: TextUnit get() = pack(TextUnitType.Em, toFloat())

@Stable public val Float.em: TextUnit get() = pack(TextUnitType.Em, this)

@Stable public val Double.em: TextUnit get() = pack(TextUnitType.Em, toFloat())

public object TextUnitCodec : JsonCodec<TextUnit>("dev.keliver.compose.TextUnit") {
  override fun toJson(value: TextUnit): JsonElement = JsonPrimitive(
    when (value.type) {
      TextUnitType.Sp -> "${value.value.toCompact()}sp"
      TextUnitType.Em -> "${value.value.toCompact()}em"
      else -> "u"
    },
  )

  override fun fromJson(json: JsonElement): TextUnit {
    val s = json.jsonPrimitive.content
    return when {
      s.endsWith("sp") -> pack(TextUnitType.Sp, s.dropLast(2).toFloat())
      s.endsWith("em") -> pack(TextUnitType.Em, s.dropLast(2).toFloat())
      else -> TextUnit.Unspecified
    }
  }
}
