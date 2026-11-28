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
package dev.keliver.calls.composeui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.keliver.compose.ui.graphics.ColorCodec
import dev.keliver.compose.ui.unit.DpCodec
import dev.keliver.compose.ui.unit.TextUnitCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// The host half of the hand codecs that stay (docs/COMPOSE_GENERATOR_DESIGN.md §6): the arithmetic value
// classes, written once. The generated replay calls these for every Color, Dp, TextUnit, Offset and Size
// argument.

/** A literal colour. A theme reference inside a call record is MS4-14's (the theme snapshot). */
internal fun decodeColor(j: JsonElement): Color {
  val k = ColorCodec.fromJson(j)
  require(k.referenceName == null) { "a theme colour inside a call record needs the theme snapshot (MS4-14): ${k.referenceName}" }
  return if (k == dev.keliver.compose.ui.graphics.Color.Unspecified) Color.Unspecified else Color(k.argb)
}

internal fun decodeDp(j: JsonElement): Dp = Dp(DpCodec.fromJson(j).value)

internal fun decodeTextUnit(j: JsonElement): TextUnit {
  val k = TextUnitCodec.fromJson(j)
  return when {
    k.isSp -> k.value.sp
    k.isEm -> k.value.em
    else -> TextUnit.Unspecified
  }
}

/** A float that is not finite travels as `"inf"`, `"-inf"` or null (NaN): JSON has no such number. */
private fun JsonElement.finiteOrNot(): Float = when {
  this is JsonNull -> Float.NaN
  jsonPrimitive.isString && jsonPrimitive.content == "inf" -> Float.POSITIVE_INFINITY
  jsonPrimitive.isString && jsonPrimitive.content == "-inf" -> Float.NEGATIVE_INFINITY
  else -> jsonPrimitive.float
}

internal fun decodeOffset(j: JsonElement): Offset = j.jsonArray.let { Offset(it[0].finiteOrNot(), it[1].finiteOrNot()) }

internal fun decodeSize(j: JsonElement): Size = j.jsonArray.let { Size(it[0].finiteOrNot(), it[1].finiteOrNot()) }

@Suppress("unused")
private val keepImport: JsonPrimitive? = null
