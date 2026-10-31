/*
 * Timing marks (M0) are compiled only into builds whose Xcode build setting KELIVER_TIMING is
 * YES (Debug and Benchmark; src/timing/kotlin/KeliverTiming.kt). Every other build, Release
 * among them, gets this file instead: the same calls, doing nothing. Scaffolded by
 * keliver-new-ios-host.sh.
 */
package @@PACKAGE@@

import app.cash.zipline.Call
import app.cash.zipline.Zipline
import dev.keliver.treehouse.ZiplineTreehouseUi

@Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE")
internal object KeliverTiming {
  inline fun start() = Unit

  inline fun mark(name: String) = Unit

  inline fun markAfterFrame(name: String) = Unit

  inline fun firstFrame(ui: ZiplineTreehouseUi): ZiplineTreehouseUi = ui

  inline fun bindServices(zipline: Zipline) = Unit

  inline fun callStart(call: Call): Any? = null

  inline fun callEnd(call: Call, startValue: Any?) = Unit
}
