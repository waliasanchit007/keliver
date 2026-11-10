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
package dev.keliver.compose.foundation.layout

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.ui.Alignment
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.compose.wire.toCompact
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The guest mirror of `androidx.compose.foundation.layout.Arrangement`. A custom arrangement (per
 * layout code) is not possible; the named ones and `spacedBy` are values.
 *
 * Wire: `"start"`, `"end"`, `"center"`, `"top"`, `"bottom"`, `"sb"` (space between), `"sa"`
 * (around), `"se"` (evenly), or `{"sp":8}` / `{"sp":8,"a":-1}` for `spacedBy`.
 */
public object Arrangement {
  @Immutable
  @Serializable(with = HorizontalArrangementCodec::class)
  public interface Horizontal {
    public val code: String
    public val spacing: Dp get() = Dp(0f)
  }

  @Immutable
  @Serializable(with = VerticalArrangementCodec::class)
  public interface Vertical {
    public val code: String
    public val spacing: Dp get() = Dp(0f)
  }

  @Immutable
  public interface HorizontalOrVertical :
    Horizontal,
    Vertical {
    override val spacing: Dp get() = Dp(0f)
  }

  @Stable public val Start: Horizontal = Named("start")

  @Stable public val End: Horizontal = Named("end")

  @Stable public val Top: Vertical = Named("top")

  @Stable public val Bottom: Vertical = Named("bottom")

  @Stable public val Center: HorizontalOrVertical = Named("center")

  @Stable public val SpaceEvenly: HorizontalOrVertical = Named("se")

  @Stable public val SpaceBetween: HorizontalOrVertical = Named("sb")

  @Stable public val SpaceAround: HorizontalOrVertical = Named("sa")

  @Stable public fun spacedBy(space: Dp): HorizontalOrVertical = SpacedAligned(space, null)

  @Stable public fun spacedBy(space: Dp, alignment: Alignment.Horizontal): Horizontal = SpacedAligned(space, alignment.bias)

  @Stable public fun spacedBy(space: Dp, alignment: Alignment.Vertical): Vertical = SpacedAligned(space, alignment.bias)

  internal data class Named(override val code: String) : HorizontalOrVertical

  /** `spacedBy`; [bias] is the alignment's bias, or null for none. */
  public class SpacedAligned internal constructor(public val space: Dp, public val bias: Float?) : HorizontalOrVertical {
    override val code: String get() = "sp"
    override val spacing: Dp get() = space
    override fun equals(other: Any?): Boolean = other is SpacedAligned && other.space == space && other.bias == bias
    override fun hashCode(): Int = space.hashCode() * 31 + bias.hashCode()
  }

  internal fun toJson(code: String, value: Any): JsonElement =
    if (value is SpacedAligned) {
      buildJsonObject {
        put("sp", value.space.value.toCompact())
        value.bias?.let { put("a", it) }
      }
    } else {
      JsonPrimitive(code)
    }

  internal fun fromJson(json: JsonElement): HorizontalOrVertical =
    if (json is JsonObject) {
      SpacedAligned(Dp(json["sp"]!!.jsonPrimitive.float), json["a"]?.jsonPrimitive?.float)
    } else {
      Named(json.jsonPrimitive.content)
    }
}

public object HorizontalArrangementCodec : JsonCodec<Arrangement.Horizontal>("dev.keliver.compose.Arrangement.Horizontal") {
  override fun toJson(value: Arrangement.Horizontal): JsonElement = Arrangement.toJson(value.code, value)
  override fun fromJson(json: JsonElement): Arrangement.Horizontal = Arrangement.fromJson(json)
}

public object VerticalArrangementCodec : JsonCodec<Arrangement.Vertical>("dev.keliver.compose.Arrangement.Vertical") {
  override fun toJson(value: Arrangement.Vertical): JsonElement = Arrangement.toJson(value.code, value)
  override fun fromJson(json: JsonElement): Arrangement.Vertical = Arrangement.fromJson(json)
}
