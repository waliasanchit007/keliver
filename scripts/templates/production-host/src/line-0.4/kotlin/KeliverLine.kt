/*
 * What this host's Keliver line has that an older line's libraries do not: the 0.4 line (and the
 * 0.3.x libraries, which build as 0.4 does). The scaffolders (keliver-new-production-host.sh,
 * keliver-new-ios-host.sh) copy the file of the app's line (appRuntime.keliverVersion,
 * keliver-runtime-line.sh), so every other file of the host is the same Kotlin on every line. The
 * Android and iOS hosts ship the same file.
 */
package @@PACKAGE@@

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.DynamicContentWidgetFactory
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import dev.keliver.widget.WidgetSystem

/** The Keliver line this host was scaffolded for. */
internal const val KELIVER_LINE = "0.4"

/**
 * The host's Treehouse spec. The 0.4 line's Treehouse has no host features: its guests see none,
 * whatever HostFeatures.kt declares (from 0.5 the spec hands them over).
 */
internal abstract class KeliverSpec<A : AppService> : TreehouseApp.Spec<A>()

/**
 * Treehouse's content. The 0.4 line's TreehouseContent has no back gate, so [backEnabled] changes
 * nothing here: a hidden content's guest registers its back handlers with the activity as it binds,
 * and a back press in an update swap's short window goes to the new app (from 0.5 it does not).
 */
@Composable
internal fun <A : AppService> KeliverTreehouseContent(
  treehouseApp: TreehouseApp<A>,
  widgetSystem: WidgetSystem<@Composable (Modifier) -> Unit>,
  contentSource: TreehouseContentSource<A>,
  modifier: Modifier,
  dynamicContentWidgetFactory: DynamicContentWidgetFactory<@Composable (Modifier) -> Unit>,
  @Suppress("UNUSED_PARAMETER") backEnabled: Boolean,
) {
  TreehouseContent(
    treehouseApp = treehouseApp,
    widgetSystem = widgetSystem,
    contentSource = contentSource,
    modifier = modifier,
    dynamicContentWidgetFactory = dynamicContentWidgetFactory,
  )
}
