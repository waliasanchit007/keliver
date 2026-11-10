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
import dev.keliver.compose.foundation.shape.CornerSize
import dev.keliver.compose.foundation.shape.CutCornerShape
import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.wire.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The guest mirror of `androidx.compose.ui.graphics.Shape`. Only the shapes a host can rebuild are
 * values here: [RectangleShape], rounded and cut corners, and theme references. A guest-defined
 * shape (per-layout code) is not possible (docs/COMPOSE_GENERATOR_DESIGN.md §6, §11).
 *
 * Wire: `"rect"`, `{"rr":[ts,te,be,bs]}`, `{"cut":[…]}` (each corner a dp number or `"50%"`), or
 * `{"ref":"shapes.medium"}`.
 */
@Immutable
@Serializable(with = ShapeCodec::class)
public abstract class Shape internal constructor()

/** A theme shape (`MaterialTheme.shapes.medium`), resolved by the host. */
@Immutable
public class ShapeReference internal constructor(public val name: String) : Shape() {
  public companion object {
    public fun of(name: String): ShapeReference = ShapeReference(name)
  }

  override fun equals(other: Any?): Boolean = other is ShapeReference && other.name == name
  override fun hashCode(): Int = name.hashCode()
  override fun toString(): String = "Shape(ref=$name)"
}

private object Rectangle : Shape() {
  override fun toString(): String = "RectangleShape"
}

@Stable public val RectangleShape: Shape = Rectangle

public object ShapeCodec : JsonCodec<Shape>("dev.keliver.compose.Shape") {
  override fun toJson(value: Shape): JsonElement = when (value) {
    is RoundedCornerShape -> buildJsonObject { put("rr", corners(value.topStart, value.topEnd, value.bottomEnd, value.bottomStart)) }
    is CutCornerShape -> buildJsonObject { put("cut", corners(value.topStart, value.topEnd, value.bottomEnd, value.bottomStart)) }
    is ShapeReference -> buildJsonObject { put("ref", value.name) }
    else -> JsonPrimitive("rect")
  }

  override fun fromJson(json: JsonElement): Shape {
    if (json !is JsonObject) return RectangleShape
    json["ref"]?.let { return ShapeReference(it.jsonPrimitive.content) }
    val rounded = json["rr"]
    val c = (rounded ?: json["cut"]!!).jsonArray.map(::corner)
    return if (rounded != null) RoundedCornerShape(c[0], c[1], c[2], c[3]) else CutCornerShape(c[0], c[1], c[2], c[3])
  }

  private fun corners(vararg c: CornerSize) = JsonArray(c.map { it.toJson() })
  private fun corner(e: JsonElement): CornerSize {
    val p = e.jsonPrimitive
    return if (p.isString) CornerSize(p.content.removeSuffix("%").toInt()) else CornerSize(dev.keliver.compose.ui.unit.Dp(p.content.toFloat()))
  }
}
