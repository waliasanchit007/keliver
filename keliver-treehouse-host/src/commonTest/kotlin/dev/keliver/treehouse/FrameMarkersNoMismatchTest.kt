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
@file:OptIn(RedwoodCodegenApi::class, ExperimentalKeliverSchemaMismatchApi::class)

package dev.keliver.treehouse

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.protocol.FrameCacheTags
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierChange
import dev.keliver.protocol.ModifierElement
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.WidgetTag
import dev.keliver.protocol.host.GeneratedHostProtocol
import dev.keliver.protocol.host.UiChange
import dev.keliver.protocol.host.WidgetHostProtocol
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * NF5 A2 x MS4-04: the reserved frame-cache marker tags are dropped in [UiChange.fromProtocol],
 * before the generated `createModifier` can report them, so a session carrying them produces no
 * [EventListener.schemaMismatch]; a genuinely unknown tag still does.
 */
class FrameMarkersNoMismatchTest {
  private class Recording : EventListener() {
    val mismatches = mutableListOf<String>()

    override fun schemaMismatch(mismatch: SchemaMismatch) {
      mismatches += mismatch.toString()
    }
  }

  private val listener = Recording()
  private val handler = RealEventPublisher(listener).widgetProtocolMismatchHandler

  // Like the generated one: only createModifier reports, and only for a tag it does not know.
  private val protocol = object : GeneratedHostProtocol {
    override val json: Json = Json.Default
    override val mismatchHandler = handler
    override fun widget(tag: WidgetTag): WidgetHostProtocol? = null
    override fun createModifier(element: ModifierElement): Modifier {
      handler.onUnknownModifier(element.tag)
      return Modifier
    }
  }

  private fun apply(vararg elements: ModifierElement) {
    UiChange.fromProtocol(protocol, ModifierChange(Id(1), elements.toList()))
  }

  @Test fun markersProduceNoSchemaMismatch() {
    apply(
      ModifierElement(
        ModifierTag(FrameCacheTags.CACHED_FRAME),
        buildJsonObject {
          put("content", "shell")
          put("maxAgeMillis", 43_200_000L)
          put("ready", true)
        },
      ),
      ModifierElement(ModifierTag(FrameCacheTags.CACHED)),
      ModifierElement(ModifierTag(FrameCacheTags.NOT_CACHED)),
      ModifierElement(ModifierTag(-4_600_050), JsonPrimitive(1)),
    )

    assertThat(listener.mismatches).isEmpty()
  }

  @Test fun anUnknownTagOutsideTheRangeStillReports() {
    apply(ModifierElement(ModifierTag(FrameCacheTags.CACHED)), ModifierElement(ModifierTag(99)))

    assertThat(listener.mismatches).containsExactly("unknown modifier 99")
  }
}
