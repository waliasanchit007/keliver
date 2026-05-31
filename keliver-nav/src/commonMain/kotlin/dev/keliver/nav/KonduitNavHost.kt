/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
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
 * screens can read the controller via [currentKonduitNavController].
 *
 * ```
 * KonduitNavHost(nav) { route ->
 *     when (route) {
 *         is Route.Home        -> HomeScreen()
 *         is Route.QuoteDetail -> QuoteDetailScreen(route.id)
 *     }
 * }
 * ```
 */
@Composable
public fun <R : Any> KonduitNavHost(
  controller: KonduitNavController<R>,
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

  val top = controller.entries.last()
  CompositionLocalProvider(LocalKonduitNavController provides controller) {
    stateHolder.SaveableStateProvider(top.id) {
      content(top.route)
    }
  }
}

/**
 * `CompositionLocal` carrying the current [KonduitNavController]. Type
 * is erased to `KonduitNavController<*>?` because Compose
 * `CompositionLocal`s don't carry a generic parameter; adopters should
 * use the typed [currentKonduitNavController] accessor instead of
 * reading `LocalKonduitNavController.current` directly.
 *
 * `@PublishedApi internal` because the typed accessor is inline +
 * reified — adopters never reference this directly.
 */
@PublishedApi
internal val LocalKonduitNavController: ProvidableCompositionLocal<KonduitNavController<*>?> =
  staticCompositionLocalOf { null }

/**
 * Read the current [KonduitNavController] from the nearest enclosing
 * [KonduitNavHost]. Type parameter [R] is the adopter's route type
 * (typically a sealed interface).
 *
 * ```
 * @Composable
 * fun HomeScreen() {
 *     val nav = currentKonduitNavController<Route>()
 *     Button(onClick = { nav.navigate(Route.QuoteDetail("42")) }) {
 *         Text("Open 42")
 *     }
 * }
 * ```
 *
 * Throws an [IllegalStateException] if called outside a
 * [KonduitNavHost]'s composition.
 */
@Composable
@Suppress("UNCHECKED_CAST")
public inline fun <reified R : Any> currentKonduitNavController(): KonduitNavController<R> {
  val controller = LocalKonduitNavController.current
    ?: error(
      "No KonduitNavController in this composition. Wrap your screen " +
        "tree in KonduitNavHost { … } at the top of the @Composable that " +
        "owns navigation.",
    )
  return controller as KonduitNavController<R>
}
