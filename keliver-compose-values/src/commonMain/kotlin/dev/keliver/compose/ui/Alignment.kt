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
package dev.keliver.compose.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.wire.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The guest mirror of `androidx.compose.ui.Alignment`, as biases (Compose's named alignments are
 * biases too). Wire: `[h,v]` for an [Alignment], a number for [Horizontal] and [Vertical].
 */
@Immutable
@Serializable(with = AlignmentCodec::class)
public interface Alignment {
  public val horizontalBias: Float
  public val verticalBias: Float

  @Immutable
  @Serializable(with = HorizontalAlignmentCodec::class)
  public interface Horizontal {
    public val bias: Float
  }

  @Immutable
  @Serializable(with = VerticalAlignmentCodec::class)
  public interface Vertical {
    public val bias: Float
  }

  public companion object {
    @Stable public val TopStart: Alignment = BiasAlignment(-1f, -1f)

    @Stable public val TopCenter: Alignment = BiasAlignment(0f, -1f)

    @Stable public val TopEnd: Alignment = BiasAlignment(1f, -1f)

    @Stable public val CenterStart: Alignment = BiasAlignment(-1f, 0f)

    @Stable public val Center: Alignment = BiasAlignment(0f, 0f)

    @Stable public val CenterEnd: Alignment = BiasAlignment(1f, 0f)

    @Stable public val BottomStart: Alignment = BiasAlignment(-1f, 1f)

    @Stable public val BottomCenter: Alignment = BiasAlignment(0f, 1f)

    @Stable public val BottomEnd: Alignment = BiasAlignment(1f, 1f)

    @Stable public val Top: Vertical = BiasAlignment.Vertical(-1f)

    @Stable public val CenterVertically: Vertical = BiasAlignment.Vertical(0f)

    @Stable public val Bottom: Vertical = BiasAlignment.Vertical(1f)

    @Stable public val Start: Horizontal = BiasAlignment.Horizontal(-1f)

    @Stable public val CenterHorizontally: Horizontal = BiasAlignment.Horizontal(0f)

    @Stable public val End: Horizontal = BiasAlignment.Horizontal(1f)
  }
}

@Immutable
public data class BiasAlignment(override val horizontalBias: Float, override val verticalBias: Float) : Alignment {
  @Immutable
  public data class Horizontal(override val bias: Float) : Alignment.Horizontal

  @Immutable
  public data class Vertical(override val bias: Float) : Alignment.Vertical
}

public object AlignmentCodec : JsonCodec<Alignment>("dev.keliver.compose.Alignment") {
  override fun toJson(value: Alignment): JsonElement = JsonArray(listOf(JsonPrimitive(value.horizontalBias), JsonPrimitive(value.verticalBias)))
  override fun fromJson(json: JsonElement): Alignment = json.jsonArray.let { BiasAlignment(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float) }
}

public object HorizontalAlignmentCodec : JsonCodec<Alignment.Horizontal>("dev.keliver.compose.Alignment.Horizontal") {
  override fun toJson(value: Alignment.Horizontal): JsonElement = JsonPrimitive(value.bias)
  override fun fromJson(json: JsonElement): Alignment.Horizontal = BiasAlignment.Horizontal(json.jsonPrimitive.float)
}

public object VerticalAlignmentCodec : JsonCodec<Alignment.Vertical>("dev.keliver.compose.Alignment.Vertical") {
  override fun toJson(value: Alignment.Vertical): JsonElement = JsonPrimitive(value.bias)
  override fun fromJson(json: JsonElement): Alignment.Vertical = BiasAlignment.Vertical(json.jsonPrimitive.float)
}
