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
package dev.keliver.compose.ui.layout

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.keliver.compose.wire.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** The guest mirror of `androidx.compose.ui.layout.ContentScale` (the named ones). Wire: the name. */
@Immutable
@Serializable(with = ContentScaleCodec::class)
public class ContentScale private constructor(public val name: String) {
  override fun equals(other: Any?): Boolean = other is ContentScale && other.name == name
  override fun hashCode(): Int = name.hashCode()
  override fun toString(): String = "ContentScale.$name"
  public companion object {
    @Stable public val Crop: ContentScale = ContentScale("Crop")

    @Stable public val Fit: ContentScale = ContentScale("Fit")

    @Stable public val FillHeight: ContentScale = ContentScale("FillHeight")

    @Stable public val FillWidth: ContentScale = ContentScale("FillWidth")

    @Stable public val Inside: ContentScale = ContentScale("Inside")

    @Stable public val None: ContentScale = ContentScale("None")

    @Stable public val FillBounds: ContentScale = ContentScale("FillBounds")
    internal fun of(name: String): ContentScale = ContentScale(name)
  }
}

public object ContentScaleCodec : JsonCodec<ContentScale>("dev.keliver.compose.ContentScale") {
  override fun toJson(value: ContentScale): JsonElement = JsonPrimitive(value.name)
  override fun fromJson(json: JsonElement): ContentScale = ContentScale.of(json.jsonPrimitive.content)
}
