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

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor

/*
 * The host half of a generated lazy list (docs/COMPOSE_GENERATOR_DESIGN.md §16), written by hand
 * once: the real `LazyColumn`/`LazyRow` gets the whole item count, of which the guest composed a
 * window; items outside it are placeholders until the window reaches them.
 *
 * Scrolling (NF1, docs/PLAN_2026-11.md WS7): the guest composes on one thread, and a window it has
 * started composing cannot be cancelled. So the host tells it only where the list IS, or, during a
 * fling, where the list WILL COME TO REST ([LazyHost.fling] predicts that from the fling's velocity
 * as it starts). The windows a fling passes through are never reported, so the guest never
 * composes them; their rows are drawn as placeholders of the rows' measured size.
 */

/** The content types of a guest item and of a placeholder, so Compose reuses each only as its own kind. */
private object GuestItem
private object PlaceholderItem

/**
 * The items of a generated lazy list: [before] placeholders, the guest's window (each an
 * `ExtraLazyItem` whose children render in the real item scope, so `fillParentMaxWidth` and
 * several root composables behave as in Compose), then [after] placeholders.
 */
internal fun LazyListScope.lazyWindow(window: ScopedChildren, before: Int, after: Int, host: LazyHost? = null) {
  val items = window.widgets
  items(
    count = before + items.size + after,
    key = { index -> (items.getOrNull(index - before) as? ComposeUiExtraLazyItem)?.key ?: "\u0000$index" },
    contentType = { index -> if (items.getOrNull(index - before) is ComposeUiExtraLazyItem) GuestItem else PlaceholderItem },
  ) { index ->
    val item = items.getOrNull(index - before) as? ComposeUiExtraLazyItem
    if (item != null) item.content.Render(this) else LazyPlaceholder(host)
  }
}

/** Material's minimum list-item height: a placeholder's size until a guest row has been measured. */
private val DefaultPlaceholderExtent = 48.dp

/** Visible on light and dark backgrounds alike: a neutral grey at low alpha. */
private val PlaceholderColor = Color(0x1F808080)

/**
 * An item the guest has not composed yet: shown while a scroll outruns the window, or while a
 * fling passes rows the guest was not asked for. It takes the measured mean size of the guest's
 * rows ([LazyHost.itemExtentPx]), so the list does not jump when the rows arrive and a fling
 * travels as far as it would over real rows; and it is drawn (a rounded block), never blank.
 */
@Composable
private fun LazyPlaceholder(host: LazyHost?) {
  val extentPx = host?.itemExtentPx ?: 0f
  val extent: Dp = if (extentPx > 0f) with(LocalDensity.current) { extentPx.toDp() } else DefaultPlaceholderExtent
  val modifier = if (host?.vertical != false) {
    Modifier.fillMaxWidth().height(extent).padding(horizontal = 16.dp, vertical = 4.dp)
  } else {
    Modifier.width(extent).height(DefaultPlaceholderExtent).padding(horizontal = 4.dp, vertical = 8.dp)
  }
  Box(modifier.background(PlaceholderColor, RoundedCornerShape(6.dp)))
}

/**
 * What the host keeps for one generated lazy list: its fling, which predicts where the list will
 * come to rest; and the measured size of the guest's rows, which placeholders take.
 */
internal class LazyHost(private val state: LazyListState) {
  internal var delegate: FlingBehavior? = null
  internal var decay: DecayAnimationSpec<Float>? = null

  /** The (first, last) window a running fling will come to rest at; null when no fling runs. */
  internal var predicted: Pair<Int, Int>? by mutableStateOf(null)
    private set

  /** The mean main-axis size of the guest's rows measured so far, in px; 0 until one is. */
  internal var itemExtentPx: Float by mutableFloatStateOf(0f)
    private set

  internal var vertical: Boolean by mutableStateOf(true)
    private set

  private val measured = HashSet<Any>()
  private var measuredSum = 0f

  /** Folds the visible guest rows into [itemExtentPx]: a running mean over distinct rows, so it settles. */
  internal fun measure(info: LazyListLayoutInfo) {
    val isVertical = info.orientation == Orientation.Vertical
    if (vertical != isVertical) vertical = isVertical
    if (measured.size >= MAX_MEASURED_ROWS) return
    var changed = false
    for (item in info.visibleItemsInfo) {
      if (item.contentType !== GuestItem || item.size <= 0) continue
      if (measured.add(item.key)) {
        measuredSum += item.size
        changed = true
      }
    }
    if (!changed) return
    val mean = measuredSum / measured.size
    // Whole pixels, and only a change of 2 px or more: placeholders do not resize on every frame.
    if (abs(mean - itemExtentPx) >= 2f) itemExtentPx = floor(mean)
  }

  /** The list's own fling, unchanged, recording where it will come to rest before it runs. */
  val fling: FlingBehavior = object : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
      val distance = decay?.calculateTargetValue(0f, initialVelocity)
      predicted = distance?.let {
        predictRestingWindow(state.layoutInfo, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, it)
      }
      try {
        val fling = delegate ?: return initialVelocity
        return with(fling) { performFling(initialVelocity) }
      } finally {
        predicted = null
      }
    }
  }

  private companion object {
    /** Enough rows for a stable mean; past it the set stops growing with a long list. */
    const val MAX_MEASURED_ROWS = 500
  }
}

/** The [LazyHost] of a generated lazy list with [state]. */
@Composable
internal fun rememberLazyHost(state: LazyListState): LazyHost {
  val decay = rememberSplineBasedDecay<Float>()
  val delegate = ScrollableDefaults.flingBehavior()
  return remember(state) { LazyHost(state) }.apply {
    this.decay = decay
    this.delegate = delegate
  }
}

/**
 * Where a fling of [distance] px (scroll units: positive scrolls toward higher indexes) from
 * ([firstIndex], [firstOffset]) comes to rest, as a (first, last) visible window, moving by the
 * mean extent of the items laid out now (with the spacing). Null when nothing is laid out.
 */
internal fun predictRestingWindow(info: LazyListLayoutInfo, firstIndex: Int, firstOffset: Int, distance: Float): Pair<Int, Int>? {
  val visible = info.visibleItemsInfo
  if (visible.isEmpty() || info.totalItemsCount <= 0) return null
  val extent = visible.sumOf { it.size }.toFloat() / visible.size + info.mainAxisItemSpacing
  return predictRestingWindow(firstIndex, firstOffset, visible.size, extent, distance, info.totalItemsCount)
}

internal fun predictRestingWindow(
  firstIndex: Int,
  firstOffset: Int,
  visibleCount: Int,
  extentPx: Float,
  distance: Float,
  totalItems: Int,
): Pair<Int, Int> {
  if (totalItems <= 0) return 0 to 0
  val moved = if (extentPx > 0f) floor((firstOffset + distance) / extentPx).toInt() else 0
  val count = visibleCount.coerceIn(1, totalItems)
  val first = (firstIndex + moved).coerceIn(0, totalItems - count)
  return first to (first + count - 1)
}

/**
 * Reports the host's viewport to the guest when its first or last visible item changes. During a
 * fling it reports, once, the window the fling will come to rest at instead ([LazyHost.predicted]),
 * and none of the windows on the way; when the fling ends, where the list really is (if that
 * differs). An unchanged window is never sent again.
 */
@Composable
internal fun ReportLazyViewport(state: LazyListState, host: LazyHost? = null, report: (firstIndex: Int, lastIndex: Int, firstOffset: Int) -> Unit) {
  val latest by rememberUpdatedState(report)
  val viewport by remember(state, host) {
    derivedStateOf {
      host?.predicted?.let { Viewport(it.first, it.second, predicted = true) } ?: run {
        val visible = state.layoutInfo.visibleItemsInfo
        Viewport(visible.firstOrNull()?.index ?: 0, visible.lastOrNull()?.index ?: 0, predicted = false)
      }
    }
  }
  if (host != null) {
    LaunchedEffect(state, host) {
      snapshotFlow { state.layoutInfo }.collect(host::measure)
    }
  }
  LaunchedEffect(state, viewport) {
    // A predicted window is reported with offset 0: the list is not there yet. When the fling ends
    // in that same window, one more report carries the real offset (the window is unchanged, so
    // the guest's window does not move).
    latest(viewport.first, viewport.last, if (viewport.predicted) 0 else state.firstVisibleItemScrollOffset)
  }
}

private data class Viewport(val first: Int, val last: Int, val predicted: Boolean)
