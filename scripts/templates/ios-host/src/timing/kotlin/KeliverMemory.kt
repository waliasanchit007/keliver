/*
 * The memory profile (M5; docs/DEVICE_HOST.md, "The memory profile"): what the process, QuickJS
 * and the Kotlin heap hold at points of a screen's life, as "memory" lines that KeliverTiming
 * writes. Compiled only where KeliverTiming's real marks are (Debug and Benchmark builds).
 * Scaffolded by keliver-new-ios-host.sh.
 *
 *   memory <nanos> at=<point> cycle=<n> footprint=<KB> peak=<KB> qjs=<used KB>/<allocated KB>
 *     objects=<QuickJS objects> knheap=<KB>
 *
 *   footprint  the process's physical footprint (task_info TASK_VM_INFO phys_footprint), what
 *              Xcode's memory gauge shows and what iOS's memory limit counts
 *   peak       its highest so far ("-" before iOS 13's revision of the call)
 *   qjs        QuickJS's heap: in use / allocated ("-" before the runtime starts)
 *   objects    QuickJS's object count ("-" with qjs)
 *   knheap     the Kotlin/Native heap's live objects after a collection; only at "closed"
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package @@PACKAGE@@

import app.cash.zipline.EngineApi
import app.cash.zipline.Zipline
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.TASK_VM_INFO_COUNT
import platform.darwin.TASK_VM_INFO_REV3_COUNT
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t

internal object KeliverMemory {
  /** The process's footprint and its peak, in bytes (peak null when this iOS lacks it); null if the call failed. From any thread. */
  fun footprint(): Pair<Long, Long?>? = memScoped {
    val info = alloc<task_vm_info_data_t>()
    val count = alloc<mach_msg_type_number_tVar>().apply { value = TASK_VM_INFO_COUNT.convert() }
    if (task_info(mach_task_self_, TASK_VM_INFO.convert(), info.ptr.reinterpret(), count.ptr) != KERN_SUCCESS) return null
    val peak = if (count.value >= TASK_VM_INFO_REV3_COUNT.convert<UInt>()) info.ledger_phys_footprint_peak.toLong() else null
    info.phys_footprint.toLong() to peak
  }

  /** QuickJS's heap in use and allocated, in bytes, and its object count; after a collection when [gc]. On [zipline]'s thread only. */
  @OptIn(EngineApi::class)
  fun quickJs(zipline: Zipline, gc: Boolean): Triple<Long, Long, Long> {
    val quickJs = zipline.quickJs
    if (gc) quickJs.gc()
    val usage = quickJs.memoryUsage
    return Triple(usage.memoryUsedSize, usage.memoryAllocatedSize, usage.objectsCount)
  }

  /** The Kotlin/Native heap's live objects in bytes, after a full collection; null when the runtime does not say. */
  @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
  fun kotlinHeapAfterCollect(): Long? {
    kotlin.native.runtime.GC.collect()
    return kotlin.native.runtime.GC.lastGCInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes
  }

  /** One line's detail (after "memory <nanos> "). */
  fun line(point: String, cycle: Int, footprint: Pair<Long, Long?>?, quickJs: Triple<Long, Long, Long>?, kotlinHeap: Long?): String {
    fun kb(bytes: Long?) = bytes?.let { (it / 1024).toString() } ?: "-"
    return "at=$point cycle=$cycle footprint=${kb(footprint?.first)} peak=${kb(footprint?.second)} " +
      "qjs=${quickJs?.let { "${kb(it.first)}/${kb(it.second)}" } ?: "-"} objects=${quickJs?.third ?: "-"} knheap=${kb(kotlinHeap)}"
  }
}
