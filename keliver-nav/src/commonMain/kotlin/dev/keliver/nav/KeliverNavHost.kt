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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Renders the top route of [controller]'s back stack. Each stack entry
 * is wrapped in its own `SaveableStateHolder` slot, so a route's
 * `rememberSaveable` state survives a navigate-away-and-back round-trip.
 * State for popped or replaced entries is cleared on the next
 * composition.
 *
 * Wraps the rendered route in a `CompositionLocalProvider` so nested
 * screens can read the controller via [currentKeliverNavController].
 *
 * ```
 * KeliverNavHost(nav) { route ->
 *     when (route) {
 *         is Route.Home        -> HomeScreen()
 *         is Route.QuoteDetail -> QuoteDetailScreen(route.id)
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalKeliverNavRetentionApi::class)
@Composable
public fun <R : Any> KeliverNavHost(
  controller: KeliverNavController<R>,
  content: @Composable (R) -> Unit,
) {
  KeliverNavHost(controller, retention = null, content)
}

/**
 * [KeliverNavHost] with a retained back stack: with [retention], up to its depth of entries below
 * the top stay composed and hidden by its [KeliverNavFrame], so [KeliverNavController.pop] to one of
 * them composes nothing in the guest and creates nothing on the host: the frame shows the native
 * tree the host already has. Hidden entries see [KeliverNavEntryLifecycle.Hidden] through
 * [keliverNavEntryLifecycle] and [LaunchedWhileVisible], and their `BackHandler`s are disabled.
 * With [retention] null (or depth 0) this is the plain [KeliverNavHost]: only the top entry is
 * composed. See [KeliverNavRetention] for what retention costs and does not change.
 *
 * With a [KeliverNavHostFrame], a system back press over an entry with no enabled `BackHandler` is
 * shown by the host in the frame of the press, and the controller is then popped to the entry the
 * host shows (see [KeliverNavHostFrame] for the protocol and its conflict rules). The app's own
 * `BackHandler(enabled = nav.canPop) { nav.pop() }` stays as it is: it takes the presses the host
 * does not (a host without a back dispatcher for the container, the bottom of the retained window).
 *
 * Retention does not add saving: a controller from [rememberKeliverNavController] is remembered,
 * not saved, so a recreated guest composition starts again from its start route, with or without
 * retention.
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun <R : Any> KeliverNavHost(
  controller: KeliverNavController<R>,
  retention: KeliverNavRetention?,
  content: @Composable (R) -> Unit,
) {
  val stateHolder = rememberSaveableStateHolder()

  // Track which entry ids the host has provided state slots for; when
  // an id leaves the stack (popped / replaced), reclaim its slot.
  val seenIds = remember { mutableSetOf<Long>() }
  val currentIds = controller.entries.map { it.id }.toSet()
  DisposableEffect(currentIds) {
    (seenIds - currentIds).forEach { stateHolder.removeState(it) }
    seenIds.clear()
    seenIds.addAll(currentIds)
    onDispose { /* SaveableStateHolder cleans itself up when the host leaves the tree. */ }
  }

  CompositionLocalProvider(LocalKeliverNavController provides controller) {
    if (retention == null || retention.depth == 0) {
      val top = controller.entries.last()
      stateHolder.SaveableStateProvider(top.id) {
        content(top.route)
      }
    } else {
      RetainedEntries(
        items = controller.entries.toList(),
        keyOf = { it.id },
        retention = retention,
        content = { entry ->
          stateHolder.SaveableStateProvider(entry.id) {
            content(entry.route)
          }
        },
        idOf = { it.id.toString() },
        onHostBack = { controller.popToEntry(it.id) },
      )
    }
  }
}

/**
 * `CompositionLocal` carrying the current [KeliverNavController]. Type
 * is erased to `KeliverNavController<*>?` because Compose
 * `CompositionLocal`s don't carry a generic parameter; adopters should
 * use the typed [currentKeliverNavController] accessor instead of
 * reading `LocalKeliverNavController.current` directly.
 *
 * `@PublishedApi internal` because the typed accessor is inline +
 * reified — adopters never reference this directly.
 */
@PublishedApi
internal val LocalKeliverNavController: ProvidableCompositionLocal<KeliverNavController<*>?> =
  staticCompositionLocalOf { null }

/**
 * Read the current [KeliverNavController] from the nearest enclosing
 * [KeliverNavHost]. Type parameter [R] is the adopter's route type
 * (typically a sealed interface).
 *
 * ```
 * @Composable
 * fun HomeScreen() {
 *     val nav = currentKeliverNavController<Route>()
 *     Button(onClick = { nav.navigate(Route.QuoteDetail("42")) }) {
 *         Text("Open 42")
 *     }
 * }
 * ```
 *
 * Throws an [IllegalStateException] if called outside a
 * [KeliverNavHost]'s composition.
 */
@Composable
@Suppress("UNCHECKED_CAST")
public inline fun <reified R : Any> currentKeliverNavController(): KeliverNavController<R> {
  val controller = LocalKeliverNavController.current
    ?: error(
      "No KeliverNavController in this composition. Wrap your screen " +
        "tree in KeliverNavHost { … } at the top of the @Composable that " +
        "owns navigation.",
    )
  return controller as KeliverNavController<R>
}
