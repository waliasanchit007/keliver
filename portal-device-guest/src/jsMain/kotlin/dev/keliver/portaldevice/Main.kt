package dev.keliver.portaldevice

import app.cash.zipline.Zipline
import dev.keliver.portal.sql.HostSqlDriver

/**
 * Zipline guest entry — runs once when the host loads the bundle. The host has
 * already bound its services (bindServices), so we take them and bind the guest
 * presenter under "PortalPresenter" for the host to take().
 */
fun main() {
  val zipline = Zipline.get()
  val hostApi = zipline.take<HostApi>("HostApi")
  // M9: the compiled screen's data layer (bound by hosts declaring HostSqlDriver@1).
  val sql = runCatching { zipline.take<HostSqlDriver>("HostSqlDriver") }.getOrNull()
  zipline.bind<PortalPresenter>("PortalPresenter", RealPortalPresenter(hostApi, sql, zipline.json))
}
