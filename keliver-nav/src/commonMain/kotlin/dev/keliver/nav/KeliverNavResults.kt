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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull

/**
 * Names a result a screen hands back to the screen below it, with its type:
 *
 * ```
 * val PickedColor = keliverNavResultKey<String>("pickedColor")
 *
 * // In the picker:
 * nav.popWithResult(PickedColor, "teal")
 *
 * // In the screen that opened it:
 * LaunchedOnResult(PickedColor) { color -> viewModel.setColor(color) }
 * ```
 *
 * A key made by [keliverNavResultKey] equals every other such key with the same [name] and the same
 * type, so a key written again where it is used (even one made anew on each recomposition) still
 * matches, and two keys with one name and different types never do: a result never reaches a
 * listener that expects another type.
 *
 * A key made with the constructor has no type to compare, so it is compared by identity: declare it
 * once, as a top-level `val`, and use that one value on both sides. It never equals a key made by
 * [keliverNavResultKey].
 */
@ExperimentalKeliverNavRetentionApi
public class KeliverNavResultKey<T> @PublishedApi internal constructor(
  public val name: String,
  private val type: KType?,
) {
  /** A key compared by identity. Prefer [keliverNavResultKey], which compares name and type. */
  public constructor(name: String) : this(name, null)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (type == null || other !is KeliverNavResultKey<*> || other.type == null) return false
    return name == other.name && type == other.type
  }

  override fun hashCode(): Int = if (type == null) super.hashCode() else 31 * name.hashCode() + type.hashCode()

  override fun toString(): String = if (type == null) "KeliverNavResultKey($name)" else "KeliverNavResultKey($name: $type)"
}

/**
 * A result key named [name] for results of type [T], equal to every other key made here with the
 * same name and type (see [KeliverNavResultKey]).
 */
@ExperimentalKeliverNavRetentionApi
public inline fun <reified T> keliverNavResultKey(name: String): KeliverNavResultKey<T> = KeliverNavResultKey(name, typeOf<T>())

/** The [KeliverNavHost] entry being composed, or null outside one. */
internal val LocalKeliverNavEntry = staticCompositionLocalOf<NavEntry<*>?> { null }

/**
 * Calls [onResult] with each result handed to this screen for [key] by
 * [KeliverNavController.popWithResult], once per result, and removes it.
 *
 * A retained screen below the top receives the result as the pop shows it, without being composed
 * again. A screen that was disposed (not retained) receives it when it is composed again on the way
 * back. A result is for the visit it was passed to: one nobody has received by the time the screen
 * is covered again (no [LaunchedOnResult] for its key was composed) is dropped, never delivered late;
 * so is one followed by a [KeliverNavController.navigate] in the same frame.
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
