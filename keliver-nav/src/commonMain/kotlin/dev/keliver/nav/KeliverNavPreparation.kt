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
package dev.keliver.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.compose.ExperimentalKeliverBackApi
import dev.keliver.compose.LocalBackHandlersEnabled

/*
 * NF3's pre-composition (docs/PLAN_2026-11.md, WS7): a finger stays down about 100 ms before a tap
 * fires. If the guest learns on touch-down which screen the tap will open, it can compose that screen
 * in those 100 ms, hidden, and the tap only has to show it.
 *
 * Built here: the guest's half. [KeliverNavPreparation] is told what is about to open and what was
 * cancelled; [KeliverPreparedStack] composes the prepared screen as a hidden entry of the same
 * [KeliverNavFrame] the retained stack uses, keyed by the screen's key, so when the tap pushes that
 * key the entry that is already composed becomes the top. Nothing is composed a second time: only
 * the frame's own widget changes, as when a retained entry is shown again.
 *
 * Not built: the host's touch-down signal. A host learns about a press before the click, but the
 * protocol has no event for it yet; see the PR that added this file for the design (a schema
 * modifier carrying press and cancel, gated by the widget version as any schema change is, D2).
 * Until then [KeliverNavPreparation.prepare] can be called by guest code that knows earlier (a
 * prefetch on a list's first visible rows, a screen's most likely next step).
 */

/**
 * What is about to open: set on touch-down by the press handler of something that navigates, and
 * cleared on cancel (the finger moved away or the press was taken by a scroll). One at a time: a
 * second [prepare] replaces the first.
 */
@ExperimentalKeliverNavRetentionApi
@Stable
public class KeliverNavPreparation<K : Any> {
  /** The key being composed ahead of its tap, or null. */
  public var prepared: K? by mutableStateOf(null)
    private set

  /** The finger went down on something that opens [key]: start composing it, hidden. */
  public fun prepare(key: K) {
    prepared = key
  }

  /** The press on [key] was cancelled: drop its composition, if it is the one being prepared. */
  public fun cancel(key: K) {
    if (prepared == key) prepared = null
  }

  /** Drop whatever is being prepared. */
  public fun cancel() {
    prepared = null
  }

  /** The prepared key reached the stack: it is a normal entry now. */
  internal fun adopted(key: K) {
    if (prepared == key) prepared = null
  }
}

/**
 * A back stack ([keys] bottom to top, unique; the last one shown) that also composes
 * [preparation]'s key, hidden by [frame], while it is not in [keys]. When it is pushed (it becomes
 * `keys.last()`), its composition is the one already made: the tap shows it without composing it.
 * A cancelled preparation is disposed (its work is lost; its effects are cancelled).
 *
 * [depth] keeps that many entries below the top composed and hidden, as [KeliverNavRetention] does
 * (0: only the top, as a plain conditional).
 *
 * A prepared entry is [KeliverNavEntryLifecycle.Hidden] until it is shown and its `BackHandler`s are
 * off, so a screen that loads data with `LaunchedEffect` starts loading on touch-down (wanted: the
 * request starts 100 ms earlier) and one that uses [LaunchedWhileVisible] waits for the tap. A
 * cancelled press therefore can cost a request; screens whose loads must not run on a press that is
 * cancelled use [LaunchedWhileVisible].
 *
 * Caveat: what [content] reads must not change at the tap, or the prepared entry recomposes then
 * (for example a counter of opens that the click increments and the screen shows).
 */
@OptIn(ExperimentalKeliverBackApi::class)
@ExperimentalKeliverNavRetentionApi
@Composable
public fun <K : Any> KeliverPreparedStack(
  keys: List<K>,
  preparation: KeliverNavPreparation<K>,
  frame: KeliverNavFrame,
  depth: Int = 0,
  content: @Composable (K) -> Unit,
) {
  require(keys.isNotEmpty()) { "keys must not be empty" }
  require(keys.size == 1 || keys.toSet().size == keys.size) { "keys must be unique: $keys" }
  require(depth >= 0) { "depth must not be negative: $depth" }
  val prepared = preparation.prepared
  val top = keys.last()
  if (prepared != null && prepared in keys) {
    SideEffect { preparation.adopted(prepared) }
  }

  // As the retained stack: only entries composed last time stay below the top (an evicted entry is
  // not composed again, hidden, on the way back).
  val composedKeys = remember { HashSet<K>() }
  val candidates = keys.takeLast(depth + 1)
  val window = candidates.filterIndexed { i, it -> i == candidates.lastIndex || it in composedKeys }
  SideEffect {
    composedKeys.clear()
    composedKeys.addAll(window)
  }
  val entries = if (prepared != null && prepared !in keys) window + prepared else window

  val parentLifecycle = LocalKeliverNavEntryLifecycle.current
  val parentBackHandlers = LocalBackHandlersEnabled.current
  frame.Stack {
    for (entry in entries) {
      key(entry) {
        val visible = entry == top
        val lifecycle = remember(parentLifecycle, parentBackHandlers) {
          PreparedEntryLifecycle(visible, parentLifecycle, parentBackHandlers)
        }
        SideEffect { lifecycle.shown = visible }
        frame.Entry(visible) {
          CompositionLocalProvider(
            LocalKeliverNavEntryLifecycle provides lifecycle.state,
            LocalBackHandlersEnabled provides lifecycle.backHandlersEnabled,
          ) {
            PreparedEntryContent(entry, content)
          }
        }
      }
    }
  }
}

/** A skippable boundary: showing a prepared entry re-runs the frame, never the entry's content. */
@Composable
private fun <K> PreparedEntryContent(key: K, content: @Composable (K) -> Unit) {
  content(key)
}

@OptIn(ExperimentalKeliverNavRetentionApi::class)
private class PreparedEntryLifecycle(
  shown: Boolean,
  parentLifecycle: State<KeliverNavEntryLifecycle>,
  parentBackHandlers: State<Boolean>,
) {
  private val top = mutableStateOf(shown)

  var shown: Boolean
    get() = top.value
    set(value) {
      if (top.value != value) top.value = value
    }

  val state: State<KeliverNavEntryLifecycle> = derivedStateOf {
    if (top.value && parentLifecycle.value == KeliverNavEntryLifecycle.Visible) {
      KeliverNavEntryLifecycle.Visible
    } else {
      KeliverNavEntryLifecycle.Hidden
    }
  }

  val backHandlersEnabled: State<Boolean> = derivedStateOf {
    state.value == KeliverNavEntryLifecycle.Visible && parentBackHandlers.value
  }
}
