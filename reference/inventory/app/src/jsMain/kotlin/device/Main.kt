package inventory.device

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import dev.keliver.hostkit.KeliverScreenHost
import inventory.logic.InventoryApp

/**
 * The service the generic keliver device host take()s.
 *
 * Declared here BY SHAPE on purpose: Zipline binds services by name and
 * signature, so this app does not need the host's own module on its
 * classpath — which matters, because that module is not published.
 */
interface PortalPresenter : AppService, ZiplineService {
  fun launch(): ZiplineTreehouseUi
  fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi
}

private class AppUi(val route: String = "", private val screen: KeliverScreenHost? = null) : TreehouseUi {
  @Composable
  override fun Show() {
    // Hand-edited: the scaffolder wires one screen + one presenter; this app
    // navigates between two, so it mounts its own root (logic/InventoryApp.kt).
    InventoryApp()
    // The inventory is in memory: shown on the first frame (M0's content mark).
    LaunchedEffect(Unit) { KeliverTiming.mark("content") }
    // Hand-edited for the host kit's device checks (K4): the route as the guest received it,
    // as a timing mark, and two routes that close the screen or make it throw.
    if (route.isNotEmpty()) LaunchedEffect(route) {
      KeliverTiming.mark(routeMark(route))
      when (route.substringBefore('?')) {
        "k4/close" -> screen?.closeScreen()
        "k4/crash" -> error("k4: a guest exception, on purpose")
      }
    }
  }

  override fun close() {
    screen?.close() // the host's handle for this screen, released with it
  }
}

/** `k4/items?tab=stock` → `route-k4-items-tab-stock`: a timing mark's alphabet (a-z, 0-9, '-'), 32 at most. */
private fun routeMark(route: String): String =
  ("route-" + route.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')).take(32)

private class AppPresenter(json: kotlinx.serialization.json.Json) : PortalPresenter {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )
  override fun launch(): ZiplineTreehouseUi = AppUi().asZiplineTreehouseUi(appLifecycle)
  override fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi =
    AppUi(route, screen).asZiplineTreehouseUi(appLifecycle)
}

fun main() {
  val zipline = Zipline.get()
  // If your presenter needs HTTP, the host binds a HostHttpProvider under
  // "HostHttp": zipline.take<HostHttpProvider>("HostHttp").
  zipline.bind<PortalPresenter>("PortalPresenter", AppPresenter(zipline.json))
}
