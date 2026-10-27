package @@PACKAGE@@

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

  /**
   * K2: the screen for [route] ([KeliverRoute.encode]'s text: `orders/42?tab=items`, its query
   * already cut to the app's allowed keys); [screen] is how it closes itself. A guest scaffolded
   * before the host kit has no such function: the host calls [launch] instead.
   */
  fun launchRoute(route: String, screen: KeliverScreenHost): ZiplineTreehouseUi
}

/** What a screen may ask of the host that shows it (K2). */
interface KeliverScreenHost : ZiplineService {
  /** Close this screen: the app's onClose for it, by default a pop or a dismiss. */
  fun closeScreen()
}
