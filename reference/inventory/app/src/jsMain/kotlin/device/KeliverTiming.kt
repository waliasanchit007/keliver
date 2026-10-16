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
 * before the marks) the first call fails, and from then on it does nothing.
 */
object KeliverTiming {
  private var trace: KeliverTrace? = null
  private var unavailable = false

  fun mark(name: String) {
    if (unavailable) return
    try {
      val t = trace ?: Zipline.get().take<KeliverTrace>("KeliverTrace").also { trace = it }
      t.mark(name)
    } catch (e: Exception) {
      // No KeliverTrace on this host: stop asking.
      unavailable = true
      trace?.close()
      trace = null
    }
  }
}
