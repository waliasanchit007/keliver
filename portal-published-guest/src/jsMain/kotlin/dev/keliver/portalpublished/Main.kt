package dev.keliver.portalpublished

import app.cash.zipline.Zipline
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portaldevice.PortalPresenter

/** Zipline guest entry for the published bundle. */
fun main() {
  val zipline = Zipline.get()
  // M7: the data-layer capability — hosts that declare HostSqlDriver@1 bind it.
  val sql = runCatching { zipline.take<HostSqlDriver>("HostSqlDriver") }.getOrNull()
  zipline.bind<PortalPresenter>("PortalPresenter", RealPublishedPresenter(zipline.json, sql))
}
