/*
 * Copyright (C) 2023 Square, Inc.
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
package dev.keliver.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher

public val LocalOnBackPressedDispatcher: ProvidableCompositionLocal<OnBackPressedDispatcher> =
  staticCompositionLocalOf {
    throw AssertionError("OnBackPressedDispatcher was not provided!")
  }

public val OnBackPressedDispatcher.Companion.current: OnBackPressedDispatcher
  @Composable
  @ReadOnlyComposable
  get() = LocalOnBackPressedDispatcher.current

private val BackHandlersAlwaysEnabled: State<Boolean> = mutableStateOf(true)

/**
 * Whether the [BackHandler]s in this part of the composition may handle back presses. A
 * [BackHandler] is enabled only while both its own `enabled` and this are `true`.
 *
 * Navigation that keeps screens composed while they are not shown (keliver-nav's retained back
 * stack) provides `false` for a hidden screen, so its handlers do not take back presses meant for
 * the screen on top. A change of the value does not recompose the [BackHandler]s that read it; it
 * reaches their callbacks one dispatch after the snapshot that changed it is applied, so a handler
 * stays enabled briefly after its screen is hidden. Defaults to always `true`.
 */
@ExperimentalKeliverBackApi
public val LocalBackHandlersEnabled: ProvidableCompositionLocal<State<Boolean>> =
  staticCompositionLocalOf { BackHandlersAlwaysEnabled }

/**
 * An effect for handling presses of the system back button.
 *
 * Calling this in your composable adds the given lambda to the [OnBackPressedDispatcher] of the
 * [LocalOnBackPressedDispatcher].
 *
 * If this is called by nested composables, if enabled, the inner most composable will consume
 * the call to system back and invoke its lambda. The call will continue to propagate up until it
 * finds an enabled BackHandler.
 *
 * The [onBack] lambda is never invoked on platforms that don't have a system back button, nor
 * while [LocalBackHandlersEnabled] is `false`.
 *
 * @param enabled if this BackHandler should be enabled
 * @param onBack the action invoked by pressing the system back
 */
// Multiplatform variant of
// https://github.com/androidx/androidx/blob/94ae1a9fb3ce778295e8cc724ae29f1231436bcb/activity/activity-compose/src/main/java/androidx/activity/compose/BackHandler.kt#L82
@OptIn(ExperimentalKeliverBackApi::class)
@Composable
public fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
  // Safely update the current `onBack` lambda when a new one is provided.
  val currentOnBack by rememberUpdatedState(onBack)
  // Remember in Composition a back callback that calls the `onBack` lambda.
  // Explicit return type necessary per https://youtrack.jetbrains.com/issue/KT-42073
  val backCallback: OnBackPressedCallback = remember {
    object : OnBackPressedCallback(enabled) {
      override fun handleOnBackPressed() {
        currentOnBack()
      }
    }
  }
  val handlersEnabled = LocalBackHandlersEnabled.current
  // On every successful composition, update the callback with the `enabled` value.
  SideEffect {
    backCallback.isEnabled = enabled && handlersEnabled.value
  }
  if (handlersEnabled !== BackHandlersAlwaysEnabled) {
    // Follow the enclosing gate without recomposing: showing or hiding a retained screen must not
    // compose anything in it.
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(backCallback, handlersEnabled) {
      snapshotFlow { handlersEnabled.value }.collect {
        backCallback.isEnabled = currentEnabled && it
      }
    }
  }
  val backDispatcher = OnBackPressedDispatcher.current
  DisposableEffect(backDispatcher) {
    // Add callback to the backDispatcher.
    val cancellable = backDispatcher.addCallback(backCallback)
    // When the effect leaves the Composition, remove the callback.
    onDispose {
      cancellable.cancel()
    }
  }
}
