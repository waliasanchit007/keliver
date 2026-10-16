/*
 * Timing marks (M0) are compiled into the debug and benchmark build types only
 * (src/timing/kotlin/KeliverTiming.kt). Every other build type, the store build among them,
 * gets this file instead: the same calls, doing nothing. Scaffolded by
 * keliver-new-production-host.sh.
 */
package @@PACKAGE@@

import android.content.Context
import app.cash.zipline.Zipline
import dev.keliver.treehouse.ZiplineTreehouseUi

@Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE")
internal object KeliverTiming {
  inline fun start(context: Context) = Unit

  inline fun mark(name: String) = Unit

  inline fun markAfterFrame(name: String) = Unit

  inline fun firstFrame(ui: ZiplineTreehouseUi): ZiplineTreehouseUi = ui

  inline fun bindServices(zipline: Zipline) = Unit
}
