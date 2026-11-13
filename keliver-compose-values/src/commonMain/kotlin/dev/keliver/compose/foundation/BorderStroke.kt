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
package dev.keliver.compose.foundation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.graphics.ColorCodec
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.wire.DefaultsCall
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.compose.wire.floatOrUnspecified
import dev.keliver.compose.wire.toCompact
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The guest mirror of `androidx.compose.foundation.BorderStroke` with a solid colour (a brush is
 * Gate B). A border a `*Defaults` factory makes (`ButtonDefaults.outlinedButtonBorder(enabled)`) is
 * that call, made by the host ([call]).
 *
 * Wire: `{"w":1,"c":<color>}`, or a call record (`{"f":…}`).
 */
@Immutable
@Serializable(with = BorderStrokeCodec::class)
public class BorderStroke private constructor(
  public val width: Dp,
  public val color: Color,
  /** Made by the host's real `*Defaults` factory; [width] and [color] are not known in the guest. */
  public val call: DefaultsCall?,
) {
  public constructor(width: Dp, color: Color) : this(width, color, null)

  @Stable
  public fun copy(width: Dp = this.width, color: Color = this.color): BorderStroke = BorderStroke(width, color)

  override fun equals(other: Any?): Boolean =
    other is BorderStroke && other.call == call && (call != null || other.width == width && other.color == color)
  override fun hashCode(): Int = call?.hashCode() ?: (width.hashCode() * 31 + color.hashCode())
  override fun toString(): String = call?.let { "BorderStroke($it)" } ?: "BorderStroke(width=$width, color=$color)"

  public companion object {
    /** A border the host makes by calling a generated `*Defaults` factory. */
    public fun ofCall(call: DefaultsCall): BorderStroke = BorderStroke(Dp.Unspecified, Color.Unspecified, call)

    /** An explicit `null` where the parameter's default is a border (design §5). */
    public val ExplicitNull: BorderStroke = ofCall(DefaultsCall.ExplicitNull)
  }
}

public object BorderStrokeCodec : JsonCodec<BorderStroke>("dev.keliver.compose.BorderStroke") {
  override fun toJson(value: BorderStroke): JsonElement = value.call?.toJson() ?: buildJsonObject {
    put("w", JsonPrimitive(value.width.value.toCompact()))
    put("c", ColorCodec.toJson(value.color))
  }

  override fun fromJson(json: JsonElement): BorderStroke {
    if (DefaultsCall.isCall(json)) return BorderStroke.ofCall(DefaultsCall.fromJson(json))
    val o = json.jsonObject
    return BorderStroke(Dp(o.getValue("w").floatOrUnspecified()), ColorCodec.fromJson(o.getValue("c")))
  }
}
