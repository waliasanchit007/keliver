package dev.keliver.measure.host

import app.cash.zipline.ZiplineService
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.ZiplineTreehouseUi

/**
 * The service this host take()s from the guest bundle, declared BY SHAPE (the
 * guest's device/Main.kt declares the same). Keliver's own copy lives in
 * portal-device-guest, which is not published.
 */
interface PortalPresenter : AppService, ZiplineService {
  fun launch(): ZiplineTreehouseUi
}
