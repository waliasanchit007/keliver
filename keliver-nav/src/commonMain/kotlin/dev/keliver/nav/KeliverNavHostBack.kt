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
 * - keliver-nav handles `onBack`: if the guest's top entry has an enabled `BackHandler` (one enabled
 *   after the host last heard otherwise), that handler takes the press and nothing is popped (the
 *   host shows the top again); otherwise the stack is popped to the entry the host shows, even if
 *   the guest pushed entries meanwhile. Either way `backHandled` grows by one in the same batch.
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
) {
  val parentLifecycle = LocalKeliverNavEntryLifecycle.current
  val parentBackHandlers = LocalBackHandlersEnabled.current
  val dispatcher = LocalOnBackPressedDispatcher.current
  val handled = remember { mutableIntStateOf(0) }
  val trackers = remember { HashMap<Any, BackHandlerTracker>() }
  val currentItems by rememberUpdatedState(items)
  val currentKeyOf by rememberUpdatedState(keyOf)
  val currentIdOf by rememberUpdatedState(idOf)
  val currentOnHostBack by rememberUpdatedState(onHostBack)
  val onBack: (String) -> Unit = remember {
    { id ->
      handled.intValue++
      val stack = currentItems
      val top = stack.last()
      // A handler enabled in the top after the host last heard of it: the press was its, as it would
      // have been on the guest path. Nothing is popped; the host shows the top again.
      val refused = trackers[currentKeyOf(top)]?.dispatchToNewestEnabled() == true
      if (!refused) {
        val target = stack.lastOrNull { currentIdOf(it) == id }
        if (target != null && target !== top) currentOnHostBack?.invoke(target)
      }
    }
  }
  frame.HostStack(handled.intValue, onBack) {
    for ((index, item) in window.withIndex()) {
      val itemKey = keyOf(item)
      key(itemKey) {
        val top = index == window.lastIndex
        val lifecycle = remember(parentLifecycle, parentBackHandlers) {
          EntryLifecycle(top, parentLifecycle, parentBackHandlers)
        }
        SideEffect { lifecycle.update(top) }
        val tracker = remember(dispatcher) { BackHandlerTracker(dispatcher) }
        DisposableEffect(itemKey, tracker) {
          trackers[itemKey] = tracker
          onDispose { if (trackers[itemKey] === tracker) trackers.remove(itemKey) }
        }
        HostEntryFrame(frame, idOf(item), onHostBack != null, lifecycle.state, tracker) {
          CompositionLocalProvider(
            LocalKeliverNavEntryLifecycle provides lifecycle.state,
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
 * disabled by the gate then, which says nothing about whether they are enabled once it is shown.
 */
@OptIn(ExperimentalKeliverNavRetentionApi::class)
@Composable
private fun HostEntryFrame(
  frame: KeliverNavHostFrame,
  entryId: String,
  canPop: Boolean,
  lifecycle: State<KeliverNavEntryLifecycle>,
  tracker: BackHandlerTracker,
  content: @Composable () -> Unit,
) {
  val lastShown = remember { BooleanBox(true) }
  val visible = lifecycle.value == KeliverNavEntryLifecycle.Visible
  val free = if (visible) !tracker.anyEnabled.value else lastShown.value
  SideEffect { if (visible) lastShown.value = free }
  frame.HostEntry(entryId, canPop && free, content)
}

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
