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
package dev.keliver.compose.ui.graphics.painter

import androidx.compose.runtime.Immutable
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.graphics.ColorCodec
import dev.keliver.compose.ui.graphics.vector.ImageVector
import dev.keliver.compose.wire.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The guest mirror of `androidx.compose.ui.graphics.painter.Painter`: only the painters a host can
 * rebuild (docs/COMPOSE_GENERATOR_DESIGN.md §6): an icon's vector painter
 * (`rememberVectorPainter(Icons.Filled.Search)`) and a [ColorPainter]. A guest-drawn painter is
 * per-frame code; `painterResource` names the host app's resources (§11, open question 6).
 *
 * Wire: `{"vec":"Filled.Search"}` or `{"color":<color>}`.
 */
@Immutable
@Serializable(with = PainterCodec::class)
public abstract class Painter internal constructor()

/** As `androidx.compose.ui.graphics.painter.ColorPainter`. */
@Immutable
public class ColorPainter(public val color: Color) : Painter() {
  override fun equals(other: Any?): Boolean = other is ColorPainter && other.color == color
  override fun hashCode(): Int = color.hashCode()
  override fun toString(): String = "ColorPainter($color)"
}

/** What `rememberVectorPainter(image)` returns in a guest: the icon, painted by the host. */
@Immutable
public class VectorPainter internal constructor(public val image: ImageVector) : Painter() {
  override fun equals(other: Any?): Boolean = other is VectorPainter && other.image == image
  override fun hashCode(): Int = image.hashCode()
  override fun toString(): String = "VectorPainter(${image.name})"

  public companion object {
    public fun of(image: ImageVector): VectorPainter = VectorPainter(image)
  }
}

public object PainterCodec : JsonCodec<Painter>("dev.keliver.compose.Painter") {
  override fun toJson(value: Painter): JsonElement = when (value) {
    is VectorPainter -> buildJsonObject { put("vec", value.image.name) }
    is ColorPainter -> buildJsonObject { put("color", ColorCodec.toJson(value.color)) }
    else -> error("unsupported painter $value")
  }

  override fun fromJson(json: JsonElement): Painter {
    val o = json.jsonObject
    o["vec"]?.let { return VectorPainter(ImageVector(it.jsonPrimitive.content)) }
    return ColorPainter(ColorCodec.fromJson(o.getValue("color")))
  }
}
