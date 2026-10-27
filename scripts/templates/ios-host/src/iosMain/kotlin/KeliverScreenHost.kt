// A fixed package, the same in every host and every guest: Zipline identifies a function by
// its signature, its parameter types' packages included, so launchRoute(String,
// KeliverScreenHost) reaches a guest only when both declare this type here.
package dev.keliver.hostkit

import app.cash.zipline.ZiplineService

/** What a screen may ask of the host that shows it (host kit). */
interface KeliverScreenHost : ZiplineService {
  /** Close this screen: the app's onClose for it (by default the host closes it as it was shown). */
  fun closeScreen()
}
