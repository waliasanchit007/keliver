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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * Guest-side typed navigation controller. Owns a back stack of routes
 * of type [R] (typically a sealed interface with `@Serializable`
 * subclasses); exposes navigate / pop / popUntil / replaceAll. The
 * stack lives entirely in the guest — the host learns about route
 * changes only via integrations adopters opt into (deferred to a
 * future release).
 *
 * Each navigate call assigns the new entry a monotonically increasing
 * id; [KeliverNavHost] uses these ids as `SaveableStateHolder` keys so
 * each stack entry's `rememberSaveable` state survives a navigate-away-
 * and-back round-trip. When an entry is popped or replaced its state
 * slot is cleared.
 *
 * Construct via [rememberKeliverNavController] in a `@Composable`:
 *
 * ```
 * @Serializable sealed interface Route {
 *     @Serializable data object Home : Route
 *     @Serializable data class QuoteDetail(val id: String) : Route
 * }
 *
 * @Composable
 * fun App() {
 *     val nav = rememberKeliverNavController<Route>(start = Route.Home)
 *     KeliverNavHost(nav) { route ->
 *         when (route) {
 *             is Route.Home        -> HomeScreen()
 *             is Route.QuoteDetail -> QuoteDetailScreen(route.id)
 *         }
 *     }
 * }
 * ```
 */
@Stable
public class KeliverNavController<R : Any> internal constructor(initialRoute: R) {
  private var nextId: Long = 0L

  // Backing list — Compose-observable; reads recompose, writes trigger
  // recomposition of every reader of `current` / `backstack` / `canPop`.
  internal val entries = mutableStateListOf(NavEntry(nextId++, initialRoute))

  /** The route at the top of the stack — what [KeliverNavHost] is rendering. */
  public val current: R get() = entries.last().route

  /** Read-only view of the stack, bottom-to-top (root first, current last). */
  public val backstack: List<R> get() = entries.map { it.route }

  /**
   * `true` if there is more than one entry — i.e. a [pop] would change
   * which route is displayed. Useful for showing / hiding a back button.
   */
  public val canPop: Boolean get() = entries.size > 1

  /** Push [route] onto the back stack. */
  public fun navigate(route: R) {
    entries.add(NavEntry(nextId++, route))
  }

  /**
   * Pops the top entry. Returns `true` if a pop happened, `false` if
   * already at the root (no-op). System-back integration typically does:
   *
   * ```
   * BackHandler(enabled = nav.canPop) { nav.pop() }
   * ```
   */
  public fun pop(): Boolean {
    if (entries.size <= 1) return false
    entries.removeAt(entries.lastIndex)
    return true
  }

  /**
   * Pops entries until [predicate] returns `true` for the new top, or
   * the root is reached. Returns whether [predicate] matched the entry
   * we stopped at — `false` means we popped to root and the root didn't
   * match.
   *
   * Example: pop everything back to a named search route:
   *
   * ```
   * nav.popUntil { it is Route.Search }
   * ```
   */
  public fun popUntil(predicate: (R) -> Boolean): Boolean {
    while (entries.size > 1 && !predicate(entries.last().route)) {
      entries.removeAt(entries.lastIndex)
    }
    return predicate(entries.last().route)
  }

  /**
   * Replaces the entire stack with a single new root [route]. Every
   * previously-saved entry state slot is orphaned and cleaned up by
   * [KeliverNavHost]'s next composition.
   */
  public fun replaceAll(route: R) {
    entries.clear()
    entries.add(NavEntry(nextId++, route))
  }
}

/** Internal record paired with each route to key the SaveableStateHolder. */
internal data class NavEntry<R>(val id: Long, val route: R)

/**
 * Compose factory — remembers a [KeliverNavController] across
 * recompositions. The controller's identity stays stable for the
 * lifetime of the surrounding `@Composable`.
 */
@Composable
public fun <R : Any> rememberKeliverNavController(start: R): KeliverNavController<R> {
  return remember { KeliverNavController(start) }
}
