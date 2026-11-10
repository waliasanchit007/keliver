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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** As `androidx.compose.ui.graphics.DefaultAlpha`. */
@Suppress("ktlint:standard:property-naming") // Compose's own name.
public const val DefaultAlpha: Float = 1.0f

/** The guest mirror of `androidx.compose.ui.graphics.FilterQuality`. Wire: Compose's int value. */
@Immutable
@JvmInline
@Serializable(with = FilterQualityCodec::class)
public value class FilterQuality internal constructor(public val value: Int) {
  public companion object {
    @Stable public val None: FilterQuality = FilterQuality(0)

    @Stable public val Low: FilterQuality = FilterQuality(1)

    @Stable public val Medium: FilterQuality = FilterQuality(2)

    @Stable public val High: FilterQuality = FilterQuality(3)
  }
}

public object FilterQualityCodec : JsonCodec<FilterQuality>("dev.keliver.compose.FilterQuality") {
  override fun toJson(value: FilterQuality): JsonElement = JsonPrimitive(value.value)
  override fun fromJson(json: JsonElement): FilterQuality = FilterQuality(json.jsonPrimitive.int)
}
