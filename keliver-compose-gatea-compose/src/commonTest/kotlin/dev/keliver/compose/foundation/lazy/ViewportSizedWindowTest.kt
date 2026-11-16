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
@file:OptIn(KeliverGeneratedApi::class)

package dev.keliver.compose.foundation.lazy

import dev.keliver.compose.wire.KeliverGeneratedApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** NF3's viewport-sized first window (LazyDsl.kt: [ViewportSizedStrategy], [MeasuredViewports]). */
class ViewportSizedWindowTest {
  @AfterTest
  fun switchBackOn() {
    LazyWindowSizing.viewportSized = true
  }

  private fun LazyWindow.show(count: Int): LazyWindow = apply { update { items(count) { } } }

  /** The composed window as `before until (count - after)`. */
  private fun LazyWindow.composed(count: Int): IntRange = before until count - after

  @Test
  fun nothingMeasuredKeepsTheFixedPreload() {
    val window = LazyWindow(LazyListState(), site = 1, viewports = MeasuredViewports()).show(100)
    assertEquals(0 until 15, window.composed(100))
  }

  @Test
  fun aMeasuredPlaceStartsWithItsViewportAndOneRow() {
    val viewports = MeasuredViewports()
    LazyWindow(LazyListState(), site = 7, viewports = viewports).show(100).onViewport(0, 9, 0)
    assertEquals(10, viewports.rows(7))

    // The list comes back at the same place (back to a screen that was not retained).
    val again = LazyWindow(LazyListState(), site = 7, viewports = viewports).show(100)
    assertEquals(0 until 11, again.composed(100))

    // Another place has its own (no) measurement.
    assertEquals(0 until 15, LazyWindow(LazyListState(), site = 8, viewports = viewports).show(100).composed(100))
  }

  @Test
  fun theHostsFirstReportHandsTheWindowBackToTheStrategy() {
    val viewports = MeasuredViewports().apply { measured(3, rows = 10, full = true) }
    val window = LazyWindow(LazyListState(), site = 3, viewports = viewports).show(100)
    assertEquals(0 until 11, window.composed(100))
    window.onViewport(0, 9, 0)
    window.show(100)
    // As without NF3: the visible rows and the fixed preload past them.
    assertEquals(0 until 24, window.composed(100))
  }

  @Test
  fun aRestoredPositionStartsTheWindowThere() {
    val viewports = MeasuredViewports().apply { measured(5, rows = 10, full = true) }
    val window = LazyWindow(LazyListState(firstVisibleItemIndex = 30), site = 5, viewports = viewports).show(100)
    assertEquals(30 until 41, window.composed(100))
  }

  @Test
  fun aShortListNeverShrinksTheMeasurement() {
    val viewports = MeasuredViewports()
    viewports.measured(1, rows = 10, full = true)
    viewports.measured(1, rows = 3, full = false) // 3 items, all shown: the viewport holds at least 3
    assertEquals(10, viewports.rows(1))
    viewports.measured(1, rows = 12, full = false)
    assertEquals(12, viewports.rows(1))
    viewports.measured(1, rows = 8, full = true) // a full viewport is exact (the screen rotated)
    assertEquals(8, viewports.rows(1))
  }

  @Test
  fun aListShorterThanItsViewportIsRecordedAsNotFull() {
    val viewports = MeasuredViewports().apply { measured(2, rows = 10, full = true) }
    LazyWindow(LazyListState(), site = 2, viewports = viewports).show(4).onViewport(0, 3, 0)
    assertEquals(10, viewports.rows(2))
  }

  @Test
  fun anEmptyListRecordsNothing() {
    val viewports = MeasuredViewports()
    LazyWindow(LazyListState(), site = 4, viewports = viewports).show(0).onViewport(0, 0, 0)
    assertNull(viewports.rows(4))
  }

  @Test
  fun theOldestPlaceIsForgottenPastCapacity() {
    val viewports = MeasuredViewports(capacity = 2)
    viewports.measured(1, rows = 5, full = true)
    viewports.measured(2, rows = 6, full = true)
    viewports.measured(1, rows = 7, full = true) // 1 is now the newest
    viewports.measured(3, rows = 8, full = true)
    assertNull(viewports.rows(2))
    assertEquals(7, viewports.rows(1))
    assertEquals(8, viewports.rows(3))
  }

  @Test
  fun switchedOffTheFixedPreloadApplies() {
    val viewports = MeasuredViewports().apply { measured(6, rows = 10, full = true) }
    LazyWindowSizing.viewportSized = false
    assertEquals(0 until 15, LazyWindow(LazyListState(), site = 6, viewports = viewports).show(100).composed(100))
  }

  @Test
  fun fewerItemsThanTheViewportComposeThemAll() {
    val viewports = MeasuredViewports().apply { measured(9, rows = 10, full = true) }
    assertEquals(0 until 6, LazyWindow(LazyListState(), site = 9, viewports = viewports).show(6).composed(6))
  }
}
