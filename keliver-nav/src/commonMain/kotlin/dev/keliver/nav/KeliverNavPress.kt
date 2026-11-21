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
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.keliver.Modifier
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.LocalHostFeatures

/**
 * The host's press signal (NF3), from code that knows the app's schema (keliver-nav knows no
 * modifiers): a modifier the host answers with an event when a finger goes down on the element and
 * another when that press ends without a click, both before the click. Gate A's is
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
   * [modifier] followed by the schema's press modifier: [onPress] on touch-down, [onCancel] when
   * that press ends without a click.
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
 * Adds nothing without a [LocalKeliverNavPress] or on a host that does not declare its feature; the
 * click then composes the screen, as before. The prepared screen is composed only by a
 * [KeliverNavHost] with a retention (it needs the frame to hide it), and its effects run on
 * touch-down: see [KeliverPreparedStack] for what that means for a screen's effects
 * ([LaunchedOnceShown] waits for the tap).
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun <R : Any> Modifier.prepareOnPress(controller: KeliverNavController<R>, route: R): Modifier =
  withPressSignal(onPress = { controller.prepare(route) }, onCancel = { controller.cancelPreparation(route) })

/**
 * [prepareOnPress] for an app that keeps its own stack: [key] is prepared in [preparation] on
 * touch-down (composed by a [KeliverPreparedStack]) and cancelled when the press ends without a
 * click.
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun <K : Any> Modifier.prepareOnPress(preparation: KeliverNavPreparation<K>, key: K): Modifier =
  withPressSignal(onPress = { preparation.prepare(key) }, onCancel = { preparation.cancel(key) })

@OptIn(ExperimentalKeliverHostFeaturesApi::class, ExperimentalKeliverNavRetentionApi::class)
@Composable
private fun Modifier.withPressSignal(onPress: () -> Unit, onCancel: () -> Unit): Modifier {
  val press = LocalKeliverNavPress.current ?: return this
  if (press.hostFeature !in LocalHostFeatures.current) return this
  return press.pressSignal(this, onPress, onCancel)
}
