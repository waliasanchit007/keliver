/*
 * The memory profile (M5; docs/DEVICE_HOST.md, "The memory profile"): what the process, QuickJS
 * and the Java heap hold at points of a screen's life, as "memory" lines that KeliverTiming
 * writes. Compiled only where KeliverTiming's real marks are (debug and benchmark builds).
 * Scaffolded by keliver-new-production-host.sh.
 *
 *   memory <nanos> at=<point> cycle=<n> rss=<KB> java=<KB> native=<KB> qjs=<used KB>/<allocated KB>
 *     objects=<QuickJS objects> pss=<KB> graphics=<KB>
 *
 *   rss       the process's resident memory (/proc/self/statm), cheap enough for every point
 *   java      the Java heap in use (Runtime); at "closed" after a collection
 *   native    the native heap in use (Debug.getNativeHeapAllocatedSize): QuickJS's heap is in it
 *   qjs       QuickJS's heap: in use / allocated ("-" before the runtime starts)
 *   objects   QuickJS's object count ("-" with qjs)
 *   pss       the proportional set size, what Android's memory accounting counts; only at
 *             "closed" (Debug.getMemoryInfo walks every mapping, too slow for start-up points)
 *   graphics  the GL, EGL and gfx share of pss; only at "closed"
 */
package @@PACKAGE@@

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import app.cash.zipline.EngineApi
import app.cash.zipline.Zipline
import java.io.File

internal object KeliverMemory {
  private val pageSize: Long by lazy { runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L) }

  /** The process's numbers now; pss and graphics only when [full]. From any thread. */
  class Usage(val rss: Long?, val java: Long, val native: Long, val pss: Long?, val graphics: Long?)

  /** The process now, in bytes; with [full], also Debug.getMemoryInfo's pss and graphics (slow). From any thread. */
  fun usage(full: Boolean): Usage {
    val runtime = Runtime.getRuntime()
    // statm's second field: resident pages.
    val rss = runCatching { File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * pageSize }.getOrNull()
    var pss: Long? = null
    var graphics: Long? = null
    if (full) {
      runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        pss = info.totalPss * 1024L
        graphics = info.getMemoryStat("summary.graphics")?.toLongOrNull()?.times(1024L)
      }
    }
    return Usage(rss, runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize(), pss, graphics)
  }

  /** QuickJS's heap in use and allocated, in bytes, and its object count; after a collection when [gc]. On [zipline]'s thread only. */
  @OptIn(EngineApi::class)
  fun quickJs(zipline: Zipline, gc: Boolean): Triple<Long, Long, Long> {
    val quickJs = zipline.quickJs
    if (gc) quickJs.gc()
    val usage = quickJs.memoryUsage
    return Triple(usage.memoryUsedSize, usage.memoryAllocatedSize, usage.objectsCount)
  }

  /** A full collection of the Java heap, finalizers run, and a second one for what they freed. */
  fun collectJava() {
    val runtime = Runtime.getRuntime()
    runtime.gc()
    System.runFinalization()
    runtime.gc()
  }

  /** One line's detail (after "memory <nanos> "). */
  fun line(point: String, cycle: Int, usage: Usage, quickJs: Triple<Long, Long, Long>?): String {
    fun kb(bytes: Long?) = bytes?.let { (it / 1024).toString() } ?: "-"
    return "at=$point cycle=$cycle rss=${kb(usage.rss)} java=${kb(usage.java)} native=${kb(usage.native)} " +
      "qjs=${quickJs?.let { "${kb(it.first)}/${kb(it.second)}" } ?: "-"} objects=${quickJs?.third ?: "-"} " +
      "pss=${kb(usage.pss)} graphics=${kb(usage.graphics)}"
  }
}
