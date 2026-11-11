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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.compose.foundation.clickable as guestClickable
import dev.keliver.compose.foundation.lazy.LazyListState as GuestLazyListState
import dev.keliver.compose.foundation.lazy.LazyListStateWire
import dev.keliver.compose.foundation.lazy.ScrollCommand
import dev.keliver.compose.material3.ButtonColors as GuestButtonColors
import dev.keliver.compose.material3.Text as GuestText
import dev.keliver.compose.material3.TextButton as GuestTextButton
import dev.keliver.compose.ui.Modifier as GuestModifier
import dev.keliver.compose.wire.DefaultsCall
import dev.keliver.compose.wire.KeliverGeneratedApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A guest built now, on a host built before (the lock's levels), and a host subtree recreated. */
@OptIn(ExperimentalTestApi::class)
class HostCompatibilityTest {
  private val reported = mutableListOf<String>()
  private val sink = GeneratedMismatches.sink

  @BeforeTest fun captureMismatches() {
    GeneratedMismatches.reset()
    GeneratedMismatches.sink = { reported += it }
  }

  @AfterTest fun restoreSink() {
    GeneratedMismatches.sink = sink
    GeneratedMismatches.reset()
  }

  /**
   * Text and clickable gained overloads at level 3 whose added parameters (`autoSize`,
   * `interactionSource`) don't travel, so a guest still sends Gate A's tags (widget 6, modifier 20),
   * which a level-2 host knows.
   */
  @Test fun aGuestTextAndClickableSendTheTagsAnOlderHostKnows() {
    val host = guestOnHost { GuestText("Hello", GuestModifier.guestClickable {}) }
    val changes = host.wire.flatMap { Json.parseToJsonElement(it).jsonArray }.map { it.jsonObject }
    val created = changes.filter { it["type"]?.jsonPrimitive?.content == "create" }.map { it.getValue("tag").jsonPrimitive.int }
    val modifiers = changes.filter { it["type"]?.jsonPrimitive?.content == "modifier" }
      .flatMap { it.getValue("elements").jsonArray }.map { it.jsonArray[0].jsonPrimitive.int }
    assertEquals(listOf(6), created, "Text is widget 6, not 10")
    assertEquals(listOf(20), modifiers, "clickable is modifier 20, not 26")
  }

  /** A newer guest's `*Defaults` factory: the widget's own Compose default, reported once, no crash. */
  @Test fun anUnknownFactoryFallsBackToComposesDefault() = runComposeUiTest {
    val unknown = GuestButtonColors(DefaultsCall(999))
    val host = guestOnHost {
      GuestTextButton(onClick = {}, colors = unknown) { GuestText("First") }
      GuestTextButton(onClick = {}, colors = unknown) { GuestText("Second") }
    }
    setContent { Box(Modifier.size(300.dp, 200.dp)) { host.root.Render(null) } }
    waitForIdle()
    host.pump()
    waitForIdle()
    onAllNodesWithText("First").assertCountEquals(1)
    onAllNodesWithText("Second").assertCountEquals(1)
    assertEquals(1, reported.size, "reported once: $reported")
    assertTrue("ButtonColors: unknown factory 999" in reported.single(), reported.single())
  }

  /**
   * The host runs a guest command once. A subtree recreated from saved state keeps the position the
   * user scrolled to rather than running the guest's last command again; a new command still runs.
   */
  @Test fun aRestoredListKeepsItsPositionAndRunsOnlyNewCommands() = runComposeUiTest {
    var wire by mutableStateOf(LazyListStateWire(0, 0, ScrollCommand(1, 42, 0, animated = false)))
    var show by mutableStateOf(true)
    var state: LazyListState? = null
    setContent {
      val holder = rememberSaveableStateHolder()
      Box(Modifier.size(300.dp, 400.dp)) {
        if (show) {
          holder.SaveableStateProvider("list") {
            val s = wire.toCompose()
            state = s
            LazyColumn(state = s) { items(500) { Text("Row $it", Modifier.height(40.dp)) } }
          }
        }
      }
    }
    waitForIdle()
    assertEquals(42, state!!.firstVisibleItemIndex, "the guest's command ran")

    onNode(hasScrollToIndexAction()).performScrollToIndex(300)
    waitForIdle()
    assertEquals(300, state!!.firstVisibleItemIndex)

    show = false
    waitForIdle()
    show = true
    waitForIdle()
    assertEquals(300, state!!.firstVisibleItemIndex, "recreated: the user's position, not the last command's")

    wire = LazyListStateWire(0, 0, ScrollCommand(2, 10, 0, animated = false))
    waitForIdle()
    assertEquals(10, state!!.firstVisibleItemIndex, "a new command still runs")
  }

  /**
   * The host's saved state restored, the guest's not (an Android process death: the host's
   * `rememberSaveable` survives in the Bundle, the guest's state in memory does not). The new
   * guest's first command still runs, though the host saved the id of the old guest's command.
   */
  @OptIn(KeliverGeneratedApi::class)
  @Test fun aRestoredHostRunsAFreshGuestsFirstCommand() = runComposeUiTest {
    val clock = BroadcastFrameClock()
    val scope = CoroutineScope(Dispatchers.Unconfined + clock)
    fun GuestLazyListState.command(index: Int): LazyListStateWire {
      scope.launch { scrollToItem(index) }
      clock.sendFrame(0L)
      return wire
    }

    var wire by mutableStateOf(GuestLazyListState().command(42))
    var show by mutableStateOf(true)
    var state: LazyListState? = null
    setContent {
      val holder = rememberSaveableStateHolder()
      Box(Modifier.size(300.dp, 400.dp)) {
        if (show) {
          holder.SaveableStateProvider("list") {
            val s = wire.toCompose()
            state = s
            LazyColumn(state = s) { items(500) { Text("Row $it", Modifier.height(40.dp)) } }
          }
        }
      }
    }
    waitForIdle()
    assertEquals(42, state!!.firstVisibleItemIndex, "the old guest's command ran")

    show = false
    waitForIdle()
    wire = GuestLazyListState().command(10)
    show = true
    waitForIdle()
    assertEquals(10, state!!.firstVisibleItemIndex, "the fresh guest's first command ran on the restored host")
  }
}
