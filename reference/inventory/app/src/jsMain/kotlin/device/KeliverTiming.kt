package inventory.device

import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService

/**
 * The host's timing service (M0), declared BY SHAPE as PortalPresenter is. Only debug and
 * benchmark hosts bind it.
 */
interface KeliverTrace : ZiplineService {
  fun mark(name: String)
}

/**
 * Marks a moment in the host's timing (docs/DEVICE_HOST.md, "Timing marks"): call
 * KeliverTiming.mark("content") once the screen's data is shown. Names are a-z, 0-9 and '-',
 * at most 32. With a host that does not bind KeliverTrace (a release build, or a host from
 * before the marks) it does nothing.
 */
object KeliverTiming {
  private val trace: KeliverTrace? by lazy {
    val zipline = Zipline.get()
    if ("KeliverTrace" in zipline.clientNames) zipline.take<KeliverTrace>("KeliverTrace") else null
  }

  fun mark(name: String) {
    trace?.mark(name)
  }
}
