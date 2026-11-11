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
package dev.keliver.portal.compose.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.compose.gatea.composeui.ScopedChildren
import dev.keliver.compose.gatea.composeui.keliverComposeGateAWidgetSystem
import dev.keliver.compose.gatea.composeui.samples.native.SampleCard as NativeSampleCard
import dev.keliver.compose.gatea.protocol.guest.KeliverComposeGateAProtocolWidgetSystemFactory
import dev.keliver.compose.gatea.protocol.host.KeliverComposeGateAHostProtocol
import dev.keliver.layout.composeui.ComposeUiRedwoodLayoutWidgetFactory
import dev.keliver.lazylayout.composeui.ComposeUiRedwoodLazyLayoutWidgetFactory
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetFactory
import dev.keliver.material.widget.KeliverMaterialWidgetSystem
import dev.keliver.protocol.Change
import dev.keliver.protocol.guest.DefaultGuestProtocolAdapter
import dev.keliver.protocol.guest.ProtocolRedwoodComposition
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.protocol.host.HostProtocolAdapter
import dev.keliver.protocol.host.ProtocolMismatchHandler
import dev.keliver.protocol.host.UiChange
import dev.keliver.protocol.host.hostRedwoodVersion
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.UiConfiguration
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * ED0's canvas criterion in pixels (JVM, Skia): Gate A's sample screen, read by portal-ingest with
 * ED0 on and drawn by the canvas through the generated stubs, the protocol (as JSON) and the
 * generated host renderers, draws the pixels the same screen draws as native Compose. A tap on
 * the canvas runs the document's action. The renderers are common Kotlin: the editor's wasm canvas
 * runs the same code (wasm cannot capture pixels in this repository's test runner — U41's skiko).
 */
@OptIn(ExperimentalTestApi::class)
class CanvasPixelTest {
  private val title = "A long item title that will not fit on one line"
  private val subtitle = "Subtitle"
  private val tags = listOf("Unread", "Weekly", "Starred", "From a teammate", "Has files", "Archived")

  @Test fun theCanvasDrawsTheNativeScreensPixels() = runComposeUiTest {
    val actions = mutableListOf<String>()
    val problems = mutableListOf<String>()
    val env = CanvasEnv(
      bind = { mapOf("title" to title, "subtitle" to subtitle, "tags" to tags)[it] },
      action = { name, _ -> actions += name },
      problem = { problems += it },
    )
    val doc = ingestSampleCard()
    val host = canvasOnHost { ComposeCanvas(doc, env) }
    var showCanvas by mutableStateOf(false)
    setContent {
      Theme {
        Box(Modifier.width(360.dp).testTag("slot")) {
          if (showCanvas) {
            Box(Modifier.testTag("canvas")) { host.root.Render(null) }
          } else {
            NativeSampleCard(title, subtitle, tags) {}
          }
        }
      }
    }
    waitForIdle()
    val native = onNodeWithTag("slot").captureToImage()
    showCanvas = true
    waitForIdle()
    host.pump()
    waitForIdle()
    val canvas = onNodeWithTag("slot").captureToImage()
    val out = File("build/ed0").apply { mkdirs() }
    ImageIO.write(native.toAwtImage(), "png", File(out, "native.png"))
    ImageIO.write(canvas.toAwtImage(), "png", File(out, "canvas.png"))
    assertEquals(emptyList(), problems, "what the canvas did not draw")
    assertEquals(native.width to native.height, canvas.width to canvas.height, "sizes")
    assertEquals(0, differingPixels(native, canvas), "pixels that differ between native Compose and the editor canvas")

    onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("canvas"))).onFirst().performClick()
    waitForIdle()
    host.pump()
    assertEquals(listOf("onOpen"), actions, "a tap on the canvas runs the document's action")
  }

  @Composable
  private fun Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6750A4), secondaryContainer = Color(0xFFE8DEF8)), content = content)
  }
}

private fun differingPixels(a: ImageBitmap, b: ImageBitmap): Int {
  val pa = a.toPixelMap()
  val pb = b.toPixelMap()
  var n = 0
  for (y in 0 until a.height) for (x in 0 until a.width) if (pa[x, y] != pb[x, y]) n++
  return n
}

/** The canvas in a guest composition whose changes travel as JSON into the generated host renderers. */
private class CanvasOnHost(
  private val guest: DefaultGuestProtocolAdapter,
  private val host: HostProtocolAdapter<@Composable (androidx.compose.ui.Modifier) -> Unit>,
  private val protocol: KeliverComposeGateAHostProtocol,
  val root: ScopedChildren,
  private val clock: BroadcastFrameClock,
) {
  fun pump() {
    clock.sendFrame(0L)
    val changes = guest.takeChanges()
    if (changes.isEmpty()) return
    val text = Json.encodeToString(ListSerializer(Change.serializer()), changes)
    val decoded = Json.decodeFromString(ListSerializer(Change.serializer()), text)
    host.sendChanges(decoded.mapNotNull { UiChange.fromProtocol(protocol, it) })
  }
}

private fun canvasOnHost(content: @Composable () -> Unit): CanvasOnHost {
  val guest = DefaultGuestProtocolAdapter(hostVersion = hostRedwoodVersion, widgetSystemFactory = KeliverComposeGateAProtocolWidgetSystemFactory)
  val clock = BroadcastFrameClock()
  val composition = ProtocolRedwoodComposition(
    scope = CoroutineScope(Dispatchers.Unconfined + clock),
    guestAdapter = guest,
    widgetVersion = UInt.MAX_VALUE,
    onBackPressedDispatcher = object : OnBackPressedDispatcher {
      override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable = object : Cancellable {
        override fun cancel() = Unit
      }
    },
    saveableStateRegistry = null,
    uiConfigurations = MutableStateFlow(UiConfiguration()),
  )
  val material = KeliverMaterialWidgetSystem(
    KeliverMaterial = ComposeUiKeliverMaterialWidgetFactory(coil3.ImageLoader.Builder(coil3.PlatformContext.INSTANCE).build()),
    RedwoodLayout = ComposeUiRedwoodLayoutWidgetFactory(),
    RedwoodLazyLayout = ComposeUiRedwoodLazyLayoutWidgetFactory(),
  )
  val root = ScopedChildren()
  val protocol = KeliverComposeGateAHostProtocol.create(Json.Default, ProtocolMismatchHandler.Throwing)
  val host = HostProtocolAdapter(
    guestVersion = guestRedwoodVersion,
    container = root,
    protocol = protocol,
    widgetSystem = keliverComposeGateAWidgetSystem(material),
    eventSink = { guest.sendEvent(it.toProtocol()) },
    leakDetector = LeakDetector.none(),
  )
  composition.setContent(content)
  return CanvasOnHost(guest, host, protocol, root, clock).also { it.pump() }
}
