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
 *
 * The memory profile (M5) adds "memory <nanos> at=<point> cycle=<n> …" lines, what
 * KeliverMemory.kt describes, at these points (the console and the file, not onTrace):
 *
 *   open     a Keliver screen is being made, before its Compose view controller
 *   ui       two display-link ticks after that screen's first composition (its Loading screen)
 *   runtime  a QuickJS runtime was made, before any code loaded (Zipline's ziplineCreated)
 *   code     the bundle's code loaded (with the code mark)
 *   content  with the guest's content mark
 *   closed   2 s after a memory cycle closed its screen, after collecting both heaps
 *
 * A memory cycle opens a second screen over the shown one, waits for its content (at most
 * 15 s), closes it and samples "closed"; cycle=<n> counts them (0 before the first). One runs
 * at a time, and only after the shown screen's content. Post
 * the Darwin notification "<bundle id>.keliver.memory-cycle" for one
 * (`xcrun simctl spawn <udid> notifyutil -p <name>`, or `xcrun devicectl device notification
 * post --device <id> --name <name>` on a phone). Growth from one cycle's "closed" to the next
 * is what a screen did not give back.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package @@PACKAGE@@

import app.cash.zipline.Call
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.protocol.Change
import dev.keliver.treehouse.AppLifecycle
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.ZiplineTreehouseUi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
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
import platform.darwin.NOTIFY_STATUS_OK
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time
import platform.darwin.notify_register_dispatch
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.remove
import platform.posix.stat
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
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
  private var sending: Frame? = null // the frame whose sendFrame call is running; the Zipline thread

  // M5. The app whose QuickJS heap is read, on its Zipline dispatcher.
  private val watched = AtomicReference<TreehouseApp<*>?>(null)
  private val memoryScope = CoroutineScope(SupervisorJob())
  private val cycle = AtomicInt(0)
  private var cycleRunning = false // main thread: from a memory cycle's open until its "closed"
  private var cycleContent: (() -> Unit)? = null // main thread: the running cycle's screen showed its content
  private var contentSeen = false // main thread: a content mark came; a cycle waits for the first

  /** Once, by the host (main thread): the file sink, started again when it is long. */
  fun start() {
    if (path != null) return
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return
    val dir = "$caches/keliver"
    val file = "$dir/timing.log"
    path = file
    registerMemoryCycle()
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
    return Frame(now()).also { sending = it }
  }

  /** EventListener.callEnd. */
  fun callEnd(call: Call, startValue: Any?) {
    val ended = startValue as? Frame ?: return
    val guest = now() - ended.start
    if (sending === ended) sending = null
    // The frame and its change lists in one sample, so a window never splits them.
    sample {
      frame(guest, ended.lists.size)
      for (list in ended.lists) changeList(list.lead, list.sink, list.count)
    }
  }

  /** A memory line two display-link ticks after the main-thread work queued so far (M5). */
  fun memoryAfterFrame(point: String) {
    dispatch_async(dispatch_get_main_queue()) { afterTick { afterTick { memory(point) } } }
  }

  /** The app whose QuickJS heap the memory profile reads (M5). */
  fun watch(app: TreehouseApp<*>) {
    watched.value = app
  }

  /**
   * A memory line for [point] (M5): its time and the footprint now. On [zipline]'s thread when
   * it is given (a runtime being loaded), QuickJS read here; otherwise from any thread, QuickJS
   * read next on the watched app's Zipline dispatcher (after the guest work queued there).
   */
  fun memory(point: String, zipline: Zipline? = null) {
    val at = cycle.value
    val nanos = now()
    val footprint = KeliverMemory.footprint()
    if (zipline != null) {
      emitMemory(point, at, nanos, footprint, runCatching { KeliverMemory.quickJs(zipline, gc = false) }.getOrNull(), null)
      return
    }
    val app = watched.value
    val code = app?.zipline?.value
    if (app == null || code == null) {
      emitMemory(point, at, nanos, footprint, null, null)
      return
    }
    memoryScope.launch(app.dispatchers.zipline) {
      emitMemory(point, at, nanos, footprint, runCatching { KeliverMemory.quickJs(code, gc = false) }.getOrNull(), null)
    }
  }

  private fun emitMemory(point: String, at: Int, nanos: Long, footprint: Pair<Long, Long?>?, quickJs: Triple<Long, Long, Long>?, kotlinHeap: Long?) {
    val line = KeliverMemory.line(point, at, footprint, quickJs, kotlinHeap)
    dispatch_async(dispatch_get_main_queue()) { record("memory", nanos, line) }
  }

  /**
   * Cycle [n]'s "closed": both heaps collected first (the Kotlin one here, on main; QuickJS on
   * its thread), and the footprint read after both. Then [done], on main.
   */
  private fun memoryClosed(n: Int, done: () -> Unit) {
    val kotlinHeap = runCatching { KeliverMemory.kotlinHeapAfterCollect() }.getOrNull()
    val app = watched.value
    val code = app?.zipline?.value
    if (app == null || code == null) {
      emitMemory("closed", n, now(), KeliverMemory.footprint(), null, kotlinHeap)
      done()
      return
    }
    // done() when the job ends however it ends (a throw, a cancel): a cycle left running would
    // refuse every later one. A stopped app has no Zipline, so it takes the branch above.
    memoryScope.launch(app.dispatchers.zipline) {
      val quickJs = runCatching { KeliverMemory.quickJs(code, gc = true) }.getOrNull()
      emitMemory("closed", n, now(), KeliverMemory.footprint(), quickJs, kotlinHeap)
    }.invokeOnCompletion { dispatch_async(dispatch_get_main_queue()) { done() } }
  }

  /** "<bundle id>.keliver.memory-cycle" runs one memory cycle; main thread. */
  private fun registerMemoryCycle() {
    val name = "${NSBundle.mainBundle.bundleIdentifier ?: "keliver"}.keliver.memory-cycle"
    val status = memScoped {
      val token = alloc<IntVar>()
      notify_register_dispatch(name, token.ptr, dispatch_get_main_queue()) { _ -> memoryCycle() }
    }
    if (status != NOTIFY_STATUS_OK.toUInt()) println("KeliverTiming: could not register $name (notify status $status)")
  }

  /** Opens a second screen over the shown one, closes it after its content, then samples "closed"; main thread. */
  @OptIn(kotlin.experimental.ExperimentalNativeApi::class) // WeakReference
  private fun memoryCycle() {
    if (cycleRunning) {
      println("KeliverTiming: memory-cycle: one is already running; this one is ignored")
      return
    }
    if (!contentSeen) {
      // Its own screen's content mark would be taken for the shown screen's.
      println("KeliverTiming: memory-cycle: the shown screen has no content yet; ignored")
      return
    }
    @Suppress("DEPRECATION")
    var top: UIViewController? = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    val over = top ?: run {
      println("KeliverTiming: memory-cycle: no view controller to open a screen over")
      return
    }
    cycleRunning = true
    val n = cycle.incrementAndGet()
    val screen = Keliver.viewController().apply { modalPresentationStyle = UIModalPresentationFullScreen }
    // No closure below holds the screen, only this weak reference. Kotlin/Native's own
    // references to it (this local) keep it until a collection, whose release of it then runs
    // on main: "closed" collects first and lets main run that release before it measures.
    val shown = kotlin.native.ref.WeakReference(screen)
    var ended = false
    var sampled = false
    val closed = {
      if (!sampled) {
        sampled = true
        if (shown.get()?.presentingViewController != null) {
          println("KeliverTiming: memory-cycle $n: its screen is still up; this cycle's closed includes it")
        }
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC.toLong()), dispatch_get_main_queue()) {
          runCatching { KeliverMemory.kotlinHeapAfterCollect() }
          dispatch_async(dispatch_get_main_queue()) { memoryClosed(n) { cycleRunning = false } }
        }
      }
    }
    val end = {
      if (!ended) {
        ended = true
        cycleContent = null
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC.toLong()), dispatch_get_main_queue()) {
          // Only this cycle's screen, and only while it is up: it may have closed itself
          // (closeScreen), and something else on top of over is the app's own.
          val up = shown.get()
          if (up != null && over.presentedViewController === up && !up.isBeingDismissed()) {
            over.dismissViewControllerAnimated(false) { closed() }
          }
          // And in any case: UIKit runs no completion for a dismiss it refuses.
          dispatch_after(dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC.toLong()), dispatch_get_main_queue()) { closed() }
        }
      }
    }
    cycleContent = end
    println("KeliverTiming: memory-cycle $n: opening a screen")
    over.presentViewController(screen, animated = false, completion = null)
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 15 * NSEC_PER_SEC.toLong()), dispatch_get_main_queue()) {
      if (!ended) println("KeliverTiming: memory-cycle $n: no content within 15 s; closing it")
      end()
    }
  }

  /** Binds KeliverTrace for the guest's marks. */
  fun bindServices(zipline: Zipline) {
    zipline.bind<KeliverTrace>("KeliverTrace", GuestTrace)
  }

  private fun changeList(received: Long, sinkNanos: Long, count: Int) {
    val frame = sending
    if (frame != null) {
      frame.lists += ChangeListSpans(received - frame.start, sinkNanos, count)
    } else {
      sample { changeList(null, sinkNanos, count) }
    }
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
      // A frame profile or memory line: the console and the file, not onTrace, whose marks are moments.
      writeLine("$name $nanos $detail")
      return
    }
    runCatching { Keliver.onTrace?.invoke(name, nanos) }.onFailure { println("KeliverTiming: onTrace threw: ${it.message}") }
    writeLine("$name $nanos")
    if (name == "content") {
      contentSeen = true
      memory("content")
      cycleContent?.invoke()
    }
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
      when {
        // The host's own lines: a guest mark of that name would read as one of them.
        name == "memory" || name == "frames" -> println("KeliverTiming: ignored a guest mark: '$name' is the host's")
        guestName.matches(name) -> markAfterFrame(name)
        else -> println("KeliverTiming: ignored a guest mark: names are a-z, 0-9 and '-', at most 32")
      }
    }
  }

  /** A guest frame being sent, and the change lists it sent so far; the Zipline thread. */
  private class Frame(val start: Long) {
    val lists = ArrayList<ChangeListSpans>(2)
  }

  private class ChangeListSpans(val lead: Long, val sink: Long, val count: Int)

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
    override fun start(host: ZiplineTreehouseUi.Host) {
      // A screen starts between frames: a frame whose callEnd never came (its call threw) is over.
      sending = null
      ui.start(FirstFrameHost(host))
    }
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
