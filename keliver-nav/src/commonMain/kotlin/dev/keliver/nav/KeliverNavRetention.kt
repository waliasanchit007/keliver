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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import dev.keliver.compose.ExperimentalKeliverBackApi
import dev.keliver.compose.LocalBackHandlersEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * How the entries of a retained back stack are laid out, shown and hidden. Supplied by code that
 * knows the app's schema (keliver-nav does not), because hiding is done with the schema's own
 * widgets and modifiers: no protocol change, so a bundle that retains needs nothing from a host
 * that its schema does not already need (D2).
 *
 * A frame must keep a hidden entry's widgets in the tree (so its native state, such as a list's
 * scroll position, survives) while, on every host, a hidden entry is:
 * - not drawn;
 * - given no input (touch, pointer, keys);
 * - not holding focus or the keyboard (IME), and not reachable by focus traversal;
 * - not exposed to accessibility services (TalkBack, VoiceOver, a screen reader on the web).
 *
 * keliver-nav itself only keeps a hidden entry's `BackHandler`s from taking back presses. Callbacks
 * the guest adds to the `OnBackPressedDispatcher` directly, and back presses a host widget handles
 * itself (a host dialog or sheet), are not gated, so keep them out of hidden entries. A frame
 * built from plain layout modifiers (moved off screen, alpha 0, as the three-way prototype's is) is
 * a stand-in that does not guarantee the focus and accessibility points; a schema-level container
 * that hides an entry is the open decision that retention by default depends on.
 */
@ExperimentalKeliverNavRetentionApi
@Stable
public interface KeliverNavFrame {
  /** The container every composed entry is emitted into; entries must overlap (bottom first). */
  @Composable
  public fun Stack(content: @Composable () -> Unit)

  /**
   * One entry. [visible] is `true` only for the top of the stack. Showing an entry again must not
   * re-invoke [content] with anything new: only the frame's own widget changes.
   */
  @Composable
  public fun Entry(visible: Boolean, content: @Composable () -> Unit)
}

/**
 * A retained back stack: up to [depth] entries below the top stay composed (hidden by [frame]), so
 * going back to one of them composes nothing; the host shows the native tree it already has.
 * Entries further down leave the composition as they do without retention, keeping their
 * `rememberSaveable` state, and are composed again when they are reached.
 *
 * [depth] 0 turns retention off. Each retained entry costs its guest composition and its host
 * widgets in memory, so keep it small on low-RAM phones.
 *
 * What retention does not change:
 * - A hidden entry is not idle: it still recomposes, and sends the host its changes, when state it
 *   reads changes (a shared reload, a ticking clock). Stop such work with [LaunchedWhileVisible].
 * - Back composes nothing only while the content lambda given to [KeliverNavHost] or
 *   [KeliverRetainedStack] keeps its identity. If the caller recomposes on navigation and that
 *   lambda captures a value that changed, Compose re-runs every retained entry's content.
 * - Switching between retention and none, or changing [depth] or [frame], at run time recreates
 *   every entry's composition (their `rememberSaveable` state survives under [KeliverNavHost]).
 */
@ExperimentalKeliverNavRetentionApi
public class KeliverNavRetention(
  public val frame: KeliverNavFrame,
  public val depth: Int = DEFAULT_DEPTH,
) {
  init {
    require(depth >= 0) { "depth must not be negative: $depth" }
  }

  override fun equals(other: Any?): Boolean =
    other is KeliverNavRetention && frame == other.frame && depth == other.depth

  override fun hashCode(): Int = 31 * frame.hashCode() + depth

  override fun toString(): String = "KeliverNavRetention(depth=$depth, frame=$frame)"

  public companion object {
    /** Two screens below the top: back, and back again, without composing. */
    public const val DEFAULT_DEPTH: Int = 2
  }
}

/** Whether a navigation entry is the one on screen. */
@ExperimentalKeliverNavRetentionApi
public enum class KeliverNavEntryLifecycle {
  /** The top of the stack (and any content outside a retained stack). */
  Visible,

  /**
   * Composed, not shown: retained below the top, or inside an enclosing entry that is hidden. It
   * should not start work (polling, timers).
   */
  Hidden,
}

@OptIn(ExperimentalKeliverNavRetentionApi::class)
private val AlwaysVisible: State<KeliverNavEntryLifecycle> = mutableStateOf(KeliverNavEntryLifecycle.Visible)

@OptIn(ExperimentalKeliverNavRetentionApi::class)
internal val LocalKeliverNavEntryLifecycle = staticCompositionLocalOf { AlwaysVisible }

/**
 * The enclosing navigation entry's lifecycle, as a [State]. Reading `.value` in a composable
 * recomposes it when the entry is shown or hidden; reading it in an effect (for example through
 * `snapshotFlow`) does not, which keeps going back free of guest composition.
 * Outside a retained stack it is always [KeliverNavEntryLifecycle.Visible].
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun keliverNavEntryLifecycle(): State<KeliverNavEntryLifecycle> = LocalKeliverNavEntryLifecycle.current

/**
 * Like `LaunchedEffect`, but [block] runs only while the enclosing navigation entry is visible: it
 * is cancelled when the entry is hidden (another screen opened over it) and started again, from
 * the beginning, each time the entry is shown, even if it had completed (as Android's
 * `repeatOnLifecycle` does). Showing and hiding an entry does not recompose the caller.
 *
 * Use it for work that runs for as long as a screen is on screen: polling, subscriptions, timers.
 * Do not use it for a one-shot load: going back would run the load again (a request, and a
 * recomposition of the screen with the new data, on the path that otherwise composes nothing).
 * Load once with `LaunchedEffect`; a hidden entry keeps what it loaded.
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun LaunchedWhileVisible(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
  val lifecycle = LocalKeliverNavEntryLifecycle.current
  LaunchedEffect(lifecycle, *keys) {
    snapshotFlow { lifecycle.value }.collectLatest {
      if (it == KeliverNavEntryLifecycle.Visible) coroutineScope { block() }
    }
  }
}

/** How many times the enclosing entry has become visible (1 outside a retained stack). */
internal val LocalKeliverNavEntryShows = staticCompositionLocalOf<State<Int>> { ShownOnce }

private val ShownOnce: State<Int> = mutableIntStateOf(1)

/**
 * Like `LaunchedEffect`, but [block] waits until the enclosing navigation entry has been shown: at
 * once for a screen pushed on top, at the tap for a screen composed ahead of it (NF3's prepared
 * screens), and never for a prepared screen whose press was cancelled. It runs once per
 * composition of the effect (and again if [keys] change), not on each show; hiding the entry does
 * not cancel it.
 *
 * "Once" is per tracking of the entry, not per screen forever: if what encloses the stack changes
 * the entry's lifecycle source (its parent entry's lifecycle or back-handler gate is provided anew),
 * the count of shows starts again and [block] runs again once the entry is shown. Make [block] safe
 * to repeat (idempotent, or guarded by state the screen keeps) where a duplicate would matter.
 *
 * Use it for what must happen only for a screen the user saw: "screen viewed" analytics, marking
 * something read, creating something (an order, a session). Loads stay in `LaunchedEffect`, so a
 * prepared screen starts loading on touch-down.
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun LaunchedOnceShown(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
  val shows = LocalKeliverNavEntryShows.current
  LaunchedEffect(shows, *keys) {
    snapshotFlow { shows.value }.first { it >= 1 }
    block()
  }
}

/**
 * Runs [block] each time the enclosing navigation entry is shown again after it was hidden: back to
 * a retained screen, or its enclosing screen shown again. Not on the first show (load that with
 * `LaunchedEffect`), so the two together are "load, and refresh on return". A run still going when
 * the entry is shown again is cancelled and started again; hiding the entry does not cancel it, so
 * a refresh that finishes after the user left still updates the (hidden) screen.
 *
 * A screen that is not retained is composed again on return, so its `LaunchedEffect` load runs
 * again instead and this does not run: the two cover both cases without the screen knowing which
 * one it is in. A refresh here recomposes the screen on the back path, which retention otherwise
 * keeps free of guest work, so keep it to what changed (a "since" query, a cache check).
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun LaunchedOnReturn(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
  val shows = LocalKeliverNavEntryShows.current
  LaunchedEffect(shows, *keys) {
    // A return is a show after the first, and after this effect started.
    val start = shows.value
    snapshotFlow { shows.value }
      .filter { it >= 2 && it > start }
      .collectLatest { coroutineScope { block() } }
  }
}

/** Counts [lifecycle]'s shows, for [LaunchedOnceShown] and [LaunchedOnReturn]. */
@OptIn(ExperimentalKeliverNavRetentionApi::class)
@Composable
internal fun CountShows(lifecycle: EntryLifecycle) {
  LaunchedEffect(lifecycle) {
    snapshotFlow { lifecycle.state.value }.collect {
      if (it == KeliverNavEntryLifecycle.Visible) lifecycle.shows.intValue++
    }
  }
}

/**
 * The retained stack without a controller, for an app that keeps its own navigation state: [keys]
 * bottom-to-top (unique, which is checked; the last one is shown), each composed by [content]. With
 * [retention] null or of depth 0 only the top is composed, as a plain conditional would.
 * See [KeliverNavRetention] for what retention costs and does not change.
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun <K : Any> KeliverRetainedStack(
  keys: List<K>,
  retention: KeliverNavRetention?,
  content: @Composable (K) -> Unit,
) {
  KeliverRetainedStack(keys, retention, onHostBack = null, content)
}

/**
 * [KeliverRetainedStack] that lets a host go back on its own when [retention]'s frame is a
 * [KeliverNavHostFrame]: on a system back press the host shows the entry below the top in the frame
 * of the press, and then [onHostBack] is called, in the guest, with that entry's key; it must make
 * that key the top (drop the keys above it), as the app's own back would. The host keeps showing the
 * entry until the guest has handled the press, so nothing jumps. A `BackHandler` enabled in the top
 * entry takes the press instead, as without host back. With [onHostBack] null, or a frame that is
 * not a [KeliverNavHostFrame], every back goes through the guest.
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun <K : Any> KeliverRetainedStack(
  keys: List<K>,
  retention: KeliverNavRetention?,
  onHostBack: ((K) -> Unit)?,
  content: @Composable (K) -> Unit,
) {
  require(keys.isNotEmpty()) { "keys must not be empty" }
  // Two entries with one key would share a key() group by position and could swap their state.
  require(keys.size == 1 || keys.toSet().size == keys.size) { "keys must be unique: $keys" }
  val ids = remember { KeyIds() }
  SideEffect { ids.retain(keys) }
  RetainedEntries(keys, { it }, retention, content, { ids.idOf(it) }, onHostBack)
}

/**
 * Composes the top of [items] and, with [retention], up to its depth below it, each in a group keyed
 * by [keyOf] so an entry's composition survives entries added above it and removed from below it.
 * On a [KeliverNavHostFrame], [idOf] names an entry to the host, [onHostBack] makes an entry the
 * top after the host went back to it, and [liveItems] (if given) reads the stack as it is now.
 *
 * NF3: [prepared], when not in [items], is composed too, hidden (an entry about to be pushed, composed
 * while the finger is down); when it is pushed its composition is the one shown. [framed] uses the
 * frame even at depth 0, so a press does not change the top's place in the composition.
 */
@OptIn(ExperimentalKeliverNavRetentionApi::class, ExperimentalKeliverBackApi::class)
@Composable
internal fun <T : Any> RetainedEntries(
  items: List<T>,
  keyOf: (T) -> Any,
  retention: KeliverNavRetention?,
  content: @Composable (T) -> Unit,
  idOf: (T) -> String = { keyOf(it).toString() },
  onHostBack: ((T) -> Unit)? = null,
  liveItems: (() -> List<T>)? = null,
  retainable: (T) -> Boolean = { true },
  prepared: T? = null,
  framed: Boolean = false,
) {
  val depth = retention?.depth ?: 0
  if (retention == null || (depth == 0 && !framed)) {
    val top = items.last()
    key(keyOf(top)) {
      content(top)
    }
    return
  }
  // Memory pressure (trimKeliverNavRetention): this composition keeps only the top.
  val trims = retentionTrims.intValue
  val lastTrims = remember { IntBox(trims) }
  val trimmed = trims != lastTrims.value
  // Only entries composed in the last composition are kept: an entry that left the window (evicted
  // by depth, opted out, trimmed) is not composed again, hidden, when an entry above it is popped.
  // That would put its whole composition on the back path; it is composed when it becomes the top,
  // as without retention. Entries that opted out do not take a place in the depth.
  val composedKeys = remember { HashSet<Any>() }
  val top = items.last()
  val below = if (trimmed) {
    emptyList()
  } else {
    items.subList(0, items.lastIndex)
      .filter { keyOf(it) in composedKeys && retainable(it) }
      .takeLast(depth)
  }
  val window = below + top
  SideEffect {
    lastTrims.value = trims
    composedKeys.clear()
    window.mapTo(composedKeys, keyOf)
  }
  val ahead = prepared?.takeIf { p -> keyOf(p).let { k -> items.none { keyOf(it) == k } } }
  val frame = retention.frame
  if (frame is KeliverNavHostFrame) {
    HostRetainedEntries(items, window, keyOf, idOf, frame, onHostBack, content, liveItems, ahead)
    return
  }
  // An entry is shown only while the entry enclosing this stack (if any) is shown too.
  val parentLifecycle = LocalKeliverNavEntryLifecycle.current
  val parentBackHandlers = LocalBackHandlersEnabled.current
  // The prepared entry goes last: the plain frame hides by [visible], and when it is pushed it stays
  // where it is.
  val entries = if (ahead != null) window + ahead else window
  retention.frame.Stack {
    for ((index, item) in entries.withIndex()) {
      key(keyOf(item)) {
        val visible = index == window.lastIndex
        val lifecycle = remember(parentLifecycle, parentBackHandlers) {
          EntryLifecycle(visible, parentLifecycle, parentBackHandlers)
        }
        SideEffect { lifecycle.update(visible) }
        CountShows(lifecycle)
        retention.frame.Entry(visible) {
          CompositionLocalProvider(
            LocalKeliverNavEntryLifecycle provides lifecycle.state,
            LocalKeliverNavEntryShows provides lifecycle.shows,
            // A hidden entry's BackHandlers stay registered (it is not disposed) but must not take
            // back presses meant for the entry on top.
            LocalBackHandlersEnabled provides lifecycle.backHandlersEnabled,
          ) {
            EntryContent(item, content)
          }
        }
      }
    }
  }
}

internal class IntBox(var value: Int)

/** A skippable boundary: showing or hiding an entry re-runs the frame, never the entry's content. */
@Composable
internal fun <T> EntryContent(item: T, content: @Composable (T) -> Unit) {
  content(item)
}

@OptIn(ExperimentalKeliverNavRetentionApi::class)
internal class EntryLifecycle(
  visible: Boolean,
  parentLifecycle: State<KeliverNavEntryLifecycle>,
  parentBackHandlers: State<Boolean>,
) {
  /** The top of this stack; not enough on its own when this stack is inside a hidden entry. */
  private val topOfStack: MutableState<Boolean> = mutableStateOf(visible)

  /** Visible: the top of this stack, inside an enclosing entry (or none) that is visible. */
  val state: State<KeliverNavEntryLifecycle> = derivedStateOf {
    if (topOfStack.value && parentLifecycle.value == KeliverNavEntryLifecycle.Visible) {
      KeliverNavEntryLifecycle.Visible
    } else {
      KeliverNavEntryLifecycle.Hidden
    }
  }

  /** Visible, inside an enclosing entry (or none) whose handlers are enabled. */
  val backHandlersEnabled: State<Boolean> = derivedStateOf {
    state.value == KeliverNavEntryLifecycle.Visible && parentBackHandlers.value
  }

  /** How many times [state] has become visible (counted by [CountShows]). */
  val shows: MutableIntState = mutableIntStateOf(0)

  fun update(visible: Boolean) {
    if (topOfStack.value != visible) topOfStack.value = visible
  }
}
