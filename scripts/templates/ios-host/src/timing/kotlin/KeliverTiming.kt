/*
 * Timing marks (M0; docs/DEVICE_HOST.md, "Timing marks"). This file is compiled only when
 * Xcode's build setting KELIVER_TIMING is YES: the Debug and Benchmark configurations set it
 * (build.gradle). A Release build compiles src/notiming/ (the same functions, doing nothing)
 * instead. Scaffolded by keliver-new-ios-host.sh.
 *
 * Each mark goes to Keliver.onTrace(name, nanos) on the main thread, to the console as
 * "KeliverTiming: <name> <nanos>", and to Library/Caches/keliver/timing.log in the app's
 * container. <nanos> is CACurrentMediaTime(), a monotonic clock, in nanoseconds.
 *
 *   open         a Keliver screen was made (Keliver.viewController())
 *   code         the bundle's code loaded (Zipline's codeLoadSuccess), each load
 *   first-frame  two display-link ticks after the host applied a screen's first change list
 *   content      two display-link ticks after the guest said its data is shown:
 *                KeliverTiming.mark("content") in the guest, through KeliverTrace
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package @@PACKAGE@@

import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.protocol.Change
import dev.keliver.treehouse.ZiplineTreehouseUi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSFileManager
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSUserDomainMask
import platform.QuartzCore.CACurrentMediaTime
import platform.QuartzCore.CADisplayLink
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.remove
import platform.posix.stat
import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

/**
 * The guest's way to mark a moment, declared BY SHAPE (as PortalPresenter is): the guest's
 * device/KeliverTiming.kt declares the same interface and takes it when the host binds it.
 */
public interface KeliverTrace : ZiplineService {
  public fun mark(name: String)
}

internal object KeliverTiming {
  private const val MAX_FILE_BYTES = 256L * 1024L
  private val guestName = Regex("[a-z0-9][a-z0-9-]{0,31}")
  private val io = dispatch_queue_create("keliver-timing", null)
  private var path: String? = null // main thread

  /** Once, by the host (main thread): the file sink, started again when it is long. */
  fun start() {
    if (path != null) return
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return
    val dir = "$caches/keliver"
    val file = "$dir/timing.log"
    path = file
    dispatch_async(io) {
      NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
      val size = memScoped { val s = alloc<stat>(); if (stat(file, s.ptr) == 0) s.st_size else 0L }
      if (size > MAX_FILE_BYTES) remove(file)
    }
  }

  /** A mark now. From any thread. */
  fun mark(name: String) {
    val nanos = (CACurrentMediaTime() * 1_000_000_000.0).toLong()
    dispatch_async(dispatch_get_main_queue()) { record(name, nanos) }
  }

  /**
   * A mark two display-link ticks after the main-thread work queued so far: the first tick can
   * come in the same vsync as Compose's own draw, so the second is after a frame was shown.
   */
  fun markAfterFrame(name: String) {
    dispatch_async(dispatch_get_main_queue()) { afterTick { afterTick { mark(name) } } }
  }

  private fun afterTick(then: () -> Unit) {
    CADisplayLink.displayLinkWithTarget(FrameTick(then), NSSelectorFromString("tick:")).addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
  }

  /** [ui], reporting first-frame after its first change list. */
  fun firstFrame(ui: ZiplineTreehouseUi): ZiplineTreehouseUi = FirstFrameUi(ui)

  /** Binds KeliverTrace for the guest's marks. */
  fun bindServices(zipline: Zipline) {
    zipline.bind<KeliverTrace>("KeliverTrace", GuestTrace)
  }

  private fun record(name: String, nanos: Long) {
    start() // a screen's open can come before the host's start()
    val line = "$name $nanos"
    println("KeliverTiming: $line")
    runCatching { Keliver.onTrace?.invoke(name, nanos) }.onFailure { println("KeliverTiming: onTrace threw: ${it.message}") }
    val file = path ?: return
    dispatch_async(io) {
      fopen(file, "a")?.let { f ->
        fputs("$line\n", f)
        fclose(f)
      }
    }
  }

  private object GuestTrace : KeliverTrace {
    override fun mark(name: String) {
      if (guestName.matches(name)) markAfterFrame(name) else println("KeliverTiming: ignored a guest mark: names are a-z, 0-9 and '-', at most 32")
    }
  }

  private class FrameTick(private val onTick: () -> Unit) : NSObject() {
    @ObjCAction
    fun tick(link: CADisplayLink) {
      link.invalidate()
      onTick()
    }
  }

  private class FirstFrameUi(private val ui: ZiplineTreehouseUi) : ZiplineTreehouseUi by ui {
    override fun start(host: ZiplineTreehouseUi.Host) = ui.start(FirstFrameHost(host))
  }

  private class FirstFrameHost(private val host: ZiplineTreehouseUi.Host) : ZiplineTreehouseUi.Host by host {
    private val marked = AtomicInt(0)

    override fun sendChanges(changes: List<Change>) {
      // The host applies them on the main thread, after this returns; markAfterFrame queues behind that.
      host.sendChanges(changes)
      if (changes.isNotEmpty() && marked.compareAndSet(0, 1)) markAfterFrame("first-frame")
    }
  }
}
