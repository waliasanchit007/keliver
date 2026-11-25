/*
 * Copyright (C) 2022 Square, Inc.
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
package dev.keliver.treehouse.composeui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.keliver.compose.ExperimentalKeliverBackApi
import dev.keliver.composeui.safeAreaInsets
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.DynamicContentWidgetFactory
import dev.keliver.treehouse.StateSnapshot
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.TreehouseView
import dev.keliver.treehouse.TreehouseView.ReadyForContentChangeListener
import dev.keliver.treehouse.bindWhenReady
import dev.keliver.ui.Density
import dev.keliver.ui.LayoutDirection as RedwoodLayoutDirection
import dev.keliver.ui.Margin
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.Size
import dev.keliver.ui.UiConfiguration
import dev.keliver.ui.dp as redwoodDp
import dev.keliver.widget.SavedStateRegistry
import dev.keliver.widget.WidgetSystem
import dev.keliver.widget.compose.ComposeWidgetChildren
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
public fun <A : AppService> TreehouseContent(
  treehouseApp: TreehouseApp<A>,
  widgetSystem: WidgetSystem<@Composable (Modifier) -> Unit>,
  contentSource: TreehouseContentSource<A>,
  modifier: Modifier = Modifier,
  dynamicContentWidgetFactory: DynamicContentWidgetFactory<@Composable (Modifier) -> Unit> =
    EmptyDynamicContentWidgetFactory,
) {
  TreehouseContent(
    treehouseApp = treehouseApp,
    widgetSystem = widgetSystem,
    contentSource = contentSource,
    modifier = modifier,
    dynamicContentWidgetFactory = dynamicContentWidgetFactory,
    backEnabled = true,
    platformBackDispatcher = platformOnBackPressedDispatcher(),
  )
}

/**
 * [TreehouseContent], with its guest's back handlers behind a gate. While [backEnabled] is false
 * every back callback this content's guest registers (a `BackHandler`, keliver-nav's) is registered
 * with the platform DISABLED: a back press goes past this content, to the content shown on top of
 * it or to the activity, and reaches no guest. When it turns true, each callback is enabled again
 * exactly when the guest has it enabled, in the order the guest registered them.
 *
 * Pass false for content composed hidden, beneath the content on show: an app loaded in the
 * background that replaces the one on show only once its first changes arrive (the host templates'
 * update swap), or, from NF5's (A), the live content beneath a cached first frame. Composed with
 * true it behaves exactly as the overload without [backEnabled].
 *
 * On iOS a guest never receives back (Treehouse's iOS dispatcher registers nothing), so there the
 * gate changes nothing.
 */
@ExperimentalKeliverBackApi
@Composable
public fun <A : AppService> TreehouseContent(
  treehouseApp: TreehouseApp<A>,
  widgetSystem: WidgetSystem<@Composable (Modifier) -> Unit>,
  contentSource: TreehouseContentSource<A>,
  backEnabled: Boolean,
  modifier: Modifier = Modifier,
  dynamicContentWidgetFactory: DynamicContentWidgetFactory<@Composable (Modifier) -> Unit> =
    EmptyDynamicContentWidgetFactory,
) {
  TreehouseContent(
    treehouseApp = treehouseApp,
    widgetSystem = widgetSystem,
    contentSource = contentSource,
    modifier = modifier,
    dynamicContentWidgetFactory = dynamicContentWidgetFactory,
    backEnabled = backEnabled,
    platformBackDispatcher = platformOnBackPressedDispatcher(),
  )
}

/** Both overloads; tests pass their own [platformBackDispatcher]. */
@Composable
internal fun <A : AppService> TreehouseContent(
  treehouseApp: TreehouseApp<A>,
  widgetSystem: WidgetSystem<@Composable (Modifier) -> Unit>,
  contentSource: TreehouseContentSource<A>,
  dynamicContentWidgetFactory: DynamicContentWidgetFactory<@Composable (Modifier) -> Unit>,
  backEnabled: Boolean,
  platformBackDispatcher: OnBackPressedDispatcher,
  modifier: Modifier = Modifier,
) {
  // Created with the view below (one per widget system), so the view's guest always registers
  // through the gate this composition opens and closes.
  val onBackPressedDispatcher = remember(widgetSystem) { BackGate(platformBackDispatcher, open = backEnabled) }
  SideEffect { onBackPressedDispatcher.open = backEnabled }

  var viewportSize: Size? by remember { mutableStateOf(null) }
  val density = LocalDensity.current
  val uiConfiguration = UiConfiguration(
    darkMode = isSystemInDarkTheme(),
    safeAreaInsets = safeAreaInsets(),
    viewInsets = Margin.Zero,
    viewportSize = viewportSize,
    density = density.density.toDouble(),
    layoutDirection = when (LocalLayoutDirection.current) {
      LayoutDirection.Ltr -> RedwoodLayoutDirection.Ltr
      LayoutDirection.Rtl -> RedwoodLayoutDirection.Rtl
    },
  )
  val treehouseView = remember(widgetSystem) {
    object : TreehouseView<@Composable (Modifier) -> Unit> {
      override val children: ComposeWidgetChildren = ComposeWidgetChildren()

      override val value: @Composable (Modifier) -> Unit = {
        children.Render()
      }

      override val dynamicContentWidgetFactory = dynamicContentWidgetFactory
      override val onBackPressedDispatcher = onBackPressedDispatcher
      override val uiConfiguration = MutableStateFlow(uiConfiguration)

      // TODO TreehouseView is a weird type and shouldn't extend from RedwoodView. The concept
      //  of this registry shouldn't exist for Treehouse / should be auto-wired via RedwoodContent.
      override val savedStateRegistry: SavedStateRegistry? get() = null
      override val widgetSystem = widgetSystem
      override val readyForContent = true
      override var readyForContentChangeListener: ReadyForContentChangeListener<@Composable (Modifier) -> Unit>? = null
      override var saveCallback: TreehouseView.SaveCallback? = null
      override val stateSnapshotId = StateSnapshot.Id(null)
    }
  }
  LaunchedEffect(treehouseView, uiConfiguration) {
    treehouseView.uiConfiguration.value = uiConfiguration
  }
  DisposableEffect(treehouseView, contentSource) {
    val closeable = contentSource.bindWhenReady(treehouseView, treehouseApp)
    onDispose {
      closeable.close()
    }
  }

  Box(
    modifier = modifier.onSizeChanged { size ->
      viewportSize = with(Density(density.density.toDouble())) {
        Size(size.width.toDp().value.redwoodDp, size.height.toDp().value.redwoodDp)
      }
    },
  ) {
    treehouseView.value(Modifier)
  }
}

@Composable
internal expect fun platformOnBackPressedDispatcher(): OnBackPressedDispatcher
