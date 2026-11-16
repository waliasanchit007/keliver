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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest

/**
 * How the entries of a retained back stack are laid out, shown and hidden. Supplied by code that
 * knows the app's schema (keliver-nav does not), because hiding is done with the schema's own
 * widgets and modifiers: no protocol change, so a bundle that retains needs nothing from a host
 * that its schema does not already need (D2).
 *
 * A frame must keep a hidden entry's widgets in the tree (so its native state, such as a list's
 * scroll position, survives) while the host neither draws it nor delivers input to it.
 */
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
 */
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
public enum class KeliverNavEntryLifecycle {
  /** The top of the stack (and any content outside a retained stack). */
  Visible,

  /** Retained below the top: composed, not shown. It should not start work (requests, polling). */
  Hidden,
}

private val AlwaysVisible: State<KeliverNavEntryLifecycle> = mutableStateOf(KeliverNavEntryLifecycle.Visible)

internal val LocalKeliverNavEntryLifecycle = staticCompositionLocalOf { AlwaysVisible }

/**
 * The enclosing navigation entry's lifecycle, as a [State]. Reading `.value` in a composable
 * recomposes it when the entry is shown or hidden; reading it in an effect (for example through
 * `snapshotFlow`) does not, which keeps going back free of guest composition.
 * Outside a retained stack it is always [KeliverNavEntryLifecycle.Visible].
 */
@Composable
public fun keliverNavEntryLifecycle(): State<KeliverNavEntryLifecycle> = LocalKeliverNavEntryLifecycle.current

/**
 * Like `LaunchedEffect`, but [block] runs only while the enclosing navigation entry is visible: it
 * is cancelled when the entry is hidden (another screen opened over it) and started again when the
 * entry is shown. Use it for work a hidden screen must not do: requests, polling, timers.
 * Showing and hiding an entry does not recompose the caller.
 */
@Composable
public fun LaunchedWhileVisible(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
  val lifecycle = LocalKeliverNavEntryLifecycle.current
  LaunchedEffect(lifecycle, *keys) {
    snapshotFlow { lifecycle.value }.collectLatest {
      if (it == KeliverNavEntryLifecycle.Visible) coroutineScope { block() }
    }
  }
}

/**
 * The retained stack without a controller, for an app that keeps its own navigation state: [keys]
 * bottom-to-top (unique; the last one is shown), each composed by [content]. With [retention] null
 * or of depth 0 only the top is composed, as a plain conditional would.
 */
@Composable
public fun <K : Any> KeliverRetainedStack(
  keys: List<K>,
  retention: KeliverNavRetention?,
  content: @Composable (K) -> Unit,
) {
  require(keys.isNotEmpty()) { "keys must not be empty" }
  RetainedEntries(keys, { it }, retention, content)
}

/**
 * Composes the top of [items] and, with [retention], up to its depth below it, each in a group keyed
 * by [keyOf] so an entry's composition survives entries added above it and removed from below it.
 */
@Composable
internal fun <T : Any> RetainedEntries(
  items: List<T>,
  keyOf: (T) -> Any,
  retention: KeliverNavRetention?,
  content: @Composable (T) -> Unit,
) {
  val depth = retention?.depth ?: 0
  if (retention == null || depth == 0) {
    val top = items.last()
    key(keyOf(top)) {
      content(top)
    }
    return
  }
  // Only entries composed in the last composition are kept: an entry that left the window (evicted
  // by depth) is not composed again, hidden, when an entry above it is popped. That would put its
  // whole composition on the back path; it is composed when it becomes the top, as without retention.
  val composedKeys = remember { HashSet<Any>() }
  val candidates = items.takeLast(depth + 1)
  val window = candidates.filterIndexed { i, it -> i == candidates.lastIndex || keyOf(it) in composedKeys }
  SideEffect {
    composedKeys.clear()
    window.mapTo(composedKeys, keyOf)
  }
  retention.frame.Stack {
    for ((index, item) in window.withIndex()) {
      key(keyOf(item)) {
        val visible = index == window.lastIndex
        val lifecycle = remember { EntryLifecycle(visible) }
        SideEffect { lifecycle.update(visible) }
        retention.frame.Entry(visible) {
          CompositionLocalProvider(LocalKeliverNavEntryLifecycle provides lifecycle.state) {
            EntryContent(item, content)
          }
        }
      }
    }
  }
}

/** A skippable boundary: showing or hiding an entry re-runs the frame, never the entry's content. */
@Composable
private fun <T> EntryContent(item: T, content: @Composable (T) -> Unit) {
  content(item)
}

private class EntryLifecycle(visible: Boolean) {
  val state: MutableState<KeliverNavEntryLifecycle> = mutableStateOf(of(visible))

  fun update(visible: Boolean) {
    val value = of(visible)
    if (state.value != value) state.value = value
  }

  private fun of(visible: Boolean) =
    if (visible) KeliverNavEntryLifecycle.Visible else KeliverNavEntryLifecycle.Hidden
}
