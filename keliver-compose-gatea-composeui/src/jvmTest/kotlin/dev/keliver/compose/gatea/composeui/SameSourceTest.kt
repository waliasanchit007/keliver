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
package dev.keliver.compose.gatea.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.compose.foundation.lazy.LazyListState as GuestLazyListState
import dev.keliver.compose.gatea.composeui.samples.guest.ItemDetail as GuestItemDetail
import dev.keliver.compose.gatea.composeui.samples.guest.LongList as GuestLongList
import dev.keliver.compose.gatea.composeui.samples.guest.SampleCard as GuestSampleCard
import dev.keliver.compose.gatea.composeui.samples.guest.SampleOrders as GuestSampleOrders
import dev.keliver.compose.gatea.composeui.samples.native.ItemDetail as NativeItemDetail
import dev.keliver.compose.gatea.composeui.samples.native.SampleCard as NativeSampleCard
import dev.keliver.compose.gatea.composeui.samples.native.SampleOrders as NativeSampleOrders
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
import dev.keliver.protocol.host.UiChange
import dev.keliver.protocol.host.hostRedwoodVersion
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.UiConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Gate A's fidelity criterion on the JVM (docs/COMPOSE_GENERATOR_DESIGN.md §16): one screen file,
 * compiled once against Compose and once against the generated guest API, renders the same pixels.
 * The guest side goes through the real protocol, as JSON, into the generated host renderers.
 */
@OptIn(ExperimentalTestApi::class)
class SameSourceTest {
  private val tags = listOf("Fragile", "Bulk", "In stock", "Ships today", "Gift wrap", "Returnable")

  @Test fun onlyImportsDiffer() {
    val dir = File("src/jvmTest/kotlin/dev/keliver/compose/gatea/composeui/samples")
    fun body(f: File) = f.readLines().filterNot {
      it.startsWith("import ") || it.startsWith("package ") || it.startsWith("//")
    }
    for (sample in listOf("SampleCard.kt", "SampleOrders.kt", "ItemDetail.kt")) {
      assertEquals(body(File(dir, "native/$sample")), body(File(dir, "guest/$sample")), sample)
      val guestImports = File(dir, "guest/$sample").readLines().filter { it.startsWith("import ") }
      val nativeImports = File(dir, "native/$sample").readLines().filter { it.startsWith("import ") }
      // Sorted: the formatter orders imports lexically, and the rewrite changes a prefix.
      assertEquals(nativeImports.sorted(), guestImports.map { it.replace("import dev.keliver.compose.", "import androidx.compose.") }.sorted(), sample)
    }
  }

  private val orders = listOf(
    "Order 1042" to "Out for delivery",
    "Order 1041" to "Packed",
    "Order 1039" to "Delivered on Monday",
    "Order 1036" to "Refund in progress",
  )

  /**
   * Gate B's sample: TopAppBar (slots, a `*Defaults` factory with a theme reference), LazyColumn
   * (a window, `LazyItemScope.fillParentMaxWidth`, keys), Surface with a border, Card with factory
   * colours and elevation, Image with a vector painter and a tint, Button, TextButton,
   * OutlinedButton (a factory-made border), HorizontalDivider (a snapshot and a reference).
   */
  @Test fun samePixelsForTheListingSample() = runComposeUiTest {
    val opened = mutableListOf<String>()
    val host = guestOnHost { GuestSampleOrders(orders, onBack = {}, onOpen = { opened += it }) }
    var showGuest by mutableStateOf(false)
    setContent {
      Theme {
        Box(Modifier.size(360.dp, 640.dp).testTag("slot")) {
          if (showGuest) {
            Box(Modifier.testTag("guest")) { host.root.Render(null) }
          } else {
            NativeSampleOrders(orders, onBack = {}, onOpen = {})
          }
        }
      }
    }
    waitForIdle()
    val native = onNodeWithTag("slot").captureToImage()
    showGuest = true
    repeat(3) {
      waitForIdle()
      host.pump()
    }
    waitForIdle()
    val guest = onNodeWithTag("slot").captureToImage()
    File("build/gate-b").mkdirs()
    javax.imageio.ImageIO.write(native.toAwtImage(), "png", File("build/gate-b/orders-native.png"))
    javax.imageio.ImageIO.write(guest.toAwtImage(), "png", File("build/gate-b/orders-guest.png"))
    File("build/gate-b/orders-wire.json").writeText(host.wire.joinToString("\n"))
    assertEquals(native.width to native.height, guest.width to guest.height, "sizes")
    assertEquals(0, differingPixels(native, guest, "build/gate-b/orders-diff.png"), "pixels that differ between native Compose and the generated guest")

    // A generated widget's own event (not a modifier lambda): the first card's TextButton…
    onAllNodesWithText("Details").onFirst().performClick()
    waitForIdle()
    host.pump()
    assertEquals(listOf("Order 1042"), opened)
    // …and the second Card's own onClick, by a tap on its title (inside the card, not a button).
    onNodeWithText("Order 1041").performClick()
    waitForIdle()
    host.pump()
    assertEquals(listOf("Order 1042", "Order 1041"), opened)
  }

  /**
   * Gate B-d's sample, written by an agent from a spec: an item-detail screen with a top bar, a lazy
   * list (cards, equal-width stat cards, history rows with dividers), and a bottom button row.
   */
  @Test fun samePixelsForTheItemDetailSample() = runComposeUiTest {
    var reordered = 0
    var adjusted = 0
    val host = guestOnHost { GuestItemDetail(onBack = {}, onAdjust = { adjusted++ }, onReorder = { reordered++ }) }
    var showGuest by mutableStateOf(false)
    setContent {
      Theme {
        Box(Modifier.size(360.dp, 640.dp).testTag("slot")) {
          if (showGuest) {
            Box(Modifier.testTag("guest")) { host.root.Render(null) }
          } else {
            NativeItemDetail(onBack = {}, onAdjust = {}, onReorder = {})
          }
        }
      }
    }
    waitForIdle()
    val native = onNodeWithTag("slot").captureToImage()
    showGuest = true
    repeat(3) {
      waitForIdle()
      host.pump()
    }
    waitForIdle()
    val guest = onNodeWithTag("slot").captureToImage()
    File("build/gate-b").mkdirs()
    javax.imageio.ImageIO.write(native.toAwtImage(), "png", File("build/gate-b/item-detail-native.png"))
    javax.imageio.ImageIO.write(guest.toAwtImage(), "png", File("build/gate-b/item-detail-guest.png"))
    File("build/gate-b/item-detail-wire.json").writeText(host.wire.joinToString("\n"))
    assertEquals(native.width to native.height, guest.width to guest.height, "sizes")
    assertEquals(0, differingPixels(native, guest, "build/gate-b/item-detail-diff.png"), "pixels that differ between native Compose and the generated guest")

    // The Button's own onClick, through the guest route.
    onNodeWithText("Reorder").performClick()
    waitForIdle()
    host.pump()
    assertEquals(1, reordered, "the guest's onReorder ran")
    assertEquals(0, adjusted, "onAdjust did not run")
  }

  /**
   * LazyColumn through the lazy-layout windowing (design §16): only a window of the items crosses;
   * a host scroll moves the guest's window and the state's mirrored fields; a guest command
   * (`scrollToItem`) scrolls the host's real state.
   */
  @Test fun lazyWindowFollowsTheHost() = runComposeUiTest {
    val state = GuestLazyListState()
    val host = guestOnHost { GuestLongList(state, count = 500) }
    setContent {
      Theme {
        Box(Modifier.size(300.dp, 400.dp)) { host.root.Render(null) }
      }
    }
    repeat(3) {
      waitForIdle()
      host.pump()
    }
    waitForIdle()
    onNodeWithText("Row 0").assertExists()
    val items = host.created[501] ?: 0
    assertTrue(items in 10..40, "a window of the 500 items crossed, not all of them: $items")

    onNode(hasScrollToIndexAction()).performScrollToIndex(300)
    repeat(4) {
      waitForIdle()
      host.pump()
    }
    waitForIdle()
    onNodeWithText("Row 300").assertExists()
    assertEquals(300, state.firstVisibleItemIndex, "the host's viewport reached the guest's state")

    CoroutineScope(Dispatchers.Unconfined + host.clock).launch { state.scrollToItem(42) }
    repeat(4) {
      host.pump()
      waitForIdle()
    }
    onNodeWithText("Row 42").assertExists()
    assertEquals(42, state.firstVisibleItemIndex, "the guest's command scrolled the host's state")
  }

  @Test fun samePixels() = runComposeUiTest {
    var opened = 0
    val host = guestOnHost { GuestSampleCard("A long item title that will not fit on one line", "Qty 12", tags) { opened++ } }
    // Both render in the same place, one after the other: a spot shadow depends on where in the
    // window it is drawn.
    var showGuest by mutableStateOf(false)
    setContent {
      Theme {
        Box(Modifier.width(360.dp).testTag("slot")) {
          if (showGuest) {
            Box(Modifier.testTag("guest")) { host.root.Render(null) }
          } else {
            NativeSampleCard("A long item title that will not fit on one line", "Qty 12", tags) {}
          }
        }
      }
    }
    waitForIdle()
    val native = onNodeWithTag("slot").captureToImage()
    showGuest = true
    waitForIdle()
    host.pump()
    waitForIdle()
    val guest = onNodeWithTag("slot").captureToImage()
    File("build/gate-a").mkdirs()
    javax.imageio.ImageIO.write(native.toAwtImage(), "png", File("build/gate-a/native.png"))
    javax.imageio.ImageIO.write(guest.toAwtImage(), "png", File("build/gate-a/guest.png"))
    File("build/gate-a/wire.json").writeText(host.wire.joinToString("\n"))
    assertEquals(native.width to native.height, guest.width to guest.height, "sizes")
    val diff = differingPixels(native, guest)
    assertEquals(0, diff, "pixels that differ between native Compose and the generated guest")

    // The modifier-event channel: a tap on the host runs the guest's clickable lambda.
    host.clickFirst(this)
    host.pump()
    assertEquals(1, opened, "the guest's clickable lambda ran")
  }

  @Composable
  private fun Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6750A4), secondaryContainer = Color(0xFFE8DEF8)), content = content)
  }
}

private fun differingPixels(a: ImageBitmap, b: ImageBitmap, diffPath: String = "build/gate-a/diff.png"): Int {
  val pa = a.toPixelMap()
  val pb = b.toPixelMap()
  var n = 0
  var box = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
  for (y in 0 until a.height) {
    for (x in 0 until a.width) {
      if (pa[x, y] != pb[x, y]) {
        n++
        box = intArrayOf(minOf(box[0], x), minOf(box[1], y), maxOf(box[2], x), maxOf(box[3], y))
        if (n < 4) println("differs at $x,$y: ${pa[x, y]} vs ${pb[x, y]}")
      }
    }
  }
  if (n > 0) {
    println("differing box: ${box.toList()}")
    val img = java.awt.image.BufferedImage(a.width, a.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (y in 0 until a.height) for (x in 0 until a.width) img.setRGB(x, y, if (pa[x, y] != pb[x, y]) 0xFF0000 else 0xFFFFFF)
    javax.imageio.ImageIO.write(img, "png", File(diffPath))
  }
  return n
}

/** A guest composition whose changes travel as JSON into generated host renderers. */
@OptIn(ExperimentalTestApi::class)
internal class GuestOnHost(
  private val guestAdapter: DefaultGuestProtocolAdapter,
  private val hostAdapter: HostProtocolAdapter<@Composable (Modifier) -> Unit>,
  private val protocol: KeliverComposeGateAHostProtocol,
  val root: ScopedChildren,
  val clock: BroadcastFrameClock,
) {
  private val json = Json
  val wire = mutableListOf<String>()

  /** Widgets created on the host, by widget tag (offset tags for dependency schemas). */
  val created = mutableMapOf<Int, Int>()

  fun pump() {
    clock.sendFrame(0L)
    val changes = guestAdapter.takeChanges()
    if (changes.isEmpty()) return
    val text = json.encodeToString(ListSerializer(Change.serializer()), changes)
    wire += text
    val decoded = json.decodeFromString(ListSerializer(Change.serializer()), text)
    decoded.filterIsInstance<dev.keliver.protocol.Create>().forEach { created.merge(it.tag.value, 1, Int::plus) }
    hostAdapter.sendChanges(decoded.mapNotNull { UiChange.fromProtocol(protocol, it) })
  }

  fun clickFirst(test: androidx.compose.ui.test.ComposeUiTest) {
    // The card row is the one clickable inside the guest box.
    test.onAllNodes(
      androidx.compose.ui.test.hasClickAction() and
        androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("guest")),
    ).onFirst().performClick()
    test.waitForIdle()
  }
}

internal fun guestOnHost(content: @Composable () -> Unit): GuestOnHost {
  val guestAdapter = DefaultGuestProtocolAdapter(hostVersion = hostRedwoodVersion, widgetSystemFactory = KeliverComposeGateAProtocolWidgetSystemFactory)
  val clock = BroadcastFrameClock()
  val composition = ProtocolRedwoodComposition(
    scope = CoroutineScope(Dispatchers.Unconfined + clock),
    guestAdapter = guestAdapter,
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
  val protocol = KeliverComposeGateAHostProtocol.create(Json.Default, dev.keliver.protocol.host.ProtocolMismatchHandler.Throwing)
  val hostAdapter = HostProtocolAdapter(
    guestVersion = guestRedwoodVersion,
    container = root,
    protocol = protocol,
    widgetSystem = keliverComposeGateAWidgetSystem(material),
    eventSink = { guestAdapter.sendEvent(it.toProtocol()) },
    leakDetector = LeakDetector.none(),
  )
  composition.setContent(content)
  return GuestOnHost(guestAdapter, hostAdapter, protocol, root, clock).also { it.pump() }
}
