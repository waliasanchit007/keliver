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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/*
 * NF3's pre-composition (docs/PLAN_2026-11.md, WS7): a finger stays down about 100 ms before a tap
 * fires. If the guest learns on touch-down which screen the tap will open, it can compose that screen
 * in those 100 ms, hidden, and the tap only has to show it.
 *
 * [KeliverNavPreparation] is told what is about to open and what was cancelled; [KeliverPreparedStack]
 * composes the prepared screen as a hidden entry of the retained stack's frame (the same path as
 * [KeliverNavHost], whose controller prepares with `KeliverNavController.prepare`), keyed by the
 * screen's key, so when the tap pushes that key the entry that is already composed becomes the top.
 * Nothing is composed a second time: only the frame's own widget changes, as when a retained entry is
 * shown again.
 *
 * The host's press signal ([KeliverNavPress], [prepareOnPress]) calls prepare on touch-down and
 * cancel when the press ends without a click, on a host that declares it (D2). Guest code that knows
 * earlier (a screen's most likely next step) may call [KeliverNavPreparation.prepare] itself.
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
 * A cancelled preparation is disposed (its work is lost; its effects are cancelled). So is one the
 * stack moved past: when [keys] change without containing the prepared key (the tap opened another
 * screen, or a back press popped the stack during the press), the preparation is dropped as a
 * cancel would drop it.
 *
 * [depth] keeps that many entries below the top composed and hidden, as [KeliverNavRetention] does
 * (0: only the top, as a plain conditional).
 *
 * A prepared entry is [KeliverNavEntryLifecycle.Hidden] until it is shown and its `BackHandler`s are
 * off, so a screen that loads data with `LaunchedEffect` starts loading on touch-down (wanted: the
 * request starts 100 ms earlier) and one that uses [LaunchedWhileVisible] waits for the tap.
 *
 * Side effects run on press. Every `LaunchedEffect` and `DisposableEffect` of a prepared screen runs
 * on touch-down, and again on every press that is then cancelled (the finger moved away, a scroll
 * started on the row): a screen that records analytics ("screen viewed"), marks something as read,
 * or creates something (an order, a session) in such an effect does so for screens the user never
 * opened. Put those effects in [LaunchedOnceShown], which waits for the tap and runs once (or in
 * [LaunchedWhileVisible] for work that lasts while the screen is shown). Both "about to be shown"
 * and "shown before and now below the top" are [KeliverNavEntryLifecycle.Hidden];
 * [LaunchedOnceShown] and [LaunchedOnReturn] tell them apart.
 *
 * Without a press this stack composes what a plain stack would, but its frame's wrappers still run:
 * the guest profile measured open at 30.98 ms against 28.06 ms for the shipped stack (+10%, Mac).
 *
 * Caveat: what [content] reads must not change at the tap, or the prepared entry recomposes then
 * (for example a counter of opens that the click increments and the screen shows).
 *
 * On a [KeliverNavHostFrame] (a host container that shows its last entry) the prepared entry goes
 * first, under the others, so the host never shows it and never goes back to it; when it is pushed
 * its entry moves to the top (the host moves its native tree, nothing is created). The host never
 * goes back on its own here: back goes through the guest (use [KeliverNavHost] for host back).
 */
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
  // The stack changed since the last composition without the prepared key (the tap opened something
  // else; a back press or a pop arrived during the press): the preparation is stale. Drop it now, or
  // it would stay composed, hidden, with its effects running, until the next press. Compared with
  // the last composition, not with the stack when the press started, so a press and a click that
  // arrive in the same frame are handled too.
  val lastKeys = remember { LastKeys<K>() }
  val stackChanged = lastKeys.keys.let { it != null && it != keys }
  // A copy: a caller may pass a state list it changes in place (the controller's own entries are
  // one), and a stored reference to it would always equal the current stack.
  SideEffect { lastKeys.keys = keys.toList() }
  val requested = preparation.prepared
  val prepared = requested?.takeUnless { stackChanged && it !in keys }
  if (requested != null && prepared == null) {
    SideEffect { preparation.cancel(requested) }
  }
  if (prepared != null && prepared in keys) {
    SideEffect { preparation.adopted(prepared) }
  }

  // The retained stack's own path (KeliverNavHost's too), with the frame even at depth 0. On a
  // KeliverNavHostFrame the host never goes back on its own here (no onHostBack).
  val ids = remember { KeyIds() }
  SideEffect { ids.retain(keys + listOfNotNull(prepared)) }
  val retention = remember(frame, depth) { KeliverNavRetention(frame, depth) }
  RetainedEntries(
    items = keys,
    keyOf = { it },
    retention = retention,
    content = content,
    idOf = { ids.idOf(it) },
    prepared = prepared,
    framed = true,
  )
}

/** The keys of the last composition that was applied. */
private class LastKeys<K> {
  var keys: List<K>? = null
}
