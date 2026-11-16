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
@file:OptIn(KeliverGeneratedApi::class, ExperimentalCoroutinesApi::class)

package dev.keliver.compose.foundation.lazy

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.compose.wire.KeliverGeneratedApi
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [rememberLazyWindow] in a real composition: the list's place in the composition keys its measured
 * viewport, and the host's first report recomposes the list by itself.
 */
class ViewportSizedCompositionTest {
  @BeforeTest
  fun forget() {
    LazyWindowSizing.viewportSized = true
    LazyWindowSizing.forgetMeasuredViewports()
  }

  private class NoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
  }

  private class Harness(private val scope: TestScope) {
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val composition = Composition(NoNodes(), recomposer)
    private var frame = 0L

    var shown by mutableStateOf(true)
    var window: LazyWindow? = null
    var compositions = 0

    /** Each list's window in its first composition, before the loading strategy widens it. */
    val firstWindows = mutableListOf<IntRange>()

    init {
      scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
      composition.setContent {
        if (shown) {
          compositions++
          val state = remember { LazyListState() }
          val w = rememberLazyWindow(state) { items(100) { } }
          if (w !== window) firstWindows += (w.before until 100 - w.after)
          window = w
        } else {
          window = null
        }
      }
      settle()
    }

    /** What the list composes now: the window as `before until (count - after)`. */
    val composed: IntRange get() = window!!.let { it.before until 100 - it.after }

    fun settle() {
      repeat(5) {
        Snapshot.sendApplyNotifications()
        scope.runCurrent()
        frame += 16_000_000L
        clock.sendFrame(frame)
        scope.runCurrent()
      }
    }

    fun close() {
      composition.dispose()
      recomposer.cancel()
    }
  }

  @Test
  fun aListComposedAgainAtTheSamePlaceStartsWithItsMeasuredViewport() = runTest {
    val harness = Harness(this)
    try {
      assertEquals(listOf(0 until 15), harness.firstWindows, "nothing measured: the fixed preload")
      harness.window!!.onViewport(0, 9, 0)
      harness.settle()

      harness.shown = false
      harness.settle()
      harness.shown = true
      harness.settle()
      assertEquals(0 until 11, harness.firstWindows.last(), "the measured 10 rows and one")
      assertEquals(0 until 11, harness.composed, "until the host reports, the hint holds")
    } finally {
      harness.close()
    }
  }

  @Test
  fun theHostsFirstReportFillsTheWindowWithoutAnotherChange() = runTest {
    val harness = Harness(this)
    try {
      // A small hint (the screen rotated, or the list was measured among tall rows).
      harness.window!!.onViewport(0, 4, 0)
      harness.settle()
      harness.shown = false
      harness.settle()
      harness.shown = true
      harness.settle()
      assertEquals(0 until 6, harness.composed)

      // The first report is exactly the loading strategy's own first window (0..15), so it
      // changes none of the strategy's state: only the report itself can recompose the list.
      val before = harness.compositions
      harness.window!!.onViewport(0, 15, 0)
      harness.settle()
      assertTrue(harness.compositions > before, "the report recomposed the list")
      assertTrue(15 in harness.composed, "the visible rows are composed: ${harness.composed}")
    } finally {
      harness.close()
    }
  }
}
