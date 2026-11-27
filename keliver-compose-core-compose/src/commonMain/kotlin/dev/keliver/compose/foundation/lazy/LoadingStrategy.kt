/*
 * Copyright (C) 2024 Square, Inc.
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
package dev.keliver.compose.foundation.lazy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * The lazy lists' guest window (LazyDsl.kt): which items the guest composes. Copied from the legacy
 * keliver-lazylayout-compose (LoadingStrategy, ScrollOptimizedLoadingStrategy and the window half
 * of its LazyListState) so the product depends on no legacy module (docs/MS4_PLAN.md, MS4-03). The
 * window's behaviour is the legacy one, line for line. Left out is only what the legacy LazyColumn
 * sent the host (`programmaticScrollIndex`, with its `animated` flag): the generated lists send their
 * commands in their own state's wire. The prototype (keliver-compose-gatea-compose) keeps using the
 * legacy module until MS4-40. Fixes go here.
 */

/** Decides which items of a lazy list the guest composes. */
internal interface LoadingStrategy {
  /**
   * Returns the index of the first item that is visible on screen. The item may be partially
   * visible.
   *
   * This may temporarily be larger than the total number of items in the model. This will occur if
   * the number of items in the model shrinks.
   */
  val firstVisibleIndex: Int

  /**
   * Returns the index of the last item that is visible on screen. The item may be partially
   * visible.
   *
   * This may temporarily be larger than the total number of items in the model. This will occur if
   * the number of items in the model shrinks.
   */
  val lastVisibleIndex: Int

  /** Perform a programmatic scroll to [firstVisibleIndex]. */
  fun scrollTo(firstVisibleIndex: Int)

  /** React to a user-initiated scroll to the target range. */
  fun onUserScroll(firstVisibleIndex: Int, lastVisibleIndex: Int)

  /**
   * Returns the range of items to render into the view tree. This should be a slice of
   * `0..(itemCount - 1)`. It should cover the most-recently scrolled to `firstIndex..lastIndex`
   * range, plus any adjacent indexes to preload.
   */
  fun loadRange(itemCount: Int): IntRange
}

private const val DEFAULT_PRELOAD_ITEM_COUNT = 15
private const val SCROLL_IN_PROGRESS_PRELOAD_ITEM_COUNT = 5
private const val PRIMARY_PRELOAD_ITEM_COUNT = 20
private const val SECONDARY_PRELOAD_ITEM_COUNT = 10

private const val DEFAULT_SCROLL_INDEX = -1

/**
 * Preloads more items in the direction of the scroll, and keeps what is contiguous with the previous
 * window rather than dropping it at once.
 */
internal class ScrollOptimizedLoadingStrategy(
  private val defaultPreloadItemCount: Int = DEFAULT_PRELOAD_ITEM_COUNT,
  private val scrollInProgressPreloadItemCount: Int = SCROLL_IN_PROGRESS_PRELOAD_ITEM_COUNT,
  private val primaryPreloadItemCount: Int = PRIMARY_PRELOAD_ITEM_COUNT,
  private val secondaryPreloadItemCount: Int = SECONDARY_PRELOAD_ITEM_COUNT,
) : LoadingStrategy {
  /** Bounds of what the user is looking at. Everything else is placeholders! */
  override var firstVisibleIndex: Int by mutableIntStateOf(0)
    private set
  override var lastVisibleIndex: Int by mutableIntStateOf(0)
    private set

  private var firstIndexFromPrevious1: Int by mutableIntStateOf(DEFAULT_SCROLL_INDEX)
  private var firstIndexFromPrevious2: Int by mutableIntStateOf(DEFAULT_SCROLL_INDEX)
  private var lastIndexFromPrevious1: Int by mutableIntStateOf(DEFAULT_SCROLL_INDEX)

  private var beginFromPrevious1: Int by mutableIntStateOf(DEFAULT_SCROLL_INDEX)
  private var endFromPrevious1: Int by mutableStateOf(DEFAULT_SCROLL_INDEX)

  override fun scrollTo(firstVisibleIndex: Int) {
    require(firstVisibleIndex >= 0)

    val delta = (lastVisibleIndex - this.firstVisibleIndex)
    this.firstVisibleIndex = firstVisibleIndex
    this.lastVisibleIndex = firstVisibleIndex + delta
  }

  override fun onUserScroll(firstVisibleIndex: Int, lastVisibleIndex: Int) {
    this.firstVisibleIndex = firstVisibleIndex
    this.lastVisibleIndex = lastVisibleIndex
  }

  override fun loadRange(itemCount: Int): IntRange {
    // Ensure that the range includes `firstIndex` through `lastIndex`.
    var begin = firstVisibleIndex
    var end = lastVisibleIndex

    val isScrollingDown = firstIndexFromPrevious1 != DEFAULT_SCROLL_INDEX && firstIndexFromPrevious1 < firstVisibleIndex
    val isScrollingUp = firstIndexFromPrevious1 != DEFAULT_SCROLL_INDEX && firstIndexFromPrevious1 > firstVisibleIndex
    val hasStoppedScrolling = firstIndexFromPrevious2 != DEFAULT_SCROLL_INDEX && firstVisibleIndex == firstIndexFromPrevious1
    val wasScrollingDown = firstIndexFromPrevious1 > firstIndexFromPrevious2
    val wasScrollingUp = firstIndexFromPrevious1 < firstIndexFromPrevious2

    // Expand the range depending on scroll direction.
    when {
      isScrollingDown -> {
        begin -= scrollInProgressPreloadItemCount
        end += primaryPreloadItemCount
      }

      isScrollingUp -> {
        begin -= primaryPreloadItemCount
        end += scrollInProgressPreloadItemCount
      }

      hasStoppedScrolling && wasScrollingDown -> {
        begin -= secondaryPreloadItemCount
        end += primaryPreloadItemCount
      }

      hasStoppedScrolling && wasScrollingUp -> {
        begin -= primaryPreloadItemCount
        end += secondaryPreloadItemCount
      }

      // New.
      else -> {
        end += defaultPreloadItemCount
      }
    }

    // On initial load, set lastIndex to the end of the loaded window.
    if (lastVisibleIndex == 0) {
      lastVisibleIndex = end
    }

    // If we're contiguous with the previous visible window,
    // don't rush to remove things from the previous range.
    if (beginFromPrevious1 != DEFAULT_SCROLL_INDEX &&
      endFromPrevious1 != DEFAULT_SCROLL_INDEX
    ) {
      // Case one: Contiguous scroll down
      if (begin in firstIndexFromPrevious1..lastIndexFromPrevious1) {
        begin = beginFromPrevious1
      }

      // Case two: Contiguous scroll up
      if (end in firstIndexFromPrevious1..lastIndexFromPrevious1) {
        end = endFromPrevious1
      }
    }

    begin = begin.coerceIn(0, itemCount)
    end = end.coerceIn(0, itemCount)

    this.firstIndexFromPrevious2 = firstIndexFromPrevious1
    this.firstIndexFromPrevious1 = firstVisibleIndex
    this.lastIndexFromPrevious1 = lastVisibleIndex

    this.beginFromPrevious1 = begin
    this.endFromPrevious1 = end

    return begin until end
  }
}

/**
 * The window half of the legacy `LazyListState`: programmatic scrolls (the guest's commands) and the
 * host's reported viewport, through [strategy], give the range of items to compose.
 */
internal class LazyWindowState(
  private val strategy: LoadingStrategy,
) {
  /** Once we receive a user scroll, we limit which programmatic scrolls we apply. */
  private var userScrolled = false

  /**
   * Perform a programmatic scroll. (Whether it animates is the host's business: the generated
   * lists send the command in their own state's wire, `LazyListStateWire`.)
   */
  fun programmaticScroll(
    firstIndex: Int,
    clobberUserScroll: Boolean = true,
  ) {
    require(firstIndex >= 0)
    if (!clobberUserScroll && userScrolled) return

    strategy.scrollTo(firstIndex)
  }

  /** React to a user-initiated scroll. */
  fun onUserScroll(firstIndex: Int, lastIndex: Int) {
    if (firstIndex > 0) {
      userScrolled = true
    }

    strategy.onUserScroll(firstIndex, lastIndex)
  }

  fun loadRange(itemCount: Int): IntRange {
    return strategy.loadRange(itemCount)
  }
}
