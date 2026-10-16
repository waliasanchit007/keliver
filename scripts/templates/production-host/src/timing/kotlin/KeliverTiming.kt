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
 */
package @@PACKAGE@@

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineService
import dev.keliver.protocol.Change
import dev.keliver.treehouse.ZiplineTreehouseUi
import java.io.File
import java.util.concurrent.Executors
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
  private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "keliver-timing").apply { isDaemon = true } }

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

  /** Binds KeliverTrace for the guest's marks. */
  fun bindServices(zipline: Zipline) {
    zipline.bind<KeliverTrace>("KeliverTrace", GuestTrace)
  }

  private fun markAt(name: String, nanos: Long) {
    Log.i(LOG_TAG, "$name $nanos")
    val f = file ?: return
    io.execute { runCatching { f.appendText("$name $nanos\n") } }
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
    override fun start(host: ZiplineTreehouseUi.Host) = ui.start(FirstFrameHost(host))
  }

  private class FirstFrameHost(private val host: ZiplineTreehouseUi.Host) : ZiplineTreehouseUi.Host by host {
    private val marked = AtomicBoolean(false)

    override fun sendChanges(changes: List<Change>) {
      // The host applies them on the main thread, after this returns; markAfterFrame queues behind that.
      host.sendChanges(changes)
      if (changes.isNotEmpty() && marked.compareAndSet(false, true)) markAfterFrame("first-frame")
    }
  }
}
