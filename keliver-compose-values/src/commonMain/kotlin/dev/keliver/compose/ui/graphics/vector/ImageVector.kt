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
package dev.keliver.compose.ui.graphics.vector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.keliver.compose.ui.graphics.painter.VectorPainter
import dev.keliver.compose.wire.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The guest mirror of `androidx.compose.ui.graphics.vector.ImageVector`: an icon from an icon
 * group the host includes, by name (`"Filled.ChevronRight"`). Path-built vectors are Gate B.
 * Wire: the name.
 */
@Immutable
@Serializable(with = ImageVectorCodec::class)
public class ImageVector(public val name: String) {
  override fun equals(other: Any?): Boolean = other is ImageVector && other.name == name
  override fun hashCode(): Int = name.hashCode()
  override fun toString(): String = "ImageVector($name)"
}

/** As `androidx.compose.ui.graphics.vector.rememberVectorPainter(image)`: the host paints the icon. */
@Composable
public fun rememberVectorPainter(image: ImageVector): VectorPainter = remember(image) { VectorPainter.of(image) }

public object ImageVectorCodec : JsonCodec<ImageVector>("dev.keliver.compose.ImageVector") {
  override fun toJson(value: ImageVector): JsonElement = JsonPrimitive(value.name)
  override fun fromJson(json: JsonElement): ImageVector = ImageVector(json.jsonPrimitive.content)
}
