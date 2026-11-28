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
package dev.keliver.protocol.guest

import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.FrameCacheMarker
import dev.keliver.protocol.FrameCacheTags
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one place a cached-frame marker element (docs/SHOW_BEFORE_COMPUTE.md 2.2) is turned into
 * protocol values. Every guest protocol adapter calls it before the generated
 * `modifierTagAndSerializationStrategy`, which knows schema modifiers only and throws for anything else.
 */
@RedwoodCodegenApi
@OptIn(ExperimentalKeliverHostFeaturesApi::class)
public object FrameCacheMarkerEncoding {
  /** The marker [element] is, or null for a schema modifier element. Its tag must be in the reserved range. */
  public fun markerOrNull(element: Modifier.Element): FrameCacheMarker? {
    if (element !is FrameCacheMarker) return null
    require(FrameCacheTags.isReserved(element.tag)) {
      "A frame-cache marker's tag must be in ${FrameCacheTags.range}, was ${element.tag}"
    }
    return element
  }

  /** The marker's value as a [JsonObject], or null for a marker without fields. */
  public fun json(marker: FrameCacheMarker): JsonElement? {
    if (marker.fields.isEmpty()) return null
    return JsonObject(
      marker.fields.mapValues { (name, field) ->
        when (field) {
          null -> JsonNull
          is String -> JsonPrimitive(field)
          is Long -> JsonPrimitive(field)
          is Boolean -> JsonPrimitive(field)
          else -> throw AssertionError("unsupported marker field $name: $field")
        }
      },
    )
  }

  /**
   * The marker's fields as plain values for a guest that builds JavaScript objects itself: `String`,
   * `Double` (a `Long` as a number; a policy's age is bounded so it is exact), `Boolean` and `null`.
   */
  public fun plainFields(marker: FrameCacheMarker): List<Pair<String, Any?>> =
    marker.fields.map { (name, field) ->
      name to when (field) {
        null -> null
        is String -> field
        is Long -> field.toDouble()
        is Boolean -> field
        else -> throw AssertionError("unsupported marker field $name: $field")
      }
    }
}
