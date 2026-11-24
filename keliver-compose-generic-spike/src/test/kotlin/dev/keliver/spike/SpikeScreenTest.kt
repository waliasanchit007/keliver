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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.sp
import dev.keliver.spike.compose.foundation.shape.RoundedCornerShape
import dev.keliver.spike.compose.ui.geometry.Offset
import dev.keliver.spike.compose.ui.geometry.Size
import dev.keliver.spike.compose.ui.graphics.Brush
import dev.keliver.spike.compose.ui.graphics.StrokeCap
import dev.keliver.spike.compose.ui.graphics.drawscope.RecordingDrawScope
import dev.keliver.spike.compose.ui.text.TextStyle
import dev.keliver.spike.compose.ui.text.font.FontWeight
import dev.keliver.spike.host.GeneratedReplay
import dev.keliver.spike.host.PagerStateRemote
import dev.keliver.spike.host.RemoteHost
import dev.keliver.spike.host.replay
import dev.keliver.spike.wire.RemoteObject
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One screen using all three mechanisms, rendered by the JVM host (Skia), written to
 * `build/generic-spike/screen.png`: a text style, a gradient and a shape (call records), a pager
 * the guest scrolls (a remote object) and a canvas (a recorded DrawScope).
 */
@OptIn(ExperimentalTestApi::class)
class SpikeScreenTest {
  @Test fun screen() = runComposeUiTest {
    // Guest side: values, a state proxy and a drawing, as JSON text.
    fun wire(v: dev.keliver.spike.wire.CallRecorded) = Json.parseToJsonElement(v.call.toJson().toString())
    val title = wire(TextStyle(color = Color(0xFF1B1B1F), fontSize = 22.sp, fontWeight = FontWeight.Bold))
    val gradient = wire(Brush.linearGradient(listOf(Color(0xFF6750A4), Color(0xFF7DD3FC))))
    val shape = wire(RoundedCornerShape(dev.keliver.compose.ui.unit.Dp(16f)))
    val drawing = RecordingDrawScope(hostSize = Size(240f, 120f)).apply {
      drawCircle(color = Color(0xFFE11D48), radius = 40f)
      drawLine(color = Color(0xFF0EA5E9), start = Offset(20f, 100f), end = Offset(220f, 100f), strokeWidth = 8f, cap = StrokeCap.Round)
    }.commands.let { Json.parseToJsonElement(JsonArray(it).toString()).jsonArray }
    val remote = RemoteObject("pager")
    val pager = dev.keliver.spike.compose.foundation.pager.PagerState(remote)
    pager.currentPage // observe

    var pagerWire by mutableStateOf(remote.wire)
    setContent {
      Column(Modifier.testTag("screen").background(androidx.compose.ui.graphics.Color.White).padding(16.dp)) {
        BasicText("Generic mechanisms", style = GeneratedReplay.replay(title) as androidx.compose.ui.text.TextStyle)
        Box(
          Modifier.padding(top = 12.dp).size(240.dp, 60.dp)
            .clip(GeneratedReplay.replay(shape) as androidx.compose.ui.graphics.Shape)
            .background(GeneratedReplay.replay(gradient) as androidx.compose.ui.graphics.Brush),
        )
        val host = rememberPagerState(pageCount = { 4 })
        HorizontalPager(host, Modifier.padding(top = 12.dp).size(240.dp, 48.dp)) { page ->
          Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFEDE9FE)), contentAlignment = Alignment.Center) {
            BasicText("page $page (guest scrolled here)")
          }
        }
        RemoteHost(pagerWire, host, PagerStateRemote) { report: JsonObject ->
          remote.onHostReport(Json.parseToJsonElement(report.toString()).jsonObject)
          pagerWire = remote.wire
        }
        Canvas(Modifier.padding(top = 12.dp).size(240.dp, 120.dp)) { replay(drawing) }
      }
    }
    CoroutineScope(Dispatchers.Unconfined).launch { pager.scrollToPage(2) }
    runOnIdle { pagerWire = remote.wire }
    waitUntil(timeoutMillis = 10_000) { pager.currentPage == 2 }
    waitForIdle()
    val image = onNodeWithTag("screen").captureToImage()
    val out = File("build/generic-spike/screen.png").apply { parentFile.mkdirs() }
    ImageIO.write(image.toAwtImage(), "png", out)
    assertEquals(2, pager.currentPage)
  }
}
