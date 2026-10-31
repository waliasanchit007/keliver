/*
 * Timing marks (M0; docs/DEVICE_HOST.md, "Timing marks"). This file is compiled into the
 * DEBUG and BENCHMARK build types only: build.gradle adds src/timing/ to those two and
 * src/notiming/ (the same functions, doing nothing) to every other one, so a store build has
 * none of this. Scaffolded by keliver-new-production-host.sh.
 *
 * Each mark is one logcat line, tag KeliverTiming, "<name> <nanos>", and the same line appended
 * to files/keliver/timing.log (adb shell run-as <applicationId> cat files/keliver/timing.log).
 * <nanos> is SystemClock.elapsedRealtimeNanos(), a monotonic clock.
 *
 *   process      when Android started this process (Process.getStartElapsedRealtime)
 *   open         a KeliverScreen entered the composition: the screen was asked for
 *   code         the bundle's code loaded (Zipline's codeLoadSuccess), each load
 *   first-frame  one frame after the host applied a screen's first change list
 *   content      one frame after the guest said its data is shown: KeliverTiming.mark("content")
 *                in the guest, through the KeliverTrace service bound below
 *
 * first-frame and the guest's marks are taken two Choreographer callbacks after the host was
 * told, so they count a frame the user can see; the others when they happen.
 *
 * The guest frame profile (M4) adds one line per window of up to 60 frames, or 1 s after the
 * window's first sample: "frames <nanos> n=… guest=… lead=… sink=… apply=… queue=…", what
 * KeliverFrames.kt describes.
 */
package @@PACKAGE@@

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import app.cash.zipline.Call
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.protocol.Change
import dev.keliver.treehouse.AppLifecycle
import dev.keliver.treehouse.ZiplineTreehouseUi
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The guest's way to mark a moment, declared BY SHAPE (as PortalPresenter is): the guest's
 * device/KeliverTiming.kt declares the same interface and takes it when the host binds it.
 */
interface KeliverTrace : ZiplineService {
  fun mark(name: String)
}

internal object KeliverTiming {
  private const val LOG_TAG = "KeliverTiming"
  private const val FILE = "keliver/timing.log"
  private const val MAX_FILE_BYTES = 256L * 1024L
  private val guestName = Regex("[a-z0-9][a-z0-9-]{0,31}")
  private val main = Handler(Looper.getMainLooper())
  private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "keliver-timing").apply { isDaemon = true } }

  // M4. The window is used on io only. sendFrame, and the change lists the guest sends during
  // it, arrive on the Zipline thread.
  private val window = FrameWindow()
  private var windowGeneration = 0
  private var windowOpen = false
  @Volatile private var sending: Frame? = null // the frame whose sendFrame call is running

  @Volatile private var file: File? = null

  /** Once per process, by the host: the file sink (a long one starts again), and the process mark. */
  fun start(context: Context) {
    if (file != null) return
    val f = File(context.filesDir, FILE)
    file = f
    io.execute {
      runCatching {
        f.parentFile?.mkdirs()
        if (f.length() > MAX_FILE_BYTES) f.delete()
      }
    }
    if (Build.VERSION.SDK_INT >= 24) markAt("process", Process.getStartElapsedRealtime() * 1_000_000L)
  }

  /** A mark now. */
  fun mark(name: String) = markAt(name, SystemClock.elapsedRealtimeNanos())

  /** A mark one frame after the UI work queued so far: two Choreographer callbacks from the main thread. */
  fun markAfterFrame(name: String) {
    main.post {
      val choreographer = Choreographer.getInstance()
      choreographer.postFrameCallback { choreographer.postFrameCallback { mark(name) } }
    }
  }

  /** [ui], reporting first-frame after its first change list. */
  fun firstFrame(ui: ZiplineTreehouseUi): ZiplineTreehouseUi = FirstFrameUi(ui)

  /** EventListener.callStart: times the host's sendFrame calls, the guest's frames (M4). */
  fun callStart(call: Call): Any? {
    if (call.service !is AppLifecycle || !call.function.signature.startsWith("fun sendFrame(")) return null
    return Frame(System.nanoTime()).also { sending = it }
  }

  /** EventListener.callEnd. */
  fun callEnd(call: Call, startValue: Any?) {
    val ended = startValue as? Frame ?: return
    val guest = System.nanoTime() - ended.start
    if (sending === ended) sending = null
    // The frame and its change lists in one sample, so a window never splits them.
    sample {
      frame(guest, ended.lists.size)
      for (list in ended.lists) changeList(list.lead, list.sink, list.count)
    }
  }

  /** Binds KeliverTrace for the guest's marks. */
  fun bindServices(zipline: Zipline) {
    zipline.bind<KeliverTrace>("KeliverTrace", GuestTrace)
  }

  private fun markAt(name: String, nanos: Long, detail: String? = null) {
    val line = if (detail == null) "$name $nanos" else "$name $nanos $detail"
    Log.i(LOG_TAG, line)
    val f = file ?: return
    io.execute { runCatching { f.appendText("$line\n") } }
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
    io.execute {
      window.add()
      if (window.isFull) {
        flush()
      } else if (!windowOpen) {
        windowOpen = true
        val generation = windowGeneration
        io.schedule({ if (generation == windowGeneration) flush() }, 1, TimeUnit.SECONDS)
      }
    }
  }

  private fun flush() {
    windowGeneration++
    windowOpen = false
    window.take()?.let { markAt("frames", SystemClock.elapsedRealtimeNanos(), it) }
  }

  /**
   * On the main thread as an ASYNCHRONOUS message, as Dispatchers.Main posts the host's apply: an
   * ordinary message can be held behind a sync barrier while the apply runs ahead of it.
   */
  private fun onMain(block: () -> Unit) {
    main.sendMessage(Message.obtain(main, Runnable { block() }).apply { isAsynchronous = true })
  }

  /** A guest frame being sent, and the change lists it sent so far; the Zipline thread. */
  private class Frame(val start: Long) {
    val lists = ArrayList<ChangeListSpans>(2)
  }

  private class ChangeListSpans(val lead: Long, val sink: Long, val count: Int)

  /** One change list's wait for the main thread and its apply there; both callbacks run on main. */
  private class ApplySpan(private val posted: Long) {
    private var began = 0L
    fun begin() { began = System.nanoTime() }
    fun end() {
      val ended = System.nanoTime()
      sample { applied(began - posted, ended - began) }
    }
  }

  private object GuestTrace : KeliverTrace {
    override fun mark(name: String) {
      if (guestName.matches(name)) {
        markAfterFrame(name)
      } else {
        Log.w(LOG_TAG, "ignored a guest mark: names are a-z, 0-9 and '-', at most 32")
      }
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
    private val marked = AtomicBoolean(false)

    override fun sendChanges(changes: List<Change>) {
      val received = System.nanoTime()
      val apply = ApplySpan(received)
      onMain(apply::begin)
      // The host applies them on the main thread, after this returns; markAfterFrame queues behind that.
      host.sendChanges(changes)
      onMain(apply::end)
      changeList(received, System.nanoTime() - received, changes.size)
      if (changes.isNotEmpty() && marked.compareAndSet(false, true)) markAfterFrame("first-frame")
    }
  }
}
