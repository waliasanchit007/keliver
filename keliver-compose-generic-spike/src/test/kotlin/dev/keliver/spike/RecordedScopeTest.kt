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
package dev.keliver.spike

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.spike.compose.ui.geometry.Offset
import dev.keliver.spike.compose.ui.geometry.Size
import dev.keliver.spike.compose.ui.graphics.Brush
import dev.keliver.spike.compose.ui.graphics.StrokeCap
import dev.keliver.spike.compose.ui.graphics.drawscope.DrawScope
import dev.keliver.spike.compose.ui.graphics.drawscope.RecordingDrawScope
import dev.keliver.spike.compose.ui.graphics.drawscope.Stroke
import dev.keliver.spike.host.replay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

/**
 * Mechanism 3 (docs/COMPOSE_GENERIC_MECHANISMS.md): a `Canvas` block written against the generated
 * guest `DrawScope` is recorded, crosses as JSON, and is replayed on the host's real `DrawScope`.
 * The pixels equal Compose drawing the same calls directly.
 */
@OptIn(ExperimentalTestApi::class)
class RecordedScopeTest {
  /** The guest's drawing: the same source text as [native], against the guest's mirrors. */
  private fun DrawScope.guestDrawing() {
    drawCircle(color = Color.Red, radius = size.minDimension / 3f) // center: the host's default (this.center)
    drawLine(color = Color.Blue, start = Offset(0f, 90f), end = Offset(size.width, 90f), strokeWidth = 6f, cap = StrokeCap.Round)
    drawRect(brush = Brush.linearGradient(listOf(Color.Green, Color.Yellow)), topLeft = Offset(5f, 5f), size = Size(20f, 20f))
    drawRect(color = Color.Black, topLeft = Offset(70f, 5f)) // size: presence split, the host's this.size.offsetSize(topLeft)
    drawCircle(color = Color.Magenta, radius = 10f, center = Offset(80f, 60f), style = Stroke(width = 3f))
  }

  private fun androidx.compose.ui.graphics.drawscope.DrawScope.native() {
    drawCircle(color = androidx.compose.ui.graphics.Color.Red, radius = size.minDimension / 3f)
    drawLine(color = androidx.compose.ui.graphics.Color.Blue, start = androidx.compose.ui.geometry.Offset(0f, 90f), end = androidx.compose.ui.geometry.Offset(size.width, 90f), strokeWidth = 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawRect(brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color.Green, androidx.compose.ui.graphics.Color.Yellow)), topLeft = androidx.compose.ui.geometry.Offset(5f, 5f), size = androidx.compose.ui.geometry.Size(20f, 20f))
    drawRect(color = androidx.compose.ui.graphics.Color.Black, topLeft = androidx.compose.ui.geometry.Offset(70f, 5f))
    drawCircle(color = androidx.compose.ui.graphics.Color.Magenta, radius = 10f, center = androidx.compose.ui.geometry.Offset(80f, 60f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
  }

  @Test fun replayedPixelsEqualNative() = runComposeUiTest {
    // The host reports its size after layout; the guest records against it (100 x 100 px here).
    val recorder = RecordingDrawScope(hostSize = Size(100f, 100f))
    recorder.guestDrawing()
    val commands = Json.parseToJsonElement(JsonArray(recorder.commands).toString()).jsonArray
    assertEquals(5, commands.size)

    // One canvas at one place, drawn each way in turn: Skia dithers gradients and antialiases by
    // device position, so two canvases side by side differ by a few pixels even for one drawing.
    var replayed by mutableStateOf(true)
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Canvas(Modifier.size(100.dp).testTag("canvas")) { if (replayed) replay(commands) else native() }
      }
    }
    val guest = onNodeWithTag("canvas").captureToImage().toPixelMap()
    replayed = false
    val native = onNodeWithTag("canvas").captureToImage().toPixelMap()
    assertEquals(100, guest.width)
    var differing = 0
    val where = mutableListOf<String>()
    for (x in 0 until guest.width) for (y in 0 until guest.height) if (guest[x, y] != native[x, y]) {
      differing++
      if (where.size < 10) where += "($x,$y) ${guest[x, y]} vs ${native[x, y]}"
    }
    if (differing > 0) println("differing: $where")
    assertEquals(0, differing, "differing pixels")
    assertEquals(androidx.compose.ui.graphics.Color.Red, guest[50, 50])
    assertEquals(androidx.compose.ui.graphics.Color.Black, guest[95, 30])
  }
}
