package dev.keliver.portaldevice

import app.cash.zipline.ZiplineService

/** Host service the guest calls to reach the network (QuickJS guests can't do HTTP). */
interface HostApi : ZiplineService {
  /** GET the url, return the response body as a string. */
  suspend fun httpCall(url: String): String
}
