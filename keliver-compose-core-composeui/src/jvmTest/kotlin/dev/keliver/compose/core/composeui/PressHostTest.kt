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
package dev.keliver.compose.core.composeui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NF3, the press signal on the host alone ([pressSignal], no guest): which events it sends, in what
 * order relative to the clickable's own callbacks, and that it changes nothing the clickable does
 * (its interactions, which drive the ripple). [log] is what a guest would receive, in order.
 *
 * The product's copy (docs/MS4_PLAN.md, MS4-02) of the prototype's test.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class PressHostTest {
  private val log = mutableListOf<String>()

  @Composable
  private fun Modifier.signal(): Modifier = pressSignal(Unit, onPress = { log += "press" }, onCancel = { log += "cancel" })

  @Composable
  private fun Row(tag: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(80.dp).testTag(tag))
  }

  @Test
  fun aTapSendsPressAtOnceThenTheClickThenTheCancel() = runComposeUiTest {
    setContent { Row("row", Modifier.clickable { log += "click" }.signal()) }
    mainClock.autoAdvance = false
    onNodeWithTag("row").performTouchInput { down(center) }
    assertEquals(listOf("press"), log, "outside a scroll container: at once")
    onNodeWithTag("row").performTouchInput { up() }
    mainClock.advanceTimeByFrame()
    assertEquals(listOf("press", "click", "cancel"), log, "the cancel follows the click")
  }

  @Test
  fun aLongPressEndsWithTheCancel() = runComposeUiTest {
    setContent {
      Row("row", Modifier.combinedClickable(onLongClick = { log += "long" }) { log += "click" }.signal())
    }
    onNodeWithTag("row").performTouchInput { longClick(center) }
    waitForIdle()
    assertEquals(listOf("press", "long", "cancel"), log)
  }

  @Test
  fun aDoubleClickEndsBothGesturesWithTheCancel() = runComposeUiTest {
    setContent {
      Row("row", Modifier.combinedClickable(onDoubleClick = { log += "double" }) { log += "click" }.signal())
    }
    onNodeWithTag("row").performTouchInput { doubleClick(center) }
    waitForIdle()
    assertEquals(listOf("press", "cancel", "press", "double", "cancel"), log)
  }

  @Test
  fun aDisabledClickableGetsPressAndCancel() = runComposeUiTest {
    setContent { Row("row", Modifier.clickable(enabled = false) { log += "click" }.signal()) }
    onNodeWithTag("row").performTouchInput { click(center) }
    waitForIdle()
    assertEquals(listOf("press", "cancel"), log)
  }

  @Test
  fun aButtonInsideTheRowTakesTheClickAndTheRowsPressIsCancelled() = runComposeUiTest {
    setContent {
      Box(Modifier.fillMaxWidth().height(80.dp).clickable { log += "row click" }.signal()) {
        Box(Modifier.size(40.dp).testTag("inner").clickable { log += "inner click" })
      }
    }
    onNodeWithTag("inner").performTouchInput { click(center) }
    waitForIdle()
    assertEquals(listOf("press", "inner click", "cancel"), log)
  }

  // A gesture the system cancels is not tested here: on the desktop test host,
  // `performTouchInput { down(center); cancel() }` delivered nothing after the down to the press
  // handler (checked in CI, 2026-11-21). On a device Compose ends a cancelled gesture with an
  // all-up event in every pass (or stops the handler), and either ends in the cancel.

  @Test
  fun anElementRemovedMidPressSendsTheCancel() = runComposeUiTest {
    var shown by mutableStateOf(true)
    setContent {
      Box(Modifier.fillMaxWidth().height(160.dp).testTag("area")) {
        if (shown) Row("row", Modifier.clickable { log += "click" }.signal())
      }
    }
    onNodeWithTag("row").performTouchInput { down(center) }
    waitForIdle()
    shown = false
    waitForIdle()
    assertEquals(listOf("press", "cancel"), log)
    onNodeWithTag("area").performTouchInput { up() }
    waitForIdle()
    assertEquals(listOf("press", "cancel"), log, "nothing more")
  }

  @Test
  fun aFingerThatLeavesTheElementCancelsAtOnce() = runComposeUiTest {
    setContent {
      Column {
        Row("row", Modifier.clickable { log += "click" }.signal())
        Row("below", Modifier)
      }
    }
    onNodeWithTag("row").performTouchInput {
      down(center)
      moveBy(Offset(0f, 400f))
    }
    waitForIdle()
    assertEquals(listOf("press", "cancel"), log, "sent as the finger leaves")
    onNodeWithTag("row").performTouchInput { up() }
    waitForIdle()
    assertEquals(listOf("press", "cancel"), log)
  }

  /** The clickable's interactions (what its ripple draws), with and without the press signal. */
  private fun ComposeUiTest.interactionsOf(withSignal: Boolean, inScroll: Boolean, gesture: androidx.compose.ui.test.TouchInjectionScope.() -> Unit): List<String> {
    val seen = mutableListOf<String>()
    val source = MutableInteractionSource()
    setContent {
      LaunchedEffect(source) {
        source.interactions.collect { i: Interaction ->
          seen += when (i) {
            is PressInteraction.Press -> "press"
            is PressInteraction.Release -> "release"
            is PressInteraction.Cancel -> "cancel"
            else -> i::class.simpleName.orEmpty()
          }
        }
      }
      val row = Modifier.clickable(interactionSource = source, indication = null) { seen += "click" }
        .let { if (withSignal) it.signal() else it }
      if (inScroll) {
        val state = rememberScrollState()
        Column(Modifier.verticalScroll(state).pressScrollContainer(state, enabled = true)) {
          Row("row", row)
          repeat(20) { Box(Modifier.fillMaxWidth().height(80.dp)) }
        }
      } else {
        Row("row", row)
      }
    }
    onNodeWithTag("row").performTouchInput(gesture)
    mainClock.advanceTimeBy(500)
    waitForIdle()
    return seen
  }

  @Test
  fun theClickablesInteractionsAreUnchanged() {
    val held: androidx.compose.ui.test.TouchInjectionScope.() -> Unit = {
      down(center)
      advanceEventTime(200)
      up()
    }
    val dragged: androidx.compose.ui.test.TouchInjectionScope.() -> Unit = {
      down(center)
      moveBy(Offset(0f, -200f))
      up()
    }
    for (inScroll in listOf(false, true)) {
      for ((name, gesture) in listOf("held" to held, "dragged" to dragged)) {
        var without = emptyList<String>()
        var with = emptyList<String>()
        runComposeUiTest { without = interactionsOf(false, inScroll, gesture) }
        runComposeUiTest { with = interactionsOf(true, inScroll, gesture) }
        assertEquals(without, with, "$name, in a scroll container: $inScroll")
      }
    }
  }

  // Inside a scroll container.

  private fun ComposeUiTest.scrollingRows(enabled: Boolean = true): ScrollState {
    lateinit var scroll: ScrollState
    setContent {
      val state = rememberScrollState()
      scroll = state
      Column(Modifier.testTag("list").verticalScroll(state).pressScrollContainer(state, enabled)) {
        repeat(30) { i -> Row("row $i", Modifier.clickable { log += "click $i" }.signal()) }
      }
    }
    return scroll
  }

  @Test
  fun aHeldTapInAScrollContainerSendsThePressAfterTheDelay() = runComposeUiTest {
    scrollingRows()
    mainClock.autoAdvance = false
    onNodeWithTag("row 2").performTouchInput { down(center) }
    mainClock.advanceTimeBy(PressSignalTiming().scrollDelayMillis / 2)
    assertEquals(emptyList(), log, "not yet")
    mainClock.advanceTimeBy(PressSignalTiming().scrollDelayMillis)
    assertEquals(listOf("press"), log, "held still for the delay")
    onNodeWithTag("row 2").performTouchInput { up() }
    mainClock.advanceTimeByFrame()
    assertEquals(listOf("press", "click 2", "cancel"), log)
  }

  @Test
  fun aQuickTapInAScrollContainerSendsOnlyTheClick() = runComposeUiTest {
    scrollingRows()
    onNodeWithTag("row 2").performTouchInput { click(center) }
    waitForIdle()
    assertEquals(listOf("click 2"), log, "released before the delay: no press, no cancel")
  }

  @Test
  fun aFlingThatStartsOnARowSendsNothing() = runComposeUiTest {
    val scroll = scrollingRows()
    onNodeWithTag("row 3").performTouchInput { swipeUp(startY = centerY, endY = centerY - 600f, durationMillis = 100) }
    waitForIdle()
    assertTrue(scroll.value > 0, "it scrolled")
    assertEquals(emptyList(), log)
  }

  @Test
  fun aTapThatStopsAFlingSendsNoPress() = runComposeUiTest {
    val scroll = scrollingRows()
    mainClock.autoAdvance = false
    onNodeWithTag("list").performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 50) }
    repeat(3) { mainClock.advanceTimeByFrame() }
    assertTrue(scroll.isScrollInProgress, "still flinging")
    val at = scroll.value
    onNodeWithTag("list").performTouchInput { down(center) }
    mainClock.advanceTimeBy(200)
    onNodeWithTag("list").performTouchInput { up() }
    mainClock.advanceTimeBy(500)
    assertTrue(scroll.value >= at)
    assertTrue("press" !in log && "cancel" !in log, "the touch-down stopped a moving list: $log")
  }

  @Test
  fun aDisabledScrollContainerSendsThePressAtOnce() = runComposeUiTest {
    scrollingRows(enabled = false)
    mainClock.autoAdvance = false
    onNodeWithTag("row 2").performTouchInput { down(center) }
    assertEquals(listOf("press"), log)
  }

  @Test
  fun theDelayIsTunable() = runComposeUiTest {
    setContent {
      androidx.compose.runtime.CompositionLocalProvider(LocalPressSignalTiming provides PressSignalTiming(scrollDelayMillis = 200)) {
        val state = rememberScrollState()
        Column(Modifier.verticalScroll(state).pressScrollContainer(state, enabled = true)) {
          repeat(30) { i -> Row("row $i", Modifier.clickable { log += "click $i" }.signal()) }
        }
      }
    }
    mainClock.autoAdvance = false
    onNodeWithTag("row 2").performTouchInput { down(center) }
    mainClock.advanceTimeBy(120)
    assertEquals(emptyList(), log)
    mainClock.advanceTimeBy(120)
    assertEquals(listOf("press"), log)
  }
}
