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
package dev.keliver.composeui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.keliver.compose.RedwoodComposition
import dev.keliver.ui.Density
import dev.keliver.ui.LayoutDirection as RedwoodLayoutDirection
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.Size
import dev.keliver.ui.UiConfiguration
import dev.keliver.ui.dp as redwoodDp
import dev.keliver.widget.WidgetSystem
import dev.keliver.widget.compose.ComposeWidgetChildren
import kotlinx.coroutines.flow.MutableStateFlow

/** Render a Redwood composition inside of Compose UI. */
@Composable
public fun RedwoodContent(
  widgetSystem: WidgetSystem<@Composable (Modifier) -> Unit>,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  // If the provider or content change, reset any assumption about the rendered size.
  var viewportSize: Size? by remember(widgetSystem, content) { mutableStateOf(null) }

  val density = LocalDensity.current
  val uiConfiguration = UiConfiguration(
    darkMode = isSystemInDarkTheme(),
    safeAreaInsets = safeAreaInsets(),
    viewportSize = viewportSize,
    density = density.density.toDouble(),
    layoutDirection = when (LocalLayoutDirection.current) {
      LayoutDirection.Ltr -> RedwoodLayoutDirection.Ltr
      LayoutDirection.Rtl -> RedwoodLayoutDirection.Rtl
    },
  )
  val uiConfigurations = remember { MutableStateFlow(uiConfiguration) }
  LaunchedEffect(uiConfiguration) {
    uiConfigurations.value = uiConfiguration
  }

  val scope = rememberCoroutineScope()
  val onBackPressedDispatcher = platformOnBackPressedDispatcher()
  val saveableStateRegistry = LocalSaveableStateRegistry.current

  // For simplicity, a new provider or content lambda gets an entirely new composition and children.
  val children = remember(widgetSystem, content) { ComposeWidgetChildren() }
  DisposableEffect(widgetSystem, content) {
    val composition = RedwoodComposition(
      scope = scope,
      widgetSystem = widgetSystem,
      container = children,
      onBackPressedDispatcher = onBackPressedDispatcher,
      saveableStateRegistry = saveableStateRegistry,
      uiConfigurations = uiConfigurations,
    )
    composition.setContent(content)

    onDispose {
      composition.cancel()
    }
  }

  Box(
    modifier = modifier.onSizeChanged { size ->
      viewportSize = with(Density(density.density.toDouble())) {
        Size(size.width.toDp().value.redwoodDp, size.height.toDp().value.redwoodDp)
      }
    },
  ) {
    children.Render()
  }
}

@Composable
internal expect fun platformOnBackPressedDispatcher(): OnBackPressedDispatcher
