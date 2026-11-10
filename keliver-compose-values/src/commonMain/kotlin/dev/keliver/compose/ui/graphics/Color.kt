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
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The guest mirror of `androidx.compose.ui.graphics.Color`: a 64-bit value class, as in Compose.
 *
 * - An sRGB colour is packed as Compose packs it (ARGB in the high 32 bits, colour space 0).
 * - [Unspecified] has its own value.
 * - A theme reference (`MaterialTheme.colorScheme.primary`, `LocalContentColor.current`) uses the
 *   colour-space bits Compose never assigns ([REF_SPACE]), with the reference's index and an
 *   alpha byte, so `.copy(alpha = …)` works on it and the host resolves it under its own theme
 *   (docs/COMPOSE_GENERATOR_DESIGN.md §7).
 *
 * Wire: an ARGB number, `"u"`, or `{"ref":"colorScheme.primary","a":0.5}`.
 */
@Immutable
@JvmInline
@Serializable(with = ColorCodec::class)
public value class Color(public val value: ULong) {
  public val isReference: Boolean get() = (value and 0x3FUL) == REF_SPACE

  /** The reference's name ([ColorRefs]), or null for a literal colour. */
  public val referenceName: String? get() = if (isReference) ColorRefs.names[((value shr 16) and 0xFFFFUL).toInt()] else null
  public val alpha: Float get() = when {
    isReference -> ((value shr 8) and 0xFFUL).toInt() / 255f
    this == Unspecified -> 0f
    else -> ((argb ushr 24) and 0xFF) / 255f
  }
  public val red: Float get() = ((argb shr 16) and 0xFF) / 255f
  public val green: Float get() = ((argb shr 8) and 0xFF) / 255f
  public val blue: Float get() = (argb and 0xFF) / 255f

  /** The sRGB ARGB int of a literal colour. */
  public val argb: Int get() = (value shr 32).toInt()

  @Stable
  public fun copy(
    alpha: Float = this.alpha,
    red: Float = this.red,
    green: Float = this.green,
    blue: Float = this.blue,
  ): Color = when {
    isReference -> reference(referenceName!!, alpha)
    else -> Color(red = red, green = green, blue = blue, alpha = alpha)
  }

  override fun toString(): String = when {
    isReference -> "Color(ref=$referenceName, alpha=$alpha)"
    this == Unspecified -> "Color.Unspecified"
    else -> "Color(0x${argb.toUInt().toString(16).padStart(8, '0').uppercase()})"
  }

  public companion object {
    @Stable public val Black: Color = Color(0xFF000000)

    @Stable public val DarkGray: Color = Color(0xFF444444)

    @Stable public val Gray: Color = Color(0xFF888888)

    @Stable public val LightGray: Color = Color(0xFFCCCCCC)

    @Stable public val White: Color = Color(0xFFFFFFFF)

    @Stable public val Red: Color = Color(0xFFFF0000)

    @Stable public val Green: Color = Color(0xFF00FF00)

    @Stable public val Blue: Color = Color(0xFF0000FF)

    @Stable public val Yellow: Color = Color(0xFFFFFF00)

    @Stable public val Cyan: Color = Color(0xFF00FFFF)

    @Stable public val Magenta: Color = Color(0xFFFF00FF)

    @Stable public val Transparent: Color = Color(0x00000000)

    @Stable public val Unspecified: Color = Color(0x10UL)

    internal const val REF_SPACE_INT: Int = 0x3F

    /** A reference to a host theme value by [name] (one of [ColorRefs.names]). */
    public fun reference(name: String, alpha: Float = 1f): Color {
      val index = ColorRefs.names.indexOf(name)
      require(index >= 0) { "unknown colour reference $name" }
      val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().toULong()
      return Color((index.toULong() shl 16) or (a shl 8) or REF_SPACE)
    }
  }
}

private val REF_SPACE: ULong = 0x3FUL

@Stable public fun Color(color: Long): Color = Color((color.toULong() and 0xFFFFFFFFUL) shl 32)

@Stable public fun Color(color: Int): Color = Color((color.toULong() and 0xFFFFFFFFUL) shl 32)

@Stable
public fun Color(red: Int, green: Int, blue: Int, alpha: Int = 0xFF): Color = Color(
  ((alpha and 0xFF) shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF),
)

@Stable
public fun Color(red: Float, green: Float, blue: Float, alpha: Float = 1f): Color = Color(
  (red * 255f).roundToInt(),
  (green * 255f).roundToInt(),
  (blue * 255f).roundToInt(),
  (alpha * 255f).roundToInt(),
)

@Stable public val Color.isSpecified: Boolean get() = this != Color.Unspecified

@Stable public val Color.isUnspecified: Boolean get() = this == Color.Unspecified

/** Shadows' default colour, as `androidx.compose.ui.graphics.DefaultShadowColor`. */
public val DefaultShadowColor: Color = Color(0xFF000000)

/** The theme values a [Color] can refer to; the index is part of the guest's packing only. */
public object ColorRefs {
  public val names: List<String> = listOf(
    "LocalContentColor",
    "colorScheme.primary", "colorScheme.onPrimary", "colorScheme.primaryContainer", "colorScheme.onPrimaryContainer",
    "colorScheme.inversePrimary", "colorScheme.secondary", "colorScheme.onSecondary", "colorScheme.secondaryContainer",
    "colorScheme.onSecondaryContainer", "colorScheme.tertiary", "colorScheme.onTertiary", "colorScheme.tertiaryContainer",
    "colorScheme.onTertiaryContainer", "colorScheme.background", "colorScheme.onBackground", "colorScheme.surface",
    "colorScheme.onSurface", "colorScheme.surfaceVariant", "colorScheme.onSurfaceVariant", "colorScheme.surfaceTint",
    "colorScheme.inverseSurface", "colorScheme.inverseOnSurface", "colorScheme.error", "colorScheme.onError",
    "colorScheme.errorContainer", "colorScheme.onErrorContainer", "colorScheme.outline", "colorScheme.outlineVariant",
    "colorScheme.scrim", "colorScheme.surfaceBright", "colorScheme.surfaceContainer", "colorScheme.surfaceContainerHigh",
    "colorScheme.surfaceContainerHighest", "colorScheme.surfaceContainerLow", "colorScheme.surfaceContainerLowest",
    "colorScheme.surfaceDim",
  )
}

public object ColorCodec : JsonCodec<Color>("dev.keliver.compose.Color") {
  override fun toJson(value: Color): JsonElement = when {
    value.isReference -> buildJsonObject {
      put("ref", value.referenceName)
      if (value.alpha != 1f) put("a", value.alpha)
    }
    value == Color.Unspecified -> JsonPrimitive("u")
    else -> JsonPrimitive(value.argb.toLong() and 0xFFFFFFFFL)
  }

  override fun fromJson(json: JsonElement): Color = when {
    json is JsonObject -> Color.reference(json["ref"]!!.jsonPrimitive.content, json["a"]?.jsonPrimitive?.float ?: 1f)
    json.jsonPrimitive.isString -> Color.Unspecified
    else -> Color(json.jsonPrimitive.long)
  }
}
