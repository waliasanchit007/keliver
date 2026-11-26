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

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.keliver.compose.core.composeui.samples.guest.LongList as GuestLongList
import dev.keliver.compose.core.composeui.samples.guest.LongRow as GuestLongRow
import dev.keliver.compose.foundation.lazy.LazyListState as GuestLazyListState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * NF1 (docs/PLAN_2026-11.md WS7), scrolling through the generated `LazyColumn` and `LazyRow`: the
 * guest composes only the window where the list is, or will come to rest, never the windows it has
 * left. `LongList`'s rows are 40 dp; the list shows 10 of them (400 dp). Shared by the JVM and the
 * iOS simulator, so each platform's own default fling is predicted by itself.
 *
 * The product's copy (docs/MS4_PLAN.md, MS4-02) of the prototype's test, on the generated root.
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

  private fun ComposeUiTest.guestList(
    tap: EventTap,
    state: GuestLazyListState = GuestLazyListState(),
    frame: @Composable (content: @Composable () -> Unit) -> Unit = { Box(Modifier.size(300.dp, 400.dp)) { it() } },
    guest: @Composable (GuestLazyListState) -> Unit = { GuestLongList(it, count = 500) },
  ): GuestOnHost {
    val host = guestOnHost(tap) { guest(state) }
    setContent {
      MaterialTheme {
        frame { host.root.Render(null) }
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

  private fun GuestOnHost.composedRows(fromBatch: Int, label: String = "Row"): Set<Int> = wire.drop(fromBatch)
    .flatMap { Regex("\"$label (\\d+)\"").findAll(it).map { m -> m.groupValues[1].toInt() }.toList() }
    .toSet()

  /**
   * Flings with [gesture] and checks that the fling reported, as it started, the window it came to
   * rest at, and none of the windows it passed; and that the guest composed none of those.
   */
  private fun ComposeUiTest.assertFlingReportsItsRest(tap: EventTap, host: GuestOnHost, label: String = "Row", gesture: TouchInjectionScope.() -> Unit) {
    val reportsBefore = tap.viewports().size
    val batchesBefore = host.wire.size

    mainClock.autoAdvance = false
    onNode(hasScrollToIndexAction()).performTouchInput(gesture)
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
    assertTrue(abs(predicted.first - rest.first) <= 2, "predicted $predicted, rested at $rest")
    // Between the prediction and the rest, nothing else was reported.
    val afterPrediction = reports.drop(atFlingStart.size)
    assertTrue(afterPrediction.size <= 1, "the windows the fling passed were not reported: $reports")

    settle(host)
    val composed = host.composedRows(batchesBefore, label)
    val dragEnd = atFlingStart.dropLast(1).maxOfOrNull { it.last } ?: 9
    val passed = (dragEnd + 25) until (rest.first - 25)
    assertTrue(composed.none { it in passed }, "no row the fling passed was composed: ${composed.sorted()} (passed $passed)")
    assertTrue(rest.first in composed, "the resting window was composed: ${composed.sorted()}")
  }

  /**
   * The guest side: a burst of reports that queued while the guest was busy is composed as ONE
   * window, the latest; none of the windows the reports passed through is composed.
   */
  @Test fun aBurstOfViewportsComposesOnlyTheLatestWindow() = runComposeUiTest {
    val tap = EventTap()
    val state = GuestLazyListState()
    val host = guestList(tap, state)
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
    val host = guestList(tap)
    assertFlingReportsItsRest(tap, host) { swipeUp(startY = bottom - 5f, endY = top + 5f, durationMillis = 60) }
  }

  /** reverseLayout: the list grows from the bottom, so a swipe down scrolls toward higher indexes. */
  @Test fun aFlingInAReversedListReportsWhereItWillComeToRest() = runComposeUiTest {
    val tap = EventTap()
    val host = guestList(tap) { GuestLongList(it, count = 500, reverseLayout = true) }
    assertFlingReportsItsRest(tap, host) { swipeDown(startY = top + 5f, endY = bottom - 5f, durationMillis = 60) }
  }

  /** LazyRow: a horizontal fling predicts the same way (the generator passes the orientation). */
  @Test fun aFlingInARowReportsWhereItWillComeToRest() = runComposeUiTest {
    val tap = EventTap()
    val host = guestList(tap, frame = { Box(Modifier.size(400.dp, 300.dp)) { it() } }) { GuestLongRow(it, count = 500) }
    assertFlingReportsItsRest(tap, host, label = "Card") { swipeLeft(startX = right - 5f, endX = left + 5f, durationMillis = 60) }
  }

  /**
   * A touch during a fling stops it short of the predicted window: the window the list really
   * stopped at is reported, and the guest composes it.
   */
  @Test fun aTouchDuringAFlingReportsWhereTheListStopped() = runComposeUiTest {
    val tap = EventTap()
    val host = guestList(tap)
    val reportsBefore = tap.viewports().size

    mainClock.autoAdvance = false
    val list = onNode(hasScrollToIndexAction())
    list.performTouchInput { swipeUp(startY = bottom - 5f, endY = top + 5f, durationMillis = 60) }
    repeat(3) { mainClock.advanceTimeByFrame() }
    val predicted = tap.viewports(reportsBefore).last()
    list.performTouchInput { down(center) }
    repeat(3) { mainClock.advanceTimeByFrame() }
    list.performTouchInput {
      advanceEventTime(300)
      up()
    }
    mainClock.autoAdvance = true
    settle(host)

    val last = tap.viewports().last()
    println("NF1 touch during a fling: predicted $predicted, stopped at $last")
    assertTrue(last.first < predicted.first - 5, "the touch stopped the fling short: predicted $predicted, last $last")
    onNodeWithText("Row ${last.first}").assertIsDisplayed()
    onNodeWithText("Row ${last.last}").assertIsDisplayed()
  }

  /**
   * A drag released at rest (a zero-velocity fling) sends no report beyond the drag's own, with
   * content padding (the item drawn in the leading padding is visible) and a tall first row.
   */
  @Test fun aReleaseAtRestReportsNothing() = runComposeUiTest {
    val tap = EventTap()
    val host = guestList(tap) { GuestLongList(it, count = 500, padding = 30, tallFirstRow = true) }
    val list = onNode(hasScrollToIndexAction())

    // The tall first row, half scrolled off.
    list.performTouchInput { down(center) }
    list.performTouchInput { repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, -12f)) } }
    waitForIdle()
    val beforeRelease = tap.viewports().size
    list.performTouchInput {
      advanceEventTime(300)
      up()
    }
    settle(host)
    assertEquals(emptyList(), tap.viewports(beforeRelease).map { it.toString() }, "the tall first row: no report at release")

    // Scrolled into the list: the leading padding shows the item before the first visible one.
    list.performScrollToIndex(40)
    settle(host)
    list.performTouchInput { down(center) }
    list.performTouchInput { repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, 2f)) } }
    waitForIdle()
    val beforeSecondRelease = tap.viewports().size
    list.performTouchInput {
      advanceEventTime(300)
      up()
    }
    settle(host)
    assertEquals(emptyList(), tap.viewports(beforeSecondRelease).map { it.toString() }, "content padding: no report at release")
  }

  /** Placeholders take the measured size of the guest's rows: a screen of them holds as many as of rows. */
  @Test fun placeholdersTakeTheRowsMeasuredSize() = runComposeUiTest {
    val tap = EventTap()
    val host = guestList(tap)
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

  /** A row of cards with no fixed height keeps the cards' height while it shows only placeholders. */
  @Test fun aRowOfPlaceholdersKeepsTheCardsHeight() = runComposeUiTest {
    val tap = EventTap()
    val host = guestList(tap, frame = { Column(Modifier.size(400.dp, 300.dp)) { it() } }) { GuestLongRow(it, count = 500) }
    val row = onNode(hasScrollToIndexAction())
    row.assertHeightIsEqualTo(80.dp)

    tap.hold = true
    row.performScrollToIndex(300)
    waitForIdle()
    assertEquals(300, tap.viewports().last().first, "the row shows placeholders")
    row.assertHeightIsEqualTo(80.dp)
    tap.release()
    settle(host)
  }

  /** The probe runs a fling to its end at once: a decay fling travels its decay's target distance. */
  @Test fun theProbeTravelsTheFlingsDistance() = runTest {
    val decay = exponentialDecay<Float>()
    val behaviour = object : FlingBehavior {
      override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        var last = 0f
        AnimationState(0f, initialVelocity).animateDecay(decay) {
          scrollBy(value - last)
          last = value
        }
        return 0f
      }
    }
    for (velocity in listOf(800f, 4_000f, -6_000f)) {
      val expected = decay.calculateTargetValue(0f, velocity)
      val probed = behaviour.flingDistance(velocity)
      assertTrue(abs(probed - expected) <= abs(expected) * 0.01f + 1f, "velocity $velocity: probed $probed, decay target $expected")
    }
  }

  /** The platform's default fling, probed: it travels further the faster it starts. */
  @Test fun thePlatformFlingIsProbed() = runComposeUiTest {
    var platform: FlingBehavior? = null
    var spline: Float? = null
    val splineVelocity = 4_000f
    setContent {
      platform = ScrollableDefaults.flingBehavior()
      spline = rememberSplineBasedDecay<Float>().calculateTargetValue(0f, splineVelocity)
    }
    waitForIdle()
    val fling = platform!!
    val distances = mutableListOf<Float>()
    runTest {
      for (velocity in listOf(1_000f, 4_000f, 8_000f)) distances += fling.flingDistance(velocity)
      assertTrue(fling.flingDistance(-4_000f) < 0f, "a fling back travels back")
    }
    // The JVM's default is the spline; iOS's is its own decay. Logged for the record.
    println("NF1 platform fling distances at 1k/4k/8k px/s: $distances (spline at 4k: $spline)")
    assertTrue(distances[0] > 0f && distances[0] < distances[1] && distances[1] < distances[2], "$distances")
  }

  @Test fun predictionWalksTheLaidOutItemsAndStaysInTheList() {
    // 100 px items, 0..7 laid out from offset 0 in an 800 px viewport, 1,000 items.
    val top = ListGeometry(List(8) { LaidItem(it, it * 100, 100) }, viewportStart = 0, viewportEnd = 800, totalItems = 1_000)
    assertEquals(10 to 17, top.restingWindow(1_000f))
    assertEquals(10 to 18, top.restingWindow(1_050f), "a partly scrolled row stays in the window")
    assertNull(top.restingWindow(50f), "a fling shorter than the viewport predicts nothing")
    assertNull(top.restingWindow(799f), "a fling shorter than the viewport predicts nothing")
    assertNull(top.restingWindow(-5_000f), "a fling back at the top goes nowhere")
    assertEquals(992 to 999, top.restingWindow(100_000f), "clamped at the end")
    assertNull(ListGeometry(List(3) { LaidItem(it, it * 100, 100) }, 0, 800, totalItems = 3).restingWindow(1_000f), "fewer items than the viewport: nowhere to go")

    // Scrolled to 10.5, a fling back of 8.5 items.
    val mid = ListGeometry(List(9) { LaidItem(10 + it, it * 100 - 50, 100) }, viewportStart = 0, viewportEnd = 800, totalItems = 1_000)
    assertEquals(2 to 9, mid.restingWindow(-850f))
    assertEquals(0 to 7, mid.restingWindow(-100_000f), "clamped at the start")

    // Spacing counts between items.
    val spaced = ListGeometry(List(8) { LaidItem(it, it * 110, 100) }, viewportStart = 0, viewportEnd = 800, spacing = 10, totalItems = 1_000)
    assertEquals(10 to 17, spaced.restingWindow(1_100f))
  }

  @Test fun contentPaddingAndATallFirstRowPredictNothingForAShortFling() {
    // 30 px of leading padding: item 39 is drawn in it (its end is past the viewport's start, -30).
    val padded = ListGeometry(
      List(12) { LaidItem(39 + it, it * 40 - 50, 40) },
      viewportStart = -30,
      viewportEnd = 400,
      afterPadding = 30,
      totalItems = 500,
    )
    for (d in listOf(0f, 3f, -3f, 9f, 200f)) assertNull(padded.restingWindow(d), "padding, distance $d")
    // From the item drawn in the padding (39), not from the first visible one (40).
    assertEquals(64 to 75, padded.restingWindow(1_000f), "a long fling moves by whole rows")

    // A 300 px first row scrolled 150 px, then 40 px rows: the old mean-from-the-top prediction moved a row.
    val tall = ListGeometry(
      listOf(LaidItem(0, -150, 300)) + List(7) { LaidItem(1 + it, 150 + it * 40, 40) },
      viewportStart = 0,
      viewportEnd = 400,
      totalItems = 500,
    )
    for (d in listOf(0f, 5f, -5f, 30f)) assertNull(tall.restingWindow(d), "tall first row, distance $d")
    assertEquals(7 to 13, tall.restingWindow(400f), "the laid-out rows by their real sizes, the mean beyond")
    assertNull(tall.restingWindow(-10_000f), "back to the top is 150 px: shorter than the viewport")
  }
}
