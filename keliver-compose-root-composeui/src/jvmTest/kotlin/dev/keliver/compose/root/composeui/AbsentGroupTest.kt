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
package dev.keliver.compose.root.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.RedwoodCodegenApi
import dev.keliver.coil3.compose.AsyncImage as GuestAsyncImage
import dev.keliver.compose.coil.composeui.ComposeUiKeliverComposeCoilWidgetFactory
import dev.keliver.compose.coil.widget.GenAsyncImage1
import dev.keliver.compose.core.composeui.ScopedChildren
import dev.keliver.compose.core.widget.GenColumn1
import dev.keliver.compose.foundation.layout.Column as GuestColumn
import dev.keliver.compose.material3.Text as GuestText
import dev.keliver.compose.root.protocol.guest.KeliverComposeProtocolWidgetSystemFactory
import dev.keliver.compose.root.protocol.host.KeliverComposeHostProtocol
import dev.keliver.compose.root.widget.KeliverComposeWidgetSystem
import dev.keliver.leaks.LeakDetector
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.WidgetTag
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * MS4-03 (#244 decision 3): a host built without a group. The coil group is optional (Q1): a host
 * that does not ship keliver-compose-coil-composeui passes no coil factory to
 * keliverComposeWidgetSystem(). A guest's AsyncImage is then reported as an unknown widget once per
 * tag, draws nothing, and the rest of the tree renders and keeps updating.
 */
@OptIn(ExperimentalTestApi::class)
class AbsentGroupTest {
  private val unknownWidgets = mutableListOf<Int>()
  private val mismatches = object : ProtocolMismatchHandler {
    override fun onUnknownWidget(tag: WidgetTag) {
      unknownWidgets += tag.value
    }
    override fun onUnknownModifier(tag: ModifierTag) = fail("unknown modifier ${tag.value}")
    override fun onUnknownChildren(widgetTag: WidgetTag, tag: ChildrenTag) = fail("unknown children ${tag.value} of ${widgetTag.value}")
    override fun onUnknownProperty(widgetTag: WidgetTag, tag: PropertyTag) = fail("unknown property ${tag.value} of ${widgetTag.value}")
  }

  @Test fun aHostWithoutCoilRendersAGeneratedOnlyTree() = runComposeUiTest {
    val host = guestOnHost(keliverComposeWidgetSystem()) {
      GuestColumn {
        GuestText("Title")
        GuestText("Body")
      }
    }
    setContent { Box(Modifier.size(300.dp, 200.dp)) { host.root.Render(null) } }
    waitForIdle()
    onAllNodesWithText("Title").assertCountEquals(1)
    onAllNodesWithText("Body").assertCountEquals(1)
    assertEquals(emptyList(), unknownWidgets, "a generated-only tree reports nothing")
  }

  @Test fun aCoilWidgetOnAHostWithoutCoilIsReportedOnceAndTheRestRenders() = runComposeUiTest {
    var model by mutableStateOf("https://example.invalid/a.png")
    var showSecond by mutableStateOf(true)
    val host = guestOnHost(keliverComposeWidgetSystem()) {
      GuestColumn {
        GuestText("Above")
        GuestAsyncImage(model = model, contentDescription = "first")
        if (showSecond) GuestAsyncImage(model = "https://example.invalid/b.png", contentDescription = "second")
        GuestText("Below")
      }
    }
    setContent { Box(Modifier.size(300.dp, 400.dp)) { host.root.Render(null) } }
    waitForIdle()
    onAllNodesWithText("Above").assertCountEquals(1)
    onAllNodesWithText("Below").assertCountEquals(1)
    assertEquals(listOf(COIL_WIRE_OFFSET + 1), unknownWidgets, "two AsyncImages, one tag, one report")
    val column = host.root.widgets.single() as GenColumn1<*>
    assertEquals(4, column.content.widgets.size, "the absent widgets keep their places")
    assertTrue(column.content.widgets.none { it is GenAsyncImage1<*> })

    // The guest changes one absent widget and removes the other: both still find their nodes.
    model = "https://example.invalid/c.png"
    showSecond = false
    Snapshot.sendApplyNotifications()
    host.pump()
    waitForIdle()
    onAllNodesWithText("Above").assertCountEquals(1)
    onAllNodesWithText("Below").assertCountEquals(1)
    assertEquals(3, column.content.widgets.size)
    assertEquals(listOf(COIL_WIRE_OFFSET + 1), unknownWidgets, "still one report")
  }

  @Test fun aHostWithCoilCreatesTheRealWidget() = runComposeUiTest {
    val host = guestOnHost(keliverComposeWidgetSystem(coil = ComposeUiKeliverComposeCoilWidgetFactory())) {
      GuestColumn {
        GuestText("Above")
        GuestAsyncImage(model = "https://example.invalid/a.png", contentDescription = "first")
      }
    }
    setContent { Box(Modifier.size(300.dp, 400.dp)) { host.root.Render(null) } }
    waitForIdle()
    onAllNodesWithText("Above").assertCountEquals(1)
    assertEquals(emptyList(), unknownWidgets)
    val column = host.root.widgets.single() as GenColumn1<*>
    assertTrue(column.content.widgets[1] is GenAsyncImage1<*>, "coil's own renderer")
  }

  @OptIn(RedwoodCodegenApi::class)
  @Test
  fun theRootSystemNamesEachGroupAndItsAbsence() {
    val withoutCoil = keliverComposeWidgetSystem()
    assertEquals(null, withoutCoil.KeliverComposeCoilOrNull)
    assertTrue(withoutCoil.KeliverComposeCoreOrNull != null)
    val absent = runCatching { withoutCoil.KeliverComposeCoil }
    assertFalse(absent.isSuccess, "an absent group's owner property fails instead of handing out null")
  }

  private fun guestOnHost(
    widgetSystem: KeliverComposeWidgetSystem<@Composable (Modifier) -> Unit>,
    content: @Composable () -> Unit,
  ): GuestOnHost {
    val guestAdapter = DefaultGuestProtocolAdapter(hostVersion = hostRedwoodVersion, widgetSystemFactory = KeliverComposeProtocolWidgetSystemFactory)
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
    val root = ScopedChildren()
    val protocol = KeliverComposeHostProtocol.create(kotlinx.serialization.json.Json.Default, mismatches)
    val hostAdapter = HostProtocolAdapter(
      guestVersion = guestRedwoodVersion,
      container = root,
      protocol = protocol,
      widgetSystem = widgetSystem,
      eventSink = { event -> guestAdapter.sendEvent(event.toProtocol()) },
      leakDetector = LeakDetector.none(),
    )
    composition.setContent(content)
    return GuestOnHost(guestAdapter, hostAdapter, protocol, root, clock).also { it.pump() }
  }

  private class GuestOnHost(
    private val guestAdapter: DefaultGuestProtocolAdapter,
    private val hostAdapter: HostProtocolAdapter<@Composable (Modifier) -> Unit>,
    private val protocol: KeliverComposeHostProtocol,
    val root: ScopedChildren,
    private val clock: BroadcastFrameClock,
  ) {
    fun pump() {
      clock.sendFrame(0L)
      val changes = guestAdapter.takeChanges()
      if (changes.isEmpty()) return
      hostAdapter.sendChanges(changes.mapNotNull { UiChange.fromProtocol(protocol, it) })
    }
  }

  private companion object {
    /** The coil group's dependency tag in the generated root (root.json) × Redwood's member tag range. */
    const val COIL_WIRE_OFFSET = 17 * 1_000_000
  }
}
