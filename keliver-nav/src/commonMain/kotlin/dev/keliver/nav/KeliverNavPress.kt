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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.keliver.Modifier
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.LocalHostFeatures

/**
 * The host's press signal (NF3), from code that knows the app's schema (keliver-nav knows no
 * modifiers): a modifier the host answers with an event when a finger goes down on the element,
 * before any click, and another when that press ends (after the click, if there was one; a cancel
 * after a click that navigated finds nothing left to drop). Gate A's is
 * `dev.keliver.compose.extras.pressSignal` with `PRESS_HOST_FEATURE`.
 *
 * Provide it once at the root of an app with [LocalKeliverNavPress]; [prepareOnPress] then composes
 * the screen a tap will open while the finger is down, on a host that declares [hostFeature]. On any
 * other host (one older than the signal, D2) it sends nothing and the screen is composed at the
 * click, as before.
 */
@ExperimentalKeliverNavRetentionApi
@Stable
public interface KeliverNavPress {
  /** The host feature that says a host renders [pressSignal]'s modifier and sends its events. */
  public val hostFeature: String

  /**
   * [modifier] followed by the schema's press modifier: [onPress] on touch-down (or, inside a
   * scroll container, once the host is sure it is not a scroll), [onCancel] when that press ends,
   * whatever ended it, after the click if there was one.
   */
  public fun pressSignal(modifier: Modifier, onPress: () -> Unit, onCancel: () -> Unit): Modifier
}

/** The app's [KeliverNavPress]; null (the default) means [prepareOnPress] adds nothing. */
@ExperimentalKeliverNavRetentionApi
public val LocalKeliverNavPress: ProvidableCompositionLocal<KeliverNavPress?> =
  staticCompositionLocalOf { null }

/**
 * Composes [route] while the finger is down on this element, hidden, so the click that opens it
 * (`controller.navigate(route)` with an equal route) shows a screen that is already composed. Put
 * it on the element whose click navigates, next to its `clickable`:
 *
 * ```
 * Row(Modifier.clickable { nav.navigate(Route.Detail(id)) }.prepareOnPress(nav, Route.Detail(id))) { … }
 * ```
 *
 * Use it only next to that clickable, on the same element: the host's press signal makes the
 * element a touch target, so on an element without a clickable of its own that overlaps a clickable
 * sibling it would take that sibling's touches.
 *
 * Adds nothing without a [LocalKeliverNavPress] or on a host that does not declare its feature; the
 * click then composes the screen, as before. The prepared screen is composed only by a
 * [KeliverNavHost] with a retention (it needs the frame to hide it).
 *
 * The prepared screen's effects run on press, not at the tap: every `LaunchedEffect` and
 * `DisposableEffect` of the screen runs on touch-down, and is cancelled again when the press ends
 * without opening it. A host waits inside a scroll container until the finger has stayed still for a
 * moment, so a scroll that starts on a row does not prepare anything, but a held press that then
 * moves away, a long press, or a click that does not navigate does: a screen that loads with
 * `LaunchedEffect` sends its HTTP request on touch-down and then cancels it. Put what must happen
 * only for a screen the user opened ("screen viewed" analytics, marking something read, creating an
 * order) in [LaunchedOnceShown], which waits for the tap. See [KeliverPreparedStack].
 *
 * The press ends when the host says so (after the click, if any) or when this element leaves the
 * composition while the finger is down (a list refreshed, the row was replaced): either cancels the
 * route that was pressed, even if [route] has changed since.
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun <R : Any> Modifier.prepareOnPress(controller: KeliverNavController<R>, route: R): Modifier =
  withPressSignal(route, prepare = controller::prepare, cancel = controller::cancelPreparation)

/**
 * [prepareOnPress] for an app that keeps its own stack: [key] is prepared in [preparation] on
 * touch-down (composed by a [KeliverPreparedStack]) and cancelled when the press ends without
 * opening it. The same rules apply: next to the clickable only, and the screen's effects run on
 * press.
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun <K : Any> Modifier.prepareOnPress(preparation: KeliverNavPreparation<K>, key: K): Modifier =
  withPressSignal(key, prepare = preparation::prepare, cancel = preparation::cancel)

@OptIn(ExperimentalKeliverHostFeaturesApi::class, ExperimentalKeliverNavRetentionApi::class)
@Composable
private fun <K : Any> Modifier.withPressSignal(key: K, prepare: (K) -> Unit, cancel: (K) -> Unit): Modifier {
  val press = LocalKeliverNavPress.current ?: return this
  if (press.hostFeature !in LocalHostFeatures.current) return this
  val state = remember { PressState(key, prepare, cancel) }
  SideEffect { state.update(key, prepare, cancel) }
  // The element left the composition mid-press: the host's cancel would go to a node the guest no
  // longer has (and be dropped), so cancel here.
  DisposableEffect(state) { onDispose { state.end() } }
  return press.pressSignal(this, state.onPress, state.onCancel)
}

/**
 * One element's press: remembers the key it prepared, so the cancel drops that one even if the
 * element's key changed during the press.
 */
private class PressState<K : Any>(
  private var key: K,
  private var prepare: (K) -> Unit,
  private var cancel: (K) -> Unit,
) {
  private var pressed: K? = null

  fun update(key: K, prepare: (K) -> Unit, cancel: (K) -> Unit) {
    this.key = key
    this.prepare = prepare
    this.cancel = cancel
  }

  val onPress: () -> Unit = {
    val k = key
    pressed?.let { if (it != k) cancel(it) }
    pressed = k
    prepare(k)
  }

  val onCancel: () -> Unit = { end() }

  fun end() {
    val k = pressed ?: return
    pressed = null
    cancel(k)
  }
}
