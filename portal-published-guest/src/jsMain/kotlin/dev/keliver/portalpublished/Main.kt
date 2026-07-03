package dev.keliver.portalpublished

import app.cash.zipline.Zipline
import dev.keliver.portaldevice.PortalPresenter

/** Zipline guest entry for the published bundle. */
fun main() {
  val zipline = Zipline.get()
  zipline.bind<PortalPresenter>("PortalPresenter", RealPublishedPresenter(zipline.json))
}
