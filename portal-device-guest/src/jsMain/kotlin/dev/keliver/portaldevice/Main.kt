package dev.keliver.portaldevice

import app.cash.zipline.Zipline

/**
 * Zipline guest entry — runs once when the host loads the bundle. The host has
 * already bound its services (bindServices), so we take HostApi and bind the guest
 * presenter under "PortalPresenter" for the host to take().
 */
fun main() {
  val zipline = Zipline.get()
  val hostApi = zipline.take<HostApi>("HostApi")
  zipline.bind<PortalPresenter>("PortalPresenter", RealPortalPresenter(hostApi, zipline.json))
}
