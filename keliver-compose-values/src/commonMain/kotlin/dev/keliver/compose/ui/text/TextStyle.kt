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
package dev.keliver.compose.ui.text

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.graphics.ColorCodec
import dev.keliver.compose.ui.text.font.FontFamily
import dev.keliver.compose.ui.text.font.FontFamilyCodec
import dev.keliver.compose.ui.text.font.FontStyle
import dev.keliver.compose.ui.text.font.FontStyleCodec
import dev.keliver.compose.ui.text.font.FontWeight
import dev.keliver.compose.ui.text.font.FontWeightCodec
import dev.keliver.compose.ui.text.style.TextAlign
import dev.keliver.compose.ui.text.style.TextAlignCodec
import dev.keliver.compose.ui.text.style.TextDecoration
import dev.keliver.compose.ui.text.style.TextDecorationCodec
import dev.keliver.compose.ui.unit.TextUnit
import dev.keliver.compose.ui.unit.TextUnitCodec
import dev.keliver.compose.wire.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The guest mirror of `androidx.compose.ui.text.TextStyle` (the fields Gate A carries). A style
 * is a host reference ([reference]: `"LocalTextStyle"`, `"typography.titleMedium"`) plus the
 * fields the guest set; the host takes the reference and merges the set fields over it, so
 * `MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)` keeps following the
 * host's theme (docs/COMPOSE_GENERATOR_DESIGN.md §6, §7). A field left at its unspecified value
 * is not set.
 *
 * Wire: `{"ref":"typography.titleMedium","set":{"fontWeight":700}}`.
 */
@Immutable
@Serializable(with = TextStyleCodec::class)
public class TextStyle(
  public val color: Color = Color.Unspecified,
  public val fontSize: TextUnit = TextUnit.Unspecified,
  public val fontWeight: FontWeight? = null,
  public val fontStyle: FontStyle? = null,
  public val fontFamily: FontFamily? = null,
  public val letterSpacing: TextUnit = TextUnit.Unspecified,
  public val textDecoration: TextDecoration? = null,
  public val textAlign: TextAlign = TextAlign.Unspecified,
  public val lineHeight: TextUnit = TextUnit.Unspecified,
) {
  /** The host value this style starts from, or null for a style built from scratch. */
  public var reference: String? = null
    private set

  @Stable
  public fun copy(
    color: Color = this.color,
    fontSize: TextUnit = this.fontSize,
    fontWeight: FontWeight? = this.fontWeight,
    fontStyle: FontStyle? = this.fontStyle,
    fontFamily: FontFamily? = this.fontFamily,
    letterSpacing: TextUnit = this.letterSpacing,
    textDecoration: TextDecoration? = this.textDecoration,
    textAlign: TextAlign = this.textAlign,
    lineHeight: TextUnit = this.lineHeight,
  ): TextStyle = TextStyle(color, fontSize, fontWeight, fontStyle, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight)
    .also { it.reference = reference }

  /** Compose's `merge`: [other]'s specified fields win. */
  @Stable
  public fun merge(other: TextStyle?): TextStyle {
    if (other == null) return this
    return TextStyle(
      color = if (other.color != Color.Unspecified) other.color else color,
      fontSize = if (other.fontSize != TextUnit.Unspecified) other.fontSize else fontSize,
      fontWeight = other.fontWeight ?: fontWeight,
      fontStyle = other.fontStyle ?: fontStyle,
      fontFamily = other.fontFamily ?: fontFamily,
      letterSpacing = if (other.letterSpacing != TextUnit.Unspecified) other.letterSpacing else letterSpacing,
      textDecoration = other.textDecoration ?: textDecoration,
      textAlign = if (other.textAlign != TextAlign.Unspecified) other.textAlign else textAlign,
      lineHeight = if (other.lineHeight != TextUnit.Unspecified) other.lineHeight else lineHeight,
    ).also { it.reference = reference }
  }

  override fun equals(other: Any?): Boolean = other is TextStyle && other.reference == reference &&
    other.color == color && other.fontSize == fontSize && other.fontWeight == fontWeight &&
    other.fontStyle == fontStyle && other.fontFamily == fontFamily && other.letterSpacing == letterSpacing &&
    other.textDecoration == textDecoration && other.textAlign == textAlign && other.lineHeight == lineHeight

  override fun hashCode(): Int = listOf(reference, color, fontSize, fontWeight, fontStyle, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight).hashCode()

  override fun toString(): String = "TextStyle(ref=$reference, color=$color, fontSize=$fontSize, fontWeight=$fontWeight, …)"

  public companion object {
    @Stable public val Default: TextStyle = TextStyle()

    /** A reference to a host style by [name] (`"LocalTextStyle"`, `"typography.bodyLarge"`, …). */
    public fun reference(name: String): TextStyle = TextStyle().also { it.reference = name }
  }
}

public object TextStyleCodec : JsonCodec<TextStyle>("dev.keliver.compose.TextStyle") {
  override fun toJson(value: TextStyle): JsonElement = buildJsonObject {
    value.reference?.let { put("ref", kotlinx.serialization.json.JsonPrimitive(it)) }
    val set = buildJsonObject {
      if (value.color != Color.Unspecified) put("color", ColorCodec.toJson(value.color))
      if (value.fontSize != TextUnit.Unspecified) put("fontSize", TextUnitCodec.toJson(value.fontSize))
      value.fontWeight?.let { put("fontWeight", FontWeightCodec.toJson(it)) }
      value.fontStyle?.let { put("fontStyle", FontStyleCodec.toJson(it)) }
      value.fontFamily?.let { put("fontFamily", FontFamilyCodec.toJson(it)) }
      if (value.letterSpacing != TextUnit.Unspecified) put("letterSpacing", TextUnitCodec.toJson(value.letterSpacing))
      value.textDecoration?.let { put("textDecoration", TextDecorationCodec.toJson(it)) }
      if (value.textAlign != TextAlign.Unspecified) put("textAlign", TextAlignCodec.toJson(value.textAlign))
      if (value.lineHeight != TextUnit.Unspecified) put("lineHeight", TextUnitCodec.toJson(value.lineHeight))
    }
    if (set.isNotEmpty()) put("set", set)
  }

  override fun fromJson(json: JsonElement): TextStyle {
    val o = json.jsonObject
    val s = (o["set"] as? JsonObject) ?: JsonObject(emptyMap())
    val style = TextStyle(
      color = s["color"]?.let(ColorCodec::fromJson) ?: Color.Unspecified,
      fontSize = s["fontSize"]?.let(TextUnitCodec::fromJson) ?: TextUnit.Unspecified,
      fontWeight = s["fontWeight"]?.let(FontWeightCodec::fromJson),
      fontStyle = s["fontStyle"]?.let(FontStyleCodec::fromJson),
      fontFamily = s["fontFamily"]?.let(FontFamilyCodec::fromJson),
      letterSpacing = s["letterSpacing"]?.let(TextUnitCodec::fromJson) ?: TextUnit.Unspecified,
      textDecoration = s["textDecoration"]?.let(TextDecorationCodec::fromJson),
      textAlign = s["textAlign"]?.let(TextAlignCodec::fromJson) ?: TextAlign.Unspecified,
      lineHeight = s["lineHeight"]?.let(TextUnitCodec::fromJson) ?: TextUnit.Unspecified,
    )
    val ref = o["ref"]?.jsonPrimitive?.content ?: return style
    return TextStyle.reference(ref).merge(style)
  }
}
