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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
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
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/*
 * The host half of a generated lazy list (docs/COMPOSE_GENERATOR_DESIGN.md §16), written by hand
 * once: the real `LazyColumn`/`LazyRow` gets the whole item count, of which the guest composed a
 * window; items outside it are placeholders until the window reaches them.
 *
 * Scrolling (NF1, docs/PLAN_2026-11.md WS7): the guest composes on one thread, and a window it has
 * started composing cannot be cancelled. So the host tells it only where the list IS, or, during a
 * fling, where the list WILL COME TO REST ([LazyHost.fling] predicts that as the fling starts,
 * from the platform fling itself). The windows a fling passes through are never reported, so the
 * guest never composes them; their rows are drawn as placeholders of the rows' measured size.
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
 * travels as far as it would over real rows; and it is drawn (a rounded block), never blank. In a
 * horizontal list it takes the list's measured height ([LazyHost.crossExtentPx]), so a row of
 * cards keeps its height while it shows placeholders. It is not content: screen readers skip it.
 */
@Composable
private fun LazyPlaceholder(host: LazyHost?) {
  val density = LocalDensity.current
  fun Float.orDefault(): Dp = if (this > 0f) with(density) { toDp() } else DefaultPlaceholderExtent
  val extent = (host?.itemExtentPx ?: 0f).orDefault()
  val modifier = if (host == null || host.vertical) {
    Modifier.fillMaxWidth().height(extent).padding(horizontal = 16.dp, vertical = 4.dp)
  } else {
    Modifier.width(extent).height(host.crossExtentPx.orDefault()).padding(horizontal = 4.dp, vertical = 8.dp)
  }
  Box(modifier.clearAndSetSemantics {}.background(PlaceholderColor, RoundedCornerShape(6.dp)))
}

/**
 * What the host keeps for one generated lazy list: its fling, which predicts where the list will
 * come to rest; and the measured size of the guest's rows, which placeholders take. [vertical] is
 * the list's orientation, which the generator knows (a `LazyRow` passes false), so placeholders
 * are sized right from the first frame.
 */
internal class LazyHost(private val state: LazyListState, internal val vertical: Boolean = true) {
  /** The platform's default fling ([ScrollableDefaults.flingBehavior]), which [fling] runs unchanged. */
  internal var delegate: FlingBehavior? = null

  /** The (first, last) window a running fling will come to rest at; null when no fling runs. */
  internal var predicted: Pair<Int, Int>? by mutableStateOf(null)
    private set

  /** The mean main-axis size of the guest's rows measured so far, in px; 0 until one is. */
  internal var itemExtentPx: Float by mutableFloatStateOf(0f)
    private set

  /**
   * A horizontal list's height (its viewport's cross size) when it last showed only guest rows,
   * in px; 0 until it has. Only such frames count: placeholders take this size, so a frame that
   * shows placeholders would measure their own size back.
   */
  internal var crossExtentPx: Float by mutableFloatStateOf(0f)
    private set

  private val measured = HashSet<Any>()
  private var measuredSum = 0f

  /** Folds the visible guest rows into [itemExtentPx]: a running mean over distinct rows, so it settles. */
  internal fun measure(info: LazyListLayoutInfo) {
    val visible = info.visibleItemsInfo
    if (!vertical && visible.isNotEmpty() && visible.all { it.contentType === GuestItem }) {
      val cross = info.viewportSize.height.toFloat()
      if (cross > 0f && cross != crossExtentPx) crossExtentPx = cross
    }
    if (measured.size >= MAX_MEASURED_ROWS) return
    var changed = false
    for (item in visible) {
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

  /**
   * The list's own fling, unchanged, recording where it will come to rest before it runs. How far
   * it goes is the platform fling's own answer ([flingDistance] runs that same fling against a
   * clock that never waits), so each platform's default decay predicts itself: the spline on
   * Android and desktop, Compose Multiplatform's UIScrollView-like decay on iOS.
   */
  val fling: FlingBehavior = object : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
      val fling = delegate ?: return initialVelocity
      val info = state.layoutInfo
      val here = info.visibleItemsInfo.let { (it.firstOrNull()?.index ?: 0) to (it.lastOrNull()?.index ?: 0) }
      // A fling that stays in this window (a short one) changes nothing: no prediction, no report.
      predicted = info.geometry()?.restingWindow(fling.flingDistance(initialVelocity))?.takeIf { it != here }
      try {
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

/** The [LazyHost] of a generated lazy list with [state]; [vertical] is false for a `LazyRow`. */
@Composable
internal fun rememberLazyHost(state: LazyListState, vertical: Boolean = true): LazyHost {
  val delegate = ScrollableDefaults.flingBehavior()
  val host = remember(state, vertical) { LazyHost(state, vertical).also { it.delegate = delegate } }
  SideEffect { host.delegate = delegate }
  return host
}

/** About 33 s of frames at 60 Hz: past it a probed fling is cut off (no platform fling runs that long). */
private const val MAX_PROBE_FRAMES = 2_000
private const val PROBE_FRAME_NANOS = 16_666_667L

private class ProbeLimit : CancellationException("fling probe cut off")

/**
 * How far this fling travels from [velocity] (px/s, scroll units) when nothing stops it: it runs
 * to its end against a scroll that consumes everything and a frame clock that never waits, so it
 * returns at once. The real fling covers the same distance, up to its frame timing.
 */
internal suspend fun FlingBehavior.flingDistance(velocity: Float): Float {
  var travelled = 0f
  val probe = object : ScrollScope {
    override fun scrollBy(pixels: Float): Float {
      travelled += pixels
      return pixels
    }
  }
  val clock = object : MonotonicFrameClock {
    var frames = 0
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
      if (++frames > MAX_PROBE_FRAMES) throw ProbeLimit()
      return onFrame(frames * PROBE_FRAME_NANOS)
    }
  }
  val fling = this
  try {
    withContext(clock) { with(fling) { probe.performFling(velocity) } }
  } catch (_: ProbeLimit) {
  }
  return travelled
}

/** One laid-out item: its [index], its main-axis [offset] from the content start, and its [size]. */
internal class LaidItem(val index: Int, val offset: Int, val size: Int)

/**
 * What a fling prediction needs of a lazy list's layout ([LazyListLayoutInfo]): the laid-out
 * [items] (consecutive indexes, ascending), the viewport's start and end offsets (the start is
 * negative by the leading content padding), the trailing content padding, the spacing and the
 * item count.
 */
internal class ListGeometry(
  val items: List<LaidItem>,
  val viewportStart: Int,
  val viewportEnd: Int,
  val afterPadding: Int = 0,
  val spacing: Int = 0,
  val totalItems: Int,
)

internal fun LazyListLayoutInfo.geometry(): ListGeometry? {
  if (visibleItemsInfo.isEmpty() || totalItemsCount <= 0) return null
  return ListGeometry(
    items = visibleItemsInfo.map { LaidItem(it.index, it.offset, it.size) },
    viewportStart = viewportStartOffset,
    viewportEnd = viewportEndOffset,
    afterPadding = afterContentPadding,
    spacing = mainAxisItemSpacing,
    totalItems = totalItemsCount,
  )
}

/**
 * The (first, last) window the list shows after scrolling [distance] px (scroll units: positive
 * toward higher indexes), or null when the list moves less than its viewport, or to the window it
 * shows now. The laid-out items count
 * with their real offsets and sizes; items beyond them with the laid-out items' mean size. The
 * distance stops at the list's ends, as the list does.
 */
internal fun ListGeometry.restingWindow(distance: Float): Pair<Int, Int>? {
  if (items.isEmpty() || totalItems <= 0) return null
  val firstLaid = items.first()
  val lastLaid = items.last()
  val mean = items.sumOf { it.size }.toFloat() / items.size
  val step = mean + spacing
  fun laid(j: Int): LaidItem? = items.getOrNull(j - firstLaid.index)?.takeIf { it.index == j }
  fun start(j: Int): Float = laid(j)?.offset?.toFloat() ?: when {
    j < firstLaid.index -> firstLaid.offset - (firstLaid.index - j) * step
    else -> lastLaid.offset + lastLaid.size + spacing + (j - lastLaid.index - 1) * step
  }
  fun end(j: Int): Float = start(j) + (laid(j)?.size?.toFloat() ?: mean)

  // The list stops with its first item at the content start, or its last at the content end.
  val minDistance = minOf(0f, start(0))
  val maxDistance = maxOf(0f, end(totalItems - 1) + afterPadding - viewportEnd)
  fun window(d: Float): Pair<Int, Int> {
    // The lowest index whose end is past the viewport's start; the highest whose start is before its end.
    val first = lowest(0, totalItems - 1) { end(it) - d > viewportStart } ?: (totalItems - 1)
    val afterLast = lowest(first, totalItems - 1) { start(it) - d >= viewportEnd } ?: totalItems
    return first to maxOf(first, afterLast - 1)
  }
  val travel = distance.coerceIn(minDistance, maxDistance)
  // A fling shorter than the viewport stays among the rows on screen: nothing to predict, and the
  // list's own reports follow it (a release at rest sends nothing).
  if (abs(travel) < viewportEnd - viewportStart) return null
  return window(travel).takeIf { it != window(0f) }
}

/** The lowest index in [from]..[to] for which the monotonic [test] holds; null if none does. */
private inline fun lowest(from: Int, to: Int, test: (Int) -> Boolean): Int? {
  var lo = from
  var hi = to + 1
  while (lo < hi) {
    val mid = (lo + hi) ushr 1
    if (test(mid)) hi = mid else lo = mid + 1
  }
  return lo.takeIf { it <= to }
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
