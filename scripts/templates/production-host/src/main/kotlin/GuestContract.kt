package @@PACKAGE@@

import app.cash.zipline.ZiplineService
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.ZiplineTreehouseUi

/**
 * The service this host take()s from the guest bundle, declared BY SHAPE.
 * Zipline binds services by name and signature, and the guest scaffolded by
 * keliver-new-device-target.sh declares the same shape in its device/Main.kt.
 * Keliver's own copy lives in portal-device-guest, which is not published.
 */
interface PortalPresenter : AppService, ZiplineService {
  fun launch(): ZiplineTreehouseUi
}
