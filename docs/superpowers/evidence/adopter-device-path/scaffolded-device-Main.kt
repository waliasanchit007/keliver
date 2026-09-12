package kiosk.device

import androidx.compose.runtime.Composable
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kiosk.logic.HomePresenter
import kiosk.screens.HomeScreen

/**
 * The service the generic keliver device host take()s.
 *
 * Declared here BY SHAPE on purpose: Zipline binds services by name and
 * signature, so this app does not need the host's own module on its
 * classpath — which matters, because that module is not published.
 */
interface PortalPresenter : AppService, ZiplineService {
  fun launch(): ZiplineTreehouseUi
}

private class AppUi : TreehouseUi {
  @Composable
  override fun Show() {
    HomeScreen(HomePresenter())
  }
}

private class AppPresenter(json: kotlinx.serialization.json.Json) : PortalPresenter {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )
  override fun launch(): ZiplineTreehouseUi = AppUi().asZiplineTreehouseUi(appLifecycle)
}

fun main() {
  val zipline = Zipline.get()
  // If your presenter needs HTTP, the host binds a HostHttpProvider under
  // "HostHttp": zipline.take<HostHttpProvider>("HostHttp").
  zipline.bind<PortalPresenter>("PortalPresenter", AppPresenter(zipline.json))
}
