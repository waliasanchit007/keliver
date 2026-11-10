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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import dev.keliver.compose.wire.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * The guest's handle on a host-owned `androidx.compose.foundation.ScrollState`
 * (docs/COMPOSE_GENERATOR_DESIGN.md §10). Gate A: the host creates the real state where the
 * scroll modifier renders, from [initial]; mirrored fields (`value`, `maxValue`) and commands
 * (`scrollTo`, `animateScrollTo`) are Gate B. Wire: the initial offset.
 */
@Stable
@Serializable(with = ScrollStateCodec::class)
public class ScrollState(public val initial: Int) {
  override fun equals(other: Any?): Boolean = other is ScrollState && other.initial == initial
  override fun hashCode(): Int = initial
}

@Composable
public fun rememberScrollState(initial: Int = 0): ScrollState = remember { ScrollState(initial) }

public object ScrollStateCodec : JsonCodec<ScrollState>("dev.keliver.compose.ScrollState") {
  override fun toJson(value: ScrollState): JsonElement = JsonPrimitive(value.initial)
  override fun fromJson(json: JsonElement): ScrollState = ScrollState(json.jsonPrimitive.int)
}
