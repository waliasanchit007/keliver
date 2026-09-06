package newsstand.device

import androidx.compose.runtime.Composable
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.http.HostHttpProvider
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import newsstand.logic.FeedPresenter
import newsstand.logic.FeedRepository
import newsstand.screens.FeedScreen

/**
 * The service the stock keliver device host takes(). Declared here by SHAPE —
 * Zipline binds services by name and signature, so this app does not need the
 * host's own module on its classpath.
 */
interface PortalPresenter : AppService, ZiplineService {
  fun launch(): ZiplineTreehouseUi
}

private class NewsstandUi(private val http: HostHttpProvider) : TreehouseUi {
  @Composable
  override fun Show() {
    FeedScreen(FeedPresenter(FeedRepository(http)))
  }
}

private class NewsstandPresenter(
  private val http: HostHttpProvider,
  json: kotlinx.serialization.json.Json,
) : PortalPresenter {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )
  override fun launch(): ZiplineTreehouseUi =
    NewsstandUi(http).asZiplineTreehouseUi(appLifecycle)
}

fun main() {
  val zipline = Zipline.get()
  // The host binds its HTTP provider under "HostHttp".
  val http = zipline.take<HostHttpProvider>("HostHttp")
  zipline.bind<PortalPresenter>("PortalPresenter", NewsstandPresenter(http, zipline.json))
}
