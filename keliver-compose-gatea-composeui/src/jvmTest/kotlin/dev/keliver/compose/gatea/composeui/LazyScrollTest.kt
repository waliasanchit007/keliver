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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.keliver.compose.foundation.lazy.LazyListState as GuestLazyListState
import dev.keliver.compose.gatea.composeui.samples.guest.LongList as GuestLongList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * NF1 (docs/PLAN_2026-11.md WS7), scrolling through the generated `LazyColumn`: the guest composes
 * only the window where the list is, or will come to rest, never the windows it has left.
 * `LongList`'s rows are 40 dp; the list shows 10 of them (400 dp).
 */
@OptIn(ExperimentalTestApi::class)
class LazyScrollTest {
  private class Viewport(val first: Int, val last: Int, val offset: Int) {
    override fun toString() = "($first..$last @$offset)"
  }

  /** The viewport reports among the host's events (`LongList` sends no other events). */
  private fun EventTap.viewports(from: Int = 0): List<Viewport> = sent.drop(from)
    .filter { it.args.size == 3 }
    .map { e -> e.args.map { it.jsonPrimitive.int }.let { Viewport(it[0], it[1], it[2]) } }

  private fun ComposeUiTest.longList(tap: EventTap, state: GuestLazyListState = GuestLazyListState()): GuestOnHost {
    val host = guestOnHost(tap) { GuestLongList(state, count = 500) }
    setContent {
      MaterialTheme {
        Box(Modifier.size(300.dp, 400.dp)) { host.root.Render(null) }
      }
    }
    settle(host)
    return host
  }

  private fun ComposeUiTest.settle(host: GuestOnHost) {
    repeat(4) {
      waitForIdle()
      host.pump()
    }
    waitForIdle()
  }

  private fun GuestOnHost.composedRows(fromBatch: Int): Set<Int> = wire.drop(fromBatch)
    .flatMap { Regex("\"Row (\\d+)\"").findAll(it).map { m -> m.groupValues[1].toInt() }.toList() }
    .toSet()

  /**
   * The guest side: a burst of reports that queued while the guest was busy is composed as ONE
   * window, the latest; none of the windows the reports passed through is composed.
   */
  @Test fun aBurstOfViewportsComposesOnlyTheLatestWindow() = runComposeUiTest {
    val tap = EventTap()
    val state = GuestLazyListState()
    val host = longList(tap, state)
    val list = onNode(hasScrollToIndexAction())

    tap.hold = true
    val reportsBefore = tap.viewports().size
    for (index in 30..450 step 30) {
      list.performScrollToIndex(index)
      waitForIdle()
    }
    val burst = tap.viewports().drop(reportsBefore)
    assertTrue(burst.size >= 15, "the host reported every window it passed: $burst")

    val batchesBefore = host.wire.size
    tap.release()
    // The guest's thread would apply the reports' state writes before its next frame.
    Snapshot.sendApplyNotifications()
    host.pump()
    waitForIdle()
    assertEquals(batchesBefore + 1, host.wire.size, "one guest frame, one batch, for the whole burst")
    val composed = host.composedRows(batchesBefore)
    assertTrue(450 in composed, "the latest window was composed: $composed")
    assertTrue(composed.all { it >= 440 }, "no window the list had left was composed: ${composed.sorted()}")
    assertEquals(450, state.firstVisibleItemIndex, "the guest's state took the latest report")

    // Later frames (the window settling) stay where the list is.
    settle(host)
    assertTrue(host.composedRows(batchesBefore).all { it >= 420 }, "settling stays near the list")
  }

  /**
   * The host side: a fast fling reports the window it will come to rest at as it starts, and none
   * of the windows it passes; the guest then composes that window and nothing in between.
   */
  @Test fun aFlingReportsWhereItWillComeToRest() = runComposeUiTest {
    val tap = EventTap()
    val state = GuestLazyListState()
    val host = longList(tap, state)
    val reportsBefore = tap.viewports().size
    val batchesBefore = host.wire.size

    mainClock.autoAdvance = false
    onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(startY = bottom - 5f, endY = top + 5f, durationMillis = 60) }
    // The drag has ended and the fling started: no guest frame has run yet.
    repeat(3) { mainClock.advanceTimeByFrame() }
    val atFlingStart = tap.viewports(reportsBefore)
    mainClock.autoAdvance = true
    waitForIdle()
    val reports = tap.viewports(reportsBefore)
    val rest = reports.last()
    println("NF1 fling reports: $reports")

    assertTrue(rest.first > 30, "a fast fling travels: $reports")
    // The fling's report (the first far from where the drag ended) predicts where it comes to rest.
    val predicted = atFlingStart.last()
    assertTrue(kotlin.math.abs(predicted.first - rest.first) <= 2, "predicted $predicted, rested at $rest")
    // Between the prediction and the rest, nothing else was reported.
    val afterPrediction = reports.drop(atFlingStart.size)
    assertTrue(afterPrediction.size <= 1, "the windows the fling passed were not reported: $reports")

    settle(host)
    val composed = host.composedRows(batchesBefore)
    val dragEnd = atFlingStart.dropLast(1).maxOfOrNull { it.last } ?: 9
    val passed = (dragEnd + 25) until (rest.first - 25)
    assertTrue(composed.none { it in passed }, "no row the fling passed was composed: ${composed.sorted()} (passed $passed)")
    assertTrue(rest.first in composed, "the resting window was composed: ${composed.sorted()}")
  }

  /** Placeholders take the measured size of the guest's rows: a screen of them holds as many as of rows. */
  @Test fun placeholdersTakeTheRowsMeasuredSize() = runComposeUiTest {
    val tap = EventTap()
    val host = longList(tap)
    val atTop = tap.viewports().last()
    assertEquals(0 to 9, atTop.first to atTop.last, "ten 40 dp rows fill 400 dp")

    tap.hold = true
    onNode(hasScrollToIndexAction()).performScrollToIndex(300)
    waitForIdle()
    val overPlaceholders = tap.viewports().last()
    assertEquals(300 to 309, overPlaceholders.first to overPlaceholders.last, "ten placeholders fill 400 dp, as ten rows do")
    tap.release()
    settle(host)
  }

  @Test fun predictionMovesByWholeItemsAndStaysInTheList() {
    // 100 px items, 8 visible, 1,000 items.
    assertEquals(10 to 17, predictRestingWindow(0, 0, 8, 100f, 1_000f, 1_000))
    assertEquals(10 to 17, predictRestingWindow(0, 50, 8, 100f, 1_000f, 1_000), "the offset counts")
    assertEquals(5 to 12, predictRestingWindow(10, 50, 8, 100f, -550f, 1_000), "a fling back: 10.5 - 5.5 items")
    assertEquals(992 to 999, predictRestingWindow(900, 0, 8, 100f, 100_000f, 1_000), "clamped at the end")
    assertEquals(0 to 7, predictRestingWindow(5, 0, 8, 100f, -100_000f, 1_000), "clamped at the start")
    assertEquals(0 to 2, predictRestingWindow(0, 0, 8, 100f, 1_000f, 3), "fewer items than the viewport")
  }
}
