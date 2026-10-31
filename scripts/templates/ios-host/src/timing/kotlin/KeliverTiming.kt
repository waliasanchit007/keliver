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
 *
 * The guest frame profile (M4) adds one line per window of up to 60 frames, or 1 s after the
 * window's first sample: "frames <nanos> n=… guest=… lead=… sink=… apply=… queue=…", what
 * KeliverFrames.kt describes. It goes to the console and the file, not to onTrace.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package @@PACKAGE@@

import app.cash.zipline.Call
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.protocol.Change
import dev.keliver.treehouse.AppLifecycle
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
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time
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

  // M4. The window is used on io only. sendFrame, and the change lists the guest sends during
  // it, arrive on the Zipline thread.
  private val window = FrameWindow()
  private var windowGeneration = 0
  private var windowOpen = false
  private var frameStart = 0L // the Zipline thread
  private var frameLists = 0 // the Zipline thread

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
    val nanos = now()
    dispatch_async(dispatch_get_main_queue()) { record(name, nanos) }
  }

  private fun now(): Long = (CACurrentMediaTime() * 1_000_000_000.0).toLong()

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

  /** EventListener.callStart: times the host's sendFrame calls, the guest's frames (M4). */
  fun callStart(call: Call): Any? {
    if (call.service !is AppLifecycle || !call.function.signature.startsWith("fun sendFrame(")) return null
    frameLists = 0
    return FrameStart(now()).also { frameStart = it.nanos }
  }

  /** EventListener.callEnd. */
  fun callEnd(call: Call, startValue: Any?) {
    val start = startValue as? FrameStart ?: return
    val guest = now() - start.nanos
    val lists = frameLists
    frameStart = 0L
    sample { frame(guest, lists) }
  }

  /** Binds KeliverTrace for the guest's marks. */
  fun bindServices(zipline: Zipline) {
    zipline.bind<KeliverTrace>("KeliverTrace", GuestTrace)
  }

  private fun changeList(received: Long, sinkNanos: Long, count: Int) {
    val start = frameStart
    if (start != 0L) frameLists++
    sample { changeList(if (start != 0L) received - start else null, sinkNanos, count) }
  }

  /** Adds to the window on io; a window ends when full, or 1 s after its first sample. */
  private fun sample(add: FrameWindow.() -> Unit) {
    dispatch_async(io) {
      window.add()
      if (window.isFull) {
        flush()
      } else if (!windowOpen) {
        windowOpen = true
        val generation = windowGeneration
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC.toLong()), io) { if (generation == windowGeneration) flush() }
      }
    }
  }

  private fun flush() {
    windowGeneration++
    windowOpen = false
    val line = window.take() ?: return
    val nanos = now()
    dispatch_async(dispatch_get_main_queue()) { record("frames", nanos, line) }
  }

  private fun record(name: String, nanos: Long, detail: String? = null) {
    if (detail != null) {
      // A frame profile line: the console and the file, not onTrace, whose marks are moments.
      writeLine("$name $nanos $detail")
      return
    }
    runCatching { Keliver.onTrace?.invoke(name, nanos) }.onFailure { println("KeliverTiming: onTrace threw: ${it.message}") }
    writeLine("$name $nanos")
  }

  /** The console and the file sink; on the main thread. */
  private fun writeLine(line: String) {
    start() // a screen's open can come before the host's start()
    println("KeliverTiming: $line")
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

  private class FrameStart(val nanos: Long)

  /** One change list's wait for the main thread and its apply there; both callbacks run on main. */
  private class ApplySpan(private val posted: Long) {
    private var began = 0L
    fun begin() { began = now() }
    fun end() {
      val ended = now()
      sample { applied(began - posted, ended - began) }
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
      val received = now()
      val apply = ApplySpan(received)
      // dispatch_async to the main queue is first in, first out, as Dispatchers.Main's own posts are.
      dispatch_async(dispatch_get_main_queue()) { apply.begin() }
      // The host applies them on the main thread, after this returns; markAfterFrame queues behind that.
      host.sendChanges(changes)
      dispatch_async(dispatch_get_main_queue()) { apply.end() }
      changeList(received, now() - received, changes.size)
      if (changes.isNotEmpty() && marked.compareAndSet(0, 1)) markAfterFrame("first-frame")
    }
  }
}
