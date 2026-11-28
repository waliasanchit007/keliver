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
package dev.keliver.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FrameMarksTest {
  private fun cachedFrame(content: String = "shell") = ModifierElement(
    ModifierTag(FrameCacheTags.CACHED_FRAME),
    buildJsonObject {
      put("content", content)
      put("maxAgeMillis", 43_200_000L)
      put("ready", true)
      put("paneTitle", "Orders")
    },
  )

  @Test fun readsEveryMarker() {
    val marks = FrameMarks.read(
      listOf(
        ModifierElement(ModifierTag(7)),
        cachedFrame(),
        ModifierElement(ModifierTag(FrameCacheTags.CACHED)),
        ModifierElement(ModifierTag(FrameCacheTags.NOT_CACHED)),
      ),
    )
    assertThat(marks).isNotNull()
    assertThat(marks!!.cached).isEqualTo(true)
    assertThat(marks.notCached).isEqualTo(true)
    val frame = marks.cachedFrame!!
    assertThat(frame.content).isEqualTo("shell")
    assertThat(frame.maxAgeMillis).isEqualTo(43_200_000L)
    assertThat(frame.ready).isEqualTo(true)
    assertThat(frame.paneTitle).isEqualTo("Orders")
  }

  @Test fun noMarkerIsNull() {
    assertThat(FrameMarks.read(listOf(ModifierElement(ModifierTag(7))))).isNull()
    assertThat(FrameMarks.read(emptyList())).isNull()
  }

  @Test fun anUnassignedReservedTagIsNotUnderstood() {
    assertThat(FrameMarks.read(listOf(ModifierElement(ModifierTag(-4_600_050), JsonPrimitive(1))))).isNull()
  }

  @Test fun aMalformedMarkerIsNotUnderstood() {
    val bad = ModifierElement(ModifierTag(FrameCacheTags.CACHED_FRAME), JsonPrimitive("nope"))
    assertThat(FrameMarks.read(listOf(bad))).isNull()
    assertThat(FrameMarks.read(listOf(cachedFrame(content = "other")))).isNull()
  }
}
