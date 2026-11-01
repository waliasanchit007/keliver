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
 *
 * The memory profile (M5) adds "memory <nanos> at=<point> cycle=<n> …" lines, what
 * KeliverMemory.kt describes, at these points:
 *
 *   open     a KeliverScreen entered the composition (with the open mark)
 *   ui       one frame after that: two Choreographer callbacks (its Loading screen, for the first)
 *   runtime  a QuickJS runtime was made, before any code loaded (Zipline's ziplineCreated)
 *   code     the bundle's code loaded (after the code mark)
 *   content  with the guest's content mark
 *   closed   2 s after a memory cycle closed its screen, after collecting both heaps
 *
 * A memory cycle opens a second KeliverScreen over the shown one (a full-screen dialog), waits
 * for its content (at most 15 s), closes it and samples "closed"; cycle=<n> counts them (0 before
 * the first). One runs at a time, and only after the shown screen's content. Broadcast
 * [MEMORY_CYCLE] for one:
 *   adb shell am broadcast -a dev.keliver.debug.MEMORY_CYCLE -p <applicationId>
 * Growth from one cycle's "closed" to the next is what a screen did not give back.
 */
package @@PACKAGE@@

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import app.cash.zipline.Call
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.protocol.Change
import dev.keliver.treehouse.AppLifecycle
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.ZiplineTreehouseUi
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The guest's way to mark a moment, declared BY SHAPE (as PortalPresenter is): the guest's
 * device/KeliverTiming.kt declares the same interface and takes it when the host binds it.
 */
interface KeliverTrace : ZiplineService {
  fun mark(name: String)
}

internal object KeliverTiming {
  private const val LOG_TAG = "KeliverTiming"
  /** Runs one memory cycle (M5): adb shell am broadcast -a <this> -p <applicationId>. */
  private const val MEMORY_CYCLE = "dev.keliver.debug.MEMORY_CYCLE"
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

  // M5. The host whose screen a memory cycle opens, and its app, whose QuickJS heap is read on
  // its Zipline dispatcher.
  @Volatile private var watchedHost: KeliverHost? = null
  @Volatile private var watchedApp: TreehouseApp<*>? = null
  @Volatile private var appContext: Context? = null
  private val memoryScope = CoroutineScope(SupervisorJob())
  private val cycle = AtomicInteger(0)
  private var resumed: WeakReference<Activity>? = null // main thread: the activity in front
  private var running: MemoryCycle? = null // main thread: from a memory cycle's open until its "closed"
  private var contentSeen = false // main thread: a content mark came; a cycle waits for the first

  /** Once per process, by the host: the file sink (a long one starts again), and the process mark. */
  fun start(context: Context) {
    if (file != null) return
    val f = File(context.filesDir, FILE)
    file = f
    appContext = context.applicationContext
    trackActivities(context.applicationContext)
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
  fun markAfterFrame(name: String) = afterFrame { mark(name) }

  /** [block] on the main thread, one frame after the UI work queued so far. */
  private fun afterFrame(block: () -> Unit) {
    main.post {
      val choreographer = Choreographer.getInstance()
      choreographer.postFrameCallback { choreographer.postFrameCallback { block() } }
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

  /** A memory line one frame after the UI work queued so far (M5). */
  fun memoryAfterFrame(point: String) = afterFrame { memory(point) }

  /** The host a memory cycle opens a screen of, and its app, whose QuickJS heap the memory profile reads (M5). */
  fun watch(host: KeliverHost, app: TreehouseApp<*>) {
    watchedHost = host
    watchedApp = app
  }

  /**
   * A memory line for [point] (M5): its time and the process's numbers now. On [zipline]'s thread
   * when it is given (a runtime being loaded), QuickJS read here; otherwise from any thread,
   * QuickJS read next on the watched app's Zipline dispatcher (after the guest work queued there).
   */
  fun memory(point: String, zipline: Zipline? = null) {
    val at = cycle.get()
    val nanos = SystemClock.elapsedRealtimeNanos()
    val usage = KeliverMemory.usage(full = false)
    if (zipline != null) {
      emitMemory(point, at, nanos, usage, runCatching { KeliverMemory.quickJs(zipline, gc = false) }.getOrNull())
      return
    }
    val app = watchedApp
    val code = app?.zipline?.value
    if (app == null || code == null) {
      emitMemory(point, at, nanos, usage, null)
      return
    }
    memoryScope.launch(app.dispatchers.zipline) {
      emitMemory(point, at, nanos, usage, runCatching { KeliverMemory.quickJs(code, gc = false) }.getOrNull())
    }
  }

  private fun emitMemory(point: String, at: Int, nanos: Long, usage: KeliverMemory.Usage, quickJs: Triple<Long, Long, Long>?) {
    markAt("memory", nanos, KeliverMemory.line(point, at, usage, quickJs))
  }

  /**
   * A KeliverScreen in [activity] entered the composition: the activity a memory cycle opens over
   * when none resumed since the host started (an app whose host is made by its first screen).
   * While nothing is in front (paused), a screen composed in an activity behind takes it until
   * the next resume; a cycle sent then opens over that activity.
   */
  fun screenOpened(activity: Activity?) {
    if (resumed?.get() == null && activity != null) resumed = WeakReference(activity)
  }

  /** The guest's content mark, one frame after it came; main thread. */
  private fun contentShown() {
    if (!contentSeen) {
      contentSeen = true
      // Only now, off start-up: a cycle waits for the first content anyway.
      appContext?.let(::registerMemoryCycle)
    }
    memory("content")
    running?.contentShown()
  }

  /**
   * Cycle [n]'s "closed": both heaps collected first (QuickJS and then Java, on QuickJS's thread),
   * and pss read after both. Then [done], on main.
   */
  private fun memoryClosed(n: Int, done: () -> Unit) {
    val app = watchedApp
    val code = app?.zipline?.value
    if (app == null || code == null) {
      io.execute {
        // done() however this ends: a cycle left running would refuse every later one.
        try {
          KeliverMemory.collectJava()
          emitMemory("closed", n, SystemClock.elapsedRealtimeNanos(), KeliverMemory.usage(full = true), null)
        } finally {
          main.post(done)
        }
      }
      return
    }
    // done() when the job ends however it ends (a throw, a cancel): a cycle left running would
    // refuse every later one. A stopped app has no Zipline, so it takes the branch above.
    memoryScope.launch(app.dispatchers.zipline) {
      val quickJs = runCatching { KeliverMemory.quickJs(code, gc = true) }.getOrNull()
      KeliverMemory.collectJava()
      emitMemory("closed", n, SystemClock.elapsedRealtimeNanos(), KeliverMemory.usage(full = true), quickJs)
    }.invokeOnCompletion { main.post(done) }
  }

  /** The activity in front, the one a memory cycle opens a screen over. */
  private fun trackActivities(context: Context) {
    (context as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
      override fun onActivityPaused(activity: Activity) { if (resumed?.get() === activity) resumed = null }
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    })
  }

  private val cycleRegistered = AtomicBoolean(false)

  /** [MEMORY_CYCLE] runs one memory cycle; once, after the first content. */
  private fun registerMemoryCycle(context: Context) {
    if (!cycleRegistered.compareAndSet(false, true)) return
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) = memoryCycle()
    }
    // As the debug update check's: only a sender holding DUMP (adb's shell) is heard.
    ContextCompat.registerReceiver(
      context, receiver, IntentFilter(MEMORY_CYCLE),
      "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED,
    )
  }

  /** Opens a second screen over the shown one, closes it after its content, then samples "closed"; main thread. */
  private fun memoryCycle() {
    if (running != null) {
      Log.i(LOG_TAG, "memory-cycle: one is already running; this one is ignored")
      return
    }
    if (!contentSeen) {
      // Its own screen's content mark would be taken for the shown screen's.
      Log.i(LOG_TAG, "memory-cycle: the shown screen has no content yet; ignored")
      return
    }
    val host = watchedHost
    val over = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    if (host == null || over == null) {
      Log.i(LOG_TAG, "memory-cycle: no ${if (host == null) "host" else "activity in front"} to open a screen over")
      return
    }
    val n = cycle.incrementAndGet()
    // A window that cannot show (its activity went between the check and here) ends nothing.
    running = runCatching { MemoryCycle(n, host, over) }
      .onFailure { Log.w(LOG_TAG, "memory-cycle $n: could not open a screen: $it") }
      .getOrNull()
  }

  /**
   * One memory cycle. Nothing here holds its dialog but a weak reference (and the window manager,
   * while it shows), so the screen it showed can go once it is closed.
   */
  private class MemoryCycle(private val n: Int, host: KeliverHost, over: Activity) {
    private val shown: WeakReference<ComponentDialog>
    private var ended = false
    private var sampled = false

    init {
      val dialog = ComponentDialog(over, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
      shown = WeakReference(dialog)
      dialog.setOnDismissListener { closed() }
      // A screen that asks to close closes this dialog, not the activity under it.
      dialog.setContentView(ComposeView(dialog.context).apply {
        setContent { KeliverScreen(host, Modifier.fillMaxSize(), onClose = { shown.get()?.dismiss() }) }
      })
      Log.i(LOG_TAG, "memory-cycle $n: opening a screen")
      dialog.show()
      main.postDelayed({
        if (!ended) Log.i(LOG_TAG, "memory-cycle $n: no content within 15 s; closing it")
        end()
      }, 15_000)
    }

    fun contentShown() = end()

    private fun end() {
      if (ended) return
      ended = true
      main.postDelayed({
        // Only while it is up: the screen may have closed itself.
        shown.get()?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
        // And in any case: a dismiss that threw (its activity went) calls no listener.
        main.postDelayed({ closed() }, 1_000)
      }, 1_000)
    }

    private fun closed() {
      if (sampled) return
      sampled = true
      ended = true // a screen that closed itself before its content: its content is not this cycle's end
      if (shown.get()?.isShowing == true) Log.i(LOG_TAG, "memory-cycle $n: its screen is still up; this cycle's closed includes it")
      main.postDelayed({ memoryClosed(n) { if (running === this) running = null } }, 2_000)
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
      when {
        // The host's own lines: a guest mark of that name would read as one of them.
        name == "memory" || name == "frames" -> Log.w(LOG_TAG, "ignored a guest mark: '$name' is the host's")
        // KeliverTiming.mark: a bare mark here is this one, which would schedule itself every frame.
        guestName.matches(name) -> afterFrame {
          KeliverTiming.mark(name)
          if (name == "content") contentShown()
        }
        else -> Log.w(LOG_TAG, "ignored a guest mark: names are a-z, 0-9 and '-', at most 32")
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
