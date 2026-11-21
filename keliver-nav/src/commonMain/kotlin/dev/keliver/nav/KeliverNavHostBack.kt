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
package dev.keliver.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.keliver.compose.ExperimentalKeliverBackApi
import dev.keliver.compose.LocalBackHandlersEnabled
import dev.keliver.compose.LocalOnBackPressedDispatcher
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher

/**
 * A [KeliverNavFrame] whose container is a host widget that shows the top entry and hides the
 * others itself (gate A's `ExtraRetained`), and that can go back **on the host**: on a system back
 * press it shows the entry below in the frame of the press, with no guest round trip, and tells the
 * guest, whose state then catches up.
 *
 * keliver-nav calls only [HostStack] and [HostEntry] on such a frame; [Stack] and [Entry] are the
 * frame without host back. A bundle that uses a host frame needs a host that has its container
 * (D2: gate it like the schema it comes from). Apps for hosts without it keep a [KeliverNavFrame]
 * of plain widgets, whose back goes through the guest.
 *
 * The protocol, for a frame's implementer:
 * - [HostStack] emits the container with [HostEntry]s as its children, bottom first; the host shows
 *   the last one.
 * - When the shown entry's `hostBack` is true and it is not the first, a back press shows the entry
 *   below and calls `onBack` with that entry's id. The host keeps showing that entry until
 *   `backHandled` reaches the number of `onBack` calls it has made, then shows the last entry again.
 * - keliver-nav handles `onBack` against the stack as it is then (two presses handled in one guest
 *   tick see the first one's pop): if the guest's top entry has an enabled `BackHandler` (one
 *   enabled after the host last heard otherwise), that handler takes the press and nothing is
 *   popped (the host shows the top again). If instead the top entry last told the host it may not
 *   go back from it, nothing is popped and **the press is dropped**: no handler runs (the top's
 *   handlers are still gated off while the host hides it), so the user presses again. A host only
 *   sends such an `onBack` from a stale `hostBack`, since one that is current re-dispatches the press
 *   through the guest instead. Otherwise the stack is popped to the entry the host shows, even if the
 *   guest pushed entries meanwhile. Either way `backHandled` grows by one in the same batch.
 * - An entry never shown (below the top of a deep link) tells the host it may not go back from it,
 *   since nothing is known of its handlers yet.
 *
 * Compatibility (D2): the container is two widgets of gate A's schema (tags 502 and 503) added as
 * extras, outside the schema lock. A host that does not have them THROWS while applying the batch
 * that creates them (the parent's children change names a widget it never created), which takes
 * down the whole content, not just the stack. Gate A is unpublished and prototype-only, so no
 * shipped host can meet such a bundle today.
 *
 * Two gates keep such a bundle off a host without the container:
 * - At run time (0.5.0): [KeliverNavDefaults] uses a host frame only when the host declares
 *   [KeliverNavDefaults.RETAINED_CONTAINER_FEATURE] (`TreehouseApp.Spec.hostFeatures`, read by the
 *   guest as `dev.keliver.compose.LocalHostFeatures`). A host older than 0.5.0 declares nothing, so
 *   the guest falls back to its plain [KeliverNavFrame] and never emits tags 502/503 there. The
 *   declaration is typed by hand: a host that declares the feature without rendering the container
 *   still crashes as above, so declare it only together with the widgets and the back dispatcher.
 * - At selection (still to do before gate A leaves the prototype): extras must be in the versioned
 *   surface (a level or widget-version bump with each new tag), the D2 bundle gate (the index's
 *   widgetVersion) must read that number, and a test must show an older host refusing such a
 *   bundle at selection rather than crashing. That covers code passing a host frame explicitly,
 *   which the run-time gate does not see.
 *
 * Known gaps (follow-ups, see the host's `LocalHostBackDispatcher`): host back is ahead of an
 * enabled guest `BackHandler` outside the stack (overlays that take back must live inside an
 * entry); no predictive back; no host-back trigger on iOS yet.
 */
@ExperimentalKeliverNavRetentionApi
@Stable
public interface KeliverNavHostFrame : KeliverNavFrame {
  /**
   * The container. [backHandled] is how many [onBack] calls the guest has handled; [onBack] is
   * called with the id of the entry the host went back to.
   */
  @Composable
  public fun HostStack(backHandled: Int, onBack: (entryId: String) -> Unit, content: @Composable () -> Unit)

  /**
   * One entry, [entryId] unique in its stack. [hostBack]: the host may go back from this entry on
   * its own (no `BackHandler` in it was enabled when it was last shown, and the stack can be popped
   * by the guest). Its value only changes when the entry is shown or a handler in it is enabled or
   * disabled; showing an entry never re-invokes [content] with anything new.
   */
  @Composable
  public fun HostEntry(entryId: String, hostBack: Boolean, content: @Composable () -> Unit)
}

/**
 * [RetainedEntries] on a [KeliverNavHostFrame]: [window] (already cut to the retention depth) in the
 * frame's host container, each entry's `BackHandler`s tracked so the host is told whether it may go
 * back from it. [onHostBack] null: the host never goes back on its own (every entry's `hostBack` is
 * false) and the container only hides.
 */
@OptIn(ExperimentalKeliverNavRetentionApi::class, ExperimentalKeliverBackApi::class)
@Composable
internal fun <T : Any> HostRetainedEntries(
  items: List<T>,
  window: List<T>,
  keyOf: (T) -> Any,
  idOf: (T) -> String,
  frame: KeliverNavHostFrame,
  onHostBack: ((T) -> Unit)?,
  content: @Composable (T) -> Unit,
  liveItems: (() -> List<T>)? = null,
  prepared: T? = null,
) {
  val parentLifecycle = LocalKeliverNavEntryLifecycle.current
  val parentBackHandlers = LocalBackHandlersEnabled.current
  val dispatcher = LocalOnBackPressedDispatcher.current
  val handled = remember { mutableIntStateOf(0) }
  val trackers = remember { HashMap<Any, BackHandlerTracker>() }
  // What each entry last told the host (hostBack): an entry told "not free" is never left on the
  // host's word, whatever its handlers say now (hidden, they are disabled by the gate).
  val told = remember { HashMap<Any, Boolean>() }
  // The stack as it is now, not as last composed: two presses handled in one guest tick must see
  // the first one's pop. [liveItems] reads the owner's state; otherwise the pop is applied here.
  val live = remember { LiveStack(items) }
  SideEffect { live.items = items }
  val currentLiveItems by rememberUpdatedState(liveItems)
  val currentKeyOf by rememberUpdatedState(keyOf)
  val currentIdOf by rememberUpdatedState(idOf)
  val currentOnHostBack by rememberUpdatedState(onHostBack)
  val onBack: (String) -> Unit = remember {
    { id ->
      handled.intValue++
      val stack = currentLiveItems?.invoke() ?: live.items
      val top = stack.last()
      val topKey = currentKeyOf(top)
      // A handler enabled in the top after the host last heard of it: the press was its, as it would
      // have been on the guest path. Nothing is popped; the host shows the top again. The same if the
      // top told the host it may not leave it (an entry never shown, or one that had a handler
      // enabled when last shown): the host's view was stale, and that press is dropped (its handlers
      // are gated off while the host hides the entry, so none can take it).
      val refused = trackers[topKey]?.dispatchToNewestEnabled() == true || told[topKey] == false
      if (!refused) {
        val index = stack.indexOfLast { currentIdOf(it) == id }
        if (index >= 0 && index != stack.lastIndex) {
          currentOnHostBack?.invoke(stack[index])
          live.items = stack.subList(0, index + 1).toList()
        }
      }
    }
  }
  val stackIndex = HashMap<Any, Int>(items.size)
  items.forEachIndexed { i, it -> stackIndex[keyOf(it)] = i }
  // NF3: an entry being prepared goes first, under the window. The host shows the last entry and
  // goes back only to the one under the shown one, so it never shows a prepared entry and never
  // goes back to it; when the entry is pushed its group moves to the end (a move on the host, its
  // composition kept).
  val entries = if (prepared != null) listOf(prepared) + window else window
  val offset = entries.size - window.size
  frame.HostStack(handled.intValue, onBack) {
    for ((position, item) in entries.withIndex()) {
      val itemKey = keyOf(item)
      val index = position - offset
      // The host goes back to the entry it shows below this one, so it may only when that is the
      // entry below in the stack: not when the one between was not kept (a route that opted out of
      // retention), or the host would skip a screen, and not to a prepared entry.
      val below = (stackIndex[itemKey] ?: 0) - 1
      val nextBelowIsShown = when {
        index < 0 -> false
        index == 0 -> offset == 0
        else -> below >= 0 && keyOf(items[below]) == keyOf(window[index - 1])
      }
      key(itemKey) {
        val top = index == window.lastIndex
        val lifecycle = remember(parentLifecycle, parentBackHandlers) {
          EntryLifecycle(top, parentLifecycle, parentBackHandlers)
        }
        SideEffect { lifecycle.update(top) }
        CountShows(lifecycle)
        val tracker = remember(dispatcher) { BackHandlerTracker(dispatcher) }
        DisposableEffect(itemKey, tracker) {
          trackers[itemKey] = tracker
          onDispose { if (trackers[itemKey] === tracker) trackers.remove(itemKey) }
        }
        DisposableEffect(itemKey) {
          onDispose { told.remove(itemKey) }
        }
        HostEntryFrame(frame, idOf(item), onHostBack != null && nextBelowIsShown, lifecycle.state, tracker, { told[itemKey] = it }) {
          CompositionLocalProvider(
            LocalKeliverNavEntryLifecycle provides lifecycle.state,
            LocalKeliverNavEntryShows provides lifecycle.shows,
            LocalBackHandlersEnabled provides lifecycle.backHandlersEnabled,
            LocalOnBackPressedDispatcher provides tracker,
          ) {
            EntryContent(item, content)
          }
        }
      }
    }
  }
}

/**
 * The frame's entry, with `hostBack` read here so a handler enabled or disabled recomposes this and
 * not the stack. While hidden, an entry keeps the value it had when last shown: its handlers are
 * disabled by the gate then, which says nothing about whether they are enabled once it is shown. An
 * entry never shown (one below the top of a deep link) is not free: nothing is known of its handlers
 * until it is shown, so the press that would leave it goes through the guest. [onTold] hears each
 * value sent.
 */
@OptIn(ExperimentalKeliverNavRetentionApi::class)
@Composable
private fun HostEntryFrame(
  frame: KeliverNavHostFrame,
  entryId: String,
  canPop: Boolean,
  lifecycle: State<KeliverNavEntryLifecycle>,
  tracker: BackHandlerTracker,
  onTold: (Boolean) -> Unit,
  content: @Composable () -> Unit,
) {
  val lastShown = remember { BooleanBox(false) }
  val visible = lifecycle.value == KeliverNavEntryLifecycle.Visible
  val free = if (visible) !tracker.anyEnabled.value else lastShown.value
  val hostBack = canPop && free
  SideEffect {
    if (visible) lastShown.value = free
    onTold(hostBack)
  }
  frame.HostEntry(entryId, hostBack, content)
}

private class LiveStack<T>(var items: List<T>)

private class BooleanBox(var value: Boolean)

/**
 * The dispatcher one retained entry's `BackHandler`s register with: it passes every callback on to
 * [delegate] and tracks whether any of them is enabled, so the host is told when it must not go
 * back from the entry on its own.
 */
internal class BackHandlerTracker(private val delegate: OnBackPressedDispatcher) : OnBackPressedDispatcher {
  private val callbacks = ArrayList<OnBackPressedCallback>()
  private val enabled = mutableStateOf(false)

  /** Whether a callback added here is enabled. */
  val anyEnabled: State<Boolean> get() = enabled

  override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
    val cancellable = delegate.addCallback(onBackPressedCallback)
    // The delegate may forward enabled changes through this slot (the guest's bridge to the host):
    // keep its callback and add ours.
    val previous = onBackPressedCallback.enabledChangedCallback
    val ours: () -> Unit = {
      previous?.invoke()
      recompute()
    }
    onBackPressedCallback.enabledChangedCallback = ours
    callbacks += onBackPressedCallback
    recompute()
    return object : Cancellable {
      override fun cancel() {
        if (onBackPressedCallback.enabledChangedCallback === ours) {
          onBackPressedCallback.enabledChangedCallback = previous
        }
        callbacks -= onBackPressedCallback
        recompute()
        cancellable.cancel()
      }
    }
  }

  /** Calls the newest enabled callback (the innermost handler); false if none is enabled. */
  fun dispatchToNewestEnabled(): Boolean {
    val callback = callbacks.lastOrNull { it.isEnabled } ?: return false
    callback.handleOnBackPressed()
    return true
  }

  private fun recompute() {
    val now = callbacks.any { it.isEnabled }
    if (enabled.value != now) enabled.value = now
  }
}

/** Stable string ids for an app's own keys (unique in the stack), for the host to name an entry by. */
internal class KeyIds {
  private var next = 0L
  private val ids = HashMap<Any, String>()

  fun idOf(key: Any): String = ids.getOrPut(key) { (next++).toString() }

  /** Forgets keys no longer in the stack. */
  fun retain(keys: Collection<Any>) {
    ids.keys.retainAll(keys.toSet())
  }
}
