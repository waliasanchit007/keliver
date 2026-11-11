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
package dev.keliver.compose.foundation.lazy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.keliver.compose.gatea.compose.ExtraLazyItem
import dev.keliver.lazylayout.compose.LazyListState as WindowState

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
public class LazyWindow internal constructor(private val state: LazyListState) {
  private val window = WindowState().apply {
    if (state.firstVisibleItemIndex > 0) programmaticScroll(state.firstVisibleItemIndex, animated = false, clobberUserScroll = false)
  }
  private var count = 0
  private var range: IntRange = IntRange.EMPTY
  private var lastCommand = 0

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
    window.onUserScroll(firstIndex, lastIndex)
  }
}

@Composable
private fun Items(intervals: Intervals, range: IntRange) {
  for (index in range) {
    val interval = intervals.locate(index)
    val local = index - interval.start
    val itemKey = interval.key?.invoke(local)
    val content = interval.content
    key(itemKey ?: index) {
      ExtraLazyItem(key = itemKey?.let(::wireKey), content = { LazyItemScopeInstance.content(local) })
    }
  }
}

/** A guest key as the host's item key: typed, so `1` and `"1"` stay distinct; strings save in a Bundle. */
private fun wireKey(key: Any): String = when (key) {
  is String -> "s:$key"
  is Int -> "i:$key"
  is Long -> "l:$key"
  else -> "o:$key"
}

@Composable
public fun rememberLazyWindow(state: LazyListState, content: LazyListScope.() -> Unit): LazyWindow {
  val window = remember(state) { LazyWindow(state) }
  window.update(content)
  return window
}
