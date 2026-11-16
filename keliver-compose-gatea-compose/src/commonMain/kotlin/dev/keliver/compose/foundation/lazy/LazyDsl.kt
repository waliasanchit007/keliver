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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.compose.gatea.compose.ExtraLazyItem
import dev.keliver.compose.wire.KeliverGeneratedApi
import dev.keliver.lazylayout.compose.LazyListState as WindowState
import dev.keliver.lazylayout.compose.LoadingStrategy
import dev.keliver.lazylayout.compose.ScrollOptimizedLoadingStrategy

/*
 * The guest's `LazyListScope` (docs/COMPOSE_GENERATOR_DESIGN.md §16), written by hand: Compose's
 * DSL, with Compose's signatures, behind which the generated `LazyColumn` and `LazyRow` compose only
 * a window of items through the existing lazy-layout windowing (keliver-lazylayout-compose's
 * loading strategy). Each composed item travels as one `ExtraLazyItem` node whose children the host
 * renders inside the real `LazyListScope.items` lambda, so several root composables in one item
 * and `LazyItemScope` modifiers (`fillParentMaxWidth`, `animateItem`) behave as in Compose.
 */

/** The receiver scope of `LazyColumn` and `LazyRow`'s content, as `androidx.compose.foundation.lazy.LazyListScope`. */
public interface LazyListScope {
  public fun item(
    key: Any? = null,
    contentType: Any? = null,
    content: @Composable LazyItemScope.() -> Unit,
  )

  public fun items(
    count: Int,
    key: ((index: Int) -> Any)? = null,
    contentType: (index: Int) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
  )

  @Deprecated(
    "Keliver: sticky headers are not in the generated API yet (Gate B, part b). Use a header item.",
    level = DeprecationLevel.ERROR,
  )
  public fun stickyHeader(
    key: Any? = null,
    contentType: Any? = null,
    content: @Composable LazyItemScope.(stickyHeaderIndex: Int) -> Unit,
  ): Unit = throw UnsupportedOperationException()
}

public inline fun <T> LazyListScope.items(
  items: List<T>,
  noinline key: ((item: T) -> Any)? = null,
  noinline contentType: (item: T) -> Any? = { null },
  crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit,
): Unit = items(
  count = items.size,
  key = if (key != null) { index: Int -> key(items[index]) } else null,
  contentType = { index: Int -> contentType(items[index]) },
) { itemContent(items[it]) }

public inline fun <T> LazyListScope.itemsIndexed(
  items: List<T>,
  noinline key: ((index: Int, item: T) -> Any)? = null,
  crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
  crossinline itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
): Unit = items(
  count = items.size,
  key = if (key != null) { index: Int -> key(index, items[index]) } else null,
  contentType = { index -> contentType(index, items[index]) },
) { itemContent(it, items[it]) }

public inline fun <T> LazyListScope.items(
  items: Array<T>,
  noinline key: ((item: T) -> Any)? = null,
  noinline contentType: (item: T) -> Any? = { null },
  crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit,
): Unit = items(
  count = items.size,
  key = if (key != null) { index: Int -> key(items[index]) } else null,
  contentType = { index: Int -> contentType(items[index]) },
) { itemContent(items[it]) }

public inline fun <T> LazyListScope.itemsIndexed(
  items: Array<T>,
  noinline key: ((index: Int, item: T) -> Any)? = null,
  crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
  crossinline itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
): Unit = items(
  count = items.size,
  key = if (key != null) { index: Int -> key(index, items[index]) } else null,
  contentType = { index -> contentType(index, items[index]) },
) { itemContent(it, items[it]) }

/** The items a content block declares, as intervals (the same model as Compose's own). */
private class Intervals : LazyListScope {
  class Interval(val start: Int, val count: Int, val key: ((Int) -> Any)?, val content: @Composable LazyItemScope.(Int) -> Unit)

  val list = ArrayList<Interval>()
  var count = 0
    private set

  override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
    add(1, if (key != null) { _ -> key } else null) { content() }
  }

  override fun items(
    count: Int,
    key: ((index: Int) -> Any)?,
    contentType: (index: Int) -> Any?,
    itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
  ) {
    add(count, key, itemContent)
  }

  private fun add(n: Int, key: ((Int) -> Any)?, content: @Composable LazyItemScope.(Int) -> Unit) {
    require(n >= 0) { "count should be non-negative ($n)" }
    if (n == 0) return
    list += Interval(count, n, key, content)
    count += n
  }

  fun locate(index: Int): Interval {
    var lo = 0
    var hi = list.size - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) / 2
      if (list[mid].start <= index) lo = mid else hi = mid - 1
    }
    return list[lo]
  }
}

/**
 * The window of a lazy list's items that the guest composes. Generated `LazyColumn`/`LazyRow` stubs
 * use it; screens do not.
 */
public class LazyWindow internal constructor(
  private val state: LazyListState,
  /** Where this list is in the composition (its compound key hash): the key of its measured viewport. */
  private val site: Int = 0,
  private val viewports: MeasuredViewports = MeasuredViewports.Shared,
) {
  private val strategy = ViewportSizedStrategy(
    ScrollOptimizedLoadingStrategy(),
    if (LazyWindowSizing.viewportSized) viewports.rows(site) else null,
  )
  private val window = WindowState(strategy).apply {
    if (state.firstVisibleItemIndex > 0) programmaticScroll(state.firstVisibleItemIndex, animated = false, clobberUserScroll = false)
  }
  private var count = 0
  private var range: IntRange = IntRange.EMPTY
  private var lastCommand = 0
  private var largestFullReport = 0

  /**
   * The window's items, as the list's children: one `ExtraLazyItem` per item in the window, in
   * order. A new value whenever the list recomposes (its items or its window changed), so the
   * children recompose with it: a lambda that only captured this window would be skipped.
   */
  public var items: @Composable () -> Unit = {}
    private set

  internal fun update(content: LazyListScope.() -> Unit) {
    val intervals = Intervals().apply(content)
    state.pendingScroll?.let { c ->
      if (c.id != lastCommand) {
        lastCommand = c.id
        window.programmaticScroll(c.index, c.animated)
      }
    }
    val range = window.loadRange(intervals.count)
    this.count = intervals.count
    this.range = range
    items = { Items(intervals, range) }
  }

  /** Items before the window, which the host shows as placeholders. */
  public val before: Int get() = range.first.coerceAtLeast(0)

  /** Items after the window. */
  public val after: Int get() = (count - before - range.count()).coerceAtLeast(0)

  /** The host's viewport changed: move the window and update the state's mirrored fields. */
  public fun onViewport(firstIndex: Int, lastIndex: Int, firstOffset: Int) {
    state.onHostViewport(firstIndex, lastIndex, firstOffset)
    if (count > 0 && lastIndex >= firstIndex) {
      // The viewport is full when items follow its last visible one: then it holds this many rows
      // here. A list shorter than its viewport only shows that it holds at least as many.
      val rows = lastIndex - firstIndex + 1
      val full = lastIndex < count - 1
      // The largest full report since this list was created, not the last one: rows differ in
      // height, and the list starts again where it started (usually the top), not where the user
      // left it (a stretch of tall rows would store 4 where the top shows 12). A larger hint costs
      // extra rows; a smaller one costs placeholders on screen until the host's first report.
      if (full) largestFullReport = maxOf(largestFullReport, rows)
      viewports.measured(site, rows = if (full) largestFullReport else rows, full = full)
    }
    strategy.hostReported = true
    window.onUserScroll(firstIndex, lastIndex)
  }
}

/**
 * The switch for NF3's viewport-sized first window ([ViewportSizedStrategy]), on by default. Off, a
 * list's first window is the fixed preload again; the guest profile's baseline uses it.
 */
@KeliverGeneratedApi
public object LazyWindowSizing {
  public var viewportSized: Boolean = true

  /** Forgets every list's measured viewport, as a new guest starts (the guest profile's runs). */
  public fun forgetMeasuredViewports() {
    MeasuredViewports.Shared.clear()
  }
}

/**
 * Items composed past the measured viewport on a list's first composition: a partly shown row. One
 * row, not a share of the viewport, because the hint is already the largest viewport the list
 * showed (see [LazyWindow.onViewport]), and every row of margin is composed on every return.
 */
internal const val VIEWPORT_MARGIN_ROWS = 1

/**
 * NF3's viewport-sized first window: until the host first reports the list's viewport, the window is
 * the rows the host last measured for a list at the same place in the composition, plus
 * [VIEWPORT_MARGIN_ROWS], instead of [ScrollOptimizedLoadingStrategy]'s fixed preload. With nothing
 * measured yet (the first time a list is shown) the fixed preload applies, as before. Once the host
 * has reported, [delegate] alone decides, so scrolling is unchanged.
 */
internal class ViewportSizedStrategy(
  private val delegate: LoadingStrategy,
  private val measuredRows: Int?,
) : LoadingStrategy by delegate {
  /**
   * Snapshot state, read by [loadRange]: the host's first report must recompose the list even when
   * it leaves the delegate's own state as it was (a first report of exactly the delegate's first
   * window), or the rows past the hint would stay placeholders until the next scroll.
   */
  var hostReported by mutableStateOf(false)

  override fun loadRange(itemCount: Int): IntRange {
    // The delegate always runs, so its scroll history is the same as without the cap.
    val range = delegate.loadRange(itemCount)
    // Read only while the cap applies: a list without a hint does not recompose for the report.
    if (measuredRows == null || range.isEmpty() || hostReported) return range
    val last = minOf(range.last, range.first + measuredRows + VIEWPORT_MARGIN_ROWS - 1)
    return range.first..last
  }
}

/**
 * Rows per viewport as the host measured them (its viewport's height over its rows' heights, seen
 * in the indices it reports), by a list's place in the composition. A list that leaves the
 * composition and comes back at the same place (back to a screen that was not retained) starts with
 * a window the size of what the host showed last time. A hint only: a wrong one (the place now holds
 * another list, the screen rotated) costs placeholders or extra rows until the host's first report,
 * which corrects the window.
 *
 * The place includes every `key(...)` above the list. `KeliverNavHost` composes each back-stack entry
 * under its entry id (`SaveableStateProvider(entry.id)`), so the hint is per entry: going back to an
 * entry that was disposed finds its own measurement, but a new push of the same screen is a new
 * entry, a new place, and starts with the fixed preload. (Expected from the code; not run under
 * `KeliverNavHost`.) The guest profile's back step is the case that benefits.
 *
 * In-process JVM guests (tests, the editor's JVM renderers) share [Shared] across every list in the
 * process; tests clear it with [LazyWindowSizing.forgetMeasuredViewports].
 */
internal class MeasuredViewports(private val capacity: Int = 64) {
  private val rows = LinkedHashMap<Int, Int>()

  fun rows(site: Int): Int? = rows[site]

  fun clear() = rows.clear()

  fun measured(site: Int, rows: Int, full: Boolean) {
    val known = this.rows.remove(site)
    // A short list never lowers what a full one measured at the same place.
    this.rows[site] = if (full || known == null) rows else maxOf(known, rows)
    if (this.rows.size > capacity) this.rows.remove(this.rows.keys.first())
  }

  companion object {
    /**
     * One per guest under Zipline (one JS realm on one thread). Not synchronized: on the JVM it is
     * one per process, for single-threaded composition only.
     */
    val Shared = MeasuredViewports()
  }
}

@Composable
private fun Items(intervals: Intervals, range: IntRange) {
  val sent = HashMap<String, Any>()
  for (index in range) {
    val interval = intervals.locate(index)
    val local = index - interval.start
    val itemKey = interval.key?.invoke(local)
    val content = interval.content
    val wire = itemKey?.let(::wireKey)
    if (wire != null) checkWireKey(sent, wire, itemKey)
    key(itemKey ?: index) {
      ExtraLazyItem(key = wire, content = { LazyItemScopeInstance.content(local) })
    }
  }
}

/**
 * A guest key as the host's item key: typed, so `1` and `"1"` stay distinct; strings save in a
 * Bundle. A key of another type travels as its `toString()`, so two different keys that print the
 * same would be one key on the host (which sees only the composed window's keys; the rest are
 * placeholders keyed by index): [checkWireKey] refuses that in the window, in the guest, with a
 * message, where Compose (which compares the keys themselves) would not.
 */
internal fun wireKey(key: Any): String = when (key) {
  is String -> "s:$key"
  is Int -> "i:$key"
  is Long -> "l:$key"
  else -> "o:${key::class.simpleName}:$key"
}

internal fun checkWireKey(sent: MutableMap<String, Any>, wire: String, key: Any) {
  val earlier = sent.put(wire, key) ?: return
  require(earlier == key) {
    "Keliver: the lazy list keys $earlier and $key differ but have the same toString(), which is how a key " +
      "that is not a String, Int or Long reaches the host. Use a String, Int or Long key, or give the key type a distinct toString()."
  }
  // An equal key twice is Compose's own error, which the host reports as Compose does.
}

@Composable
public fun rememberLazyWindow(state: LazyListState, content: LazyListScope.() -> Unit): LazyWindow {
  val site = currentCompositeKeyHash
  val window = remember(state) { LazyWindow(state, site) }
  window.update(content)
  return window
}
