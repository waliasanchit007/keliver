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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull

/**
 * Names a result a screen hands back to the screen below it, with its type:
 *
 * ```
 * val PickedColor = KeliverNavResultKey<String>("pickedColor")
 *
 * // In the picker:
 * nav.popWithResult(PickedColor, "teal")
 *
 * // In the screen that opened it:
 * LaunchedOnResult(PickedColor) { color -> viewModel.setColor(color) }
 * ```
 *
 * Keys are compared by identity, not by [name] (which is for logs): declare each key once, as a
 * top-level `val`, and use that one value on both sides. Two keys with one name and different types
 * are then different keys, so a result never reaches a listener that expects another type.
 */
@ExperimentalKeliverNavRetentionApi
public class KeliverNavResultKey<T>(public val name: String) {
  override fun toString(): String = "KeliverNavResultKey($name)"
}

/** The [KeliverNavHost] entry being composed, or null outside one. */
internal val LocalKeliverNavEntry = staticCompositionLocalOf<NavEntry<*>?> { null }

/**
 * Calls [onResult] with each result handed to this screen for [key] by
 * [KeliverNavController.popWithResult], once per result, and removes it.
 *
 * A retained screen below the top receives the result as the pop shows it, without being composed
 * again. A screen that was disposed (not retained) receives it when it is composed again on the way
 * back. A result is for the visit it was passed to: one nobody has received by the time the screen
 * is covered again (no [LaunchedOnResult] for its key was composed) is dropped, never delivered late.
 * Results are not saved: one not yet received when the guest is recreated is lost.
 * Outside a [KeliverNavHost] (a preview, a test of the screen alone) nothing is delivered.
 */
@ExperimentalKeliverNavRetentionApi
@Composable
public fun <T> LaunchedOnResult(key: KeliverNavResultKey<T>, onResult: suspend CoroutineScope.(T) -> Unit) {
  val entry = LocalKeliverNavEntry.current ?: return
  val currentOnResult by rememberUpdatedState(onResult)
  LaunchedEffect(entry, key) {
    snapshotFlow { entry.results[key] }.filterNotNull().collect { result ->
      // Removed before delivery, so a result is received once even if onResult suspends or fails.
      if (entry.results[key] === result) entry.results.remove(key)
      @Suppress("UNCHECKED_CAST")
      currentOnResult(result.value as T)
    }
  }
}
