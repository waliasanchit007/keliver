/*
 * What this host's Keliver line has that an older line's libraries do not: the 0.5 line. The
 * scaffolders (keliver-new-production-host.sh, keliver-new-ios-host.sh) copy the file of the app's
 * line (appRuntime.keliverVersion, keliver-runtime-line.sh), so every other file of the host is the
 * same Kotlin on every line. The Android and iOS hosts ship the same file.
 */
@file:OptIn(ExperimentalKeliverBackApi::class, ExperimentalKeliverHostFeaturesApi::class)

package @@PACKAGE@@

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.keliver.compose.ExperimentalKeliverBackApi
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.DynamicContentWidgetFactory
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import dev.keliver.widget.WidgetSystem

/** The Keliver line this host was scaffolded for. */
internal const val KELIVER_LINE = "0.5"

/** The host's Treehouse spec: it hands the guest the host features this host implements (HostFeatures.kt). */
internal abstract class KeliverSpec<A : AppService> : TreehouseApp.Spec<A>() {
  override val hostFeatures: Set<String>
    get() = HostFeatures.implemented
}

/**
 * Treehouse's content. [backEnabled] false: no back press reaches this content's guest (NF5 H0);
 * its back handlers are registered with the platform disabled, so a press goes to the content on
 * top of it, or to the activity. For content composed hidden, beneath the content on show.
 */
@Composable
internal fun <A : AppService> KeliverTreehouseContent(
  treehouseApp: TreehouseApp<A>,
  widgetSystem: WidgetSystem<@Composable (Modifier) -> Unit>,
  contentSource: TreehouseContentSource<A>,
  modifier: Modifier,
  dynamicContentWidgetFactory: DynamicContentWidgetFactory<@Composable (Modifier) -> Unit>,
  backEnabled: Boolean,
) {
  TreehouseContent(
    treehouseApp = treehouseApp,
    widgetSystem = widgetSystem,
    contentSource = contentSource,
    modifier = modifier,
    dynamicContentWidgetFactory = dynamicContentWidgetFactory,
    backEnabled = backEnabled,
  )
}
