/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package dev.keliver.sample.host.threeway

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.lifecycleScope
import app.cash.zipline.Call
import app.cash.zipline.CallResult
import app.cash.zipline.EngineApi
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ZiplineHttpClient
import coil3.ImageLoader
import dev.keliver.compose.gatea.composeui.LocalHostBackDispatcher
import dev.keliver.compose.gatea.composeui.keliverComposeGateAWidgetSystem
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.sample.threeway.ThreewayAppService
import dev.keliver.sample.threeway.ThreewayGuestStats
import dev.keliver.sample.threeway.data.ItemsPage
import dev.keliver.sample.threeway.data.ThreewayApi
import dev.keliver.sample.threeway.data.ThreewayMarks
import dev.keliver.sample.threeway.data.parseItems
import dev.keliver.sample.threeway.nativecopy.ThreewayApp as NativeThreewayApp
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * The three-way benchmark's route (prototype builds only; docs/THREE_WAY_BENCHMARK.md): the same
 * list and detail screens, the same API, in one of three forms chosen by the `form` extra:
 *
 * - `native`: ThreewayScreens.kt compiled into this app (imports rewritten), OkHttp on an IO thread,
 *   kotlinx.serialization on a Default thread;
 * - `guest`: the same file in the guest (QuickJS), its code from the APK's assets and Zipline's
 *   cache, fetching through the host's `HostHttp` (this app's OkHttp client) and parsing in QuickJS;
 * - `web`: index.html from the APK's assets in a WebView, fetch() and JSON.parse.
 *
 * Timing: every form reports when its content for a request or a navigation is in its tree (native:
 * composition applied; guest: the change batch after its composition, handed to the host; web: the
 * page painted after the DOM change); the route then takes the next draw of this window as "drawn".
 * The `bench` extra (`nav`, `api-small`, `api-large`, comma-separated) runs a series of synthetic
 * taps after the first content and logs one JSON line per iteration (tag `Threeway`, and
 * `threeway-results.txt` in the app's external files). The first content also reports fully drawn,
 * for the startup benchmark.
 */
class ThreewayActivity : ComponentActivity() {
  private val main = Handler(Looper.getMainLooper())
  private lateinit var form: String

  /**
   * Benchmark experiments (the `x` extra, comma-separated; empty = as shipped). Guest: see
   * ThreewayHost.experiments. Web: `webcv` (the rows get `content-visibility: auto`, so the page
   * lays out and paints only rows near the viewport). docs/THREE_WAY_BENCHMARK.md, "Experiments".
   */
  private var experiments = ""
  private val experimentSet: Set<String> get() = experiments.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

  /** The process's (ThreewayGuest's): a guest prestarted before this activity reports into it too. */
  private val recorder = ThreewayGuest.recorder

  /** The guest form's app (for reading QuickJS's memory on its thread); null in the other forms. */
  private var guestApp: TreehouseApp<ThreewayAppService>? = null

  /**
   * Guest only: QuickJS's memory is read before each tap and after each drawn result, and at the
   * end of each series (`mem`, or any GC threshold experiment). The reads run on the Zipline thread
   * outside the measured intervals.
   */
  private val memoryReads: Boolean get() = form == "guest" && ("mem" in experimentSet || gcThresholdBytes() != null)

  /**
   * The GC threshold experiment, set when Zipline is created: `gc<MiB>` (`gc4`, `gc16`, `gc64`) or
   * `gcoff` (threshold -1, QuickJS's "never collect on allocation"; its reference counting still
   * frees acyclic objects). Bytes; null = QuickJS's default (run that arm as `x=mem`, so it pays the
   * same memory reads as the others).
   */
  private fun gcThresholdBytes(): Long? = ThreewayListener.gcThresholdBytes(experiments)

  private var scroll: ScrollTracker? = null

  /**
   * The scrolling series, Compose forms: a nested-scroll connection around the content, so the list
   * reports every scroll it consumed (drag and fling alike) and what it left (nonzero at an end), and
   * the velocity its fling starts with. Consumes nothing. Added only in the scrolling series, so the
   * other series' trees are unchanged.
   */
  private val scrollProbe = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
      scroll?.onScrolled(consumed.y, available.y)
      return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
      scroll?.onFlingStart(available.y)
      return Velocity.Zero
    }
  }

  private val scrollSeries: Boolean get() = intent.getStringExtra("bench").orEmpty().contains("scroll")

  private fun Modifier.scrollProbe(): Modifier = if (scrollSeries) nestedScroll(scrollProbe) else this

  /** Each step's results in the running series (for its summary), and the scrolling series' flings. */
  private val stepResults = LinkedHashMap<String, MutableList<JSONObject>>()
  private val flings = ArrayList<JSONObject>()
  private var webView: WebView? = null

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    scroll?.onTouch(ev)
    return super.dispatchTouchEvent(ev)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    ColdStart.mark("activityCreate")
    super.onCreate(savedInstanceState)
    form = intent.getStringExtra("form") ?: "native"
    experiments = intent.getStringExtra("x").orEmpty()
    Log.i(TAG, "form=$form x=$experiments")
    // NF4 `prestart`: what the next process's ThreewayPrestartProvider acts on (ThreewayPrestart.kt).
    ThreewayGuest.remember(this, form, experiments, intent.getBooleanExtra("localApi", false))
    // `x=trace` (NF0's system trace): trace sections for the guest's calls on the Zipline thread (set
    // with the GC threshold experiment here) and for every main-thread message, so a Perfetto trace
    // shows when the guest's changes are applied.
    ThreewayListener.applyExperiments(experiments)
    if ("trace" in experimentSet) {
      Looper.getMainLooper().setMessageLogging { line ->
        if (line.startsWith(">>>>> ")) {
          // ">>>>> Dispatching to Handler (h) {id} callback: what" -> "msg callback"
          val callback = line.substringAfter("} ", "").substringBefore('@').substringBefore(": ")
          Trace.beginSection("msg " + callback.takeLast(100))
        } else if (line.startsWith("<<<<< ")) {
          Trace.endSection()
        }
      }
    }
    // The fixture API served by this app instead of the Mac (LocalFixture), before any form starts.
    if (intent.getBooleanExtra("localApi", false)) LocalFixture.start()
    // WARNING: benchmark-only (as GatebActivity): keep the screen on and draw over a locked phone's
    // lock screen (it does not unlock it), only when the caller asks with `--ez overLockScreen true`.
    if (intent.getBooleanExtra("overLockScreen", false)) {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
      }
    }
    when (form) {
      "web" -> startWeb()
      "guest" -> startGuest()
      else -> startNative()
    }
    ColdStart.mark("contentSet")
    // The activity's first frame (Macrobenchmark's time to initial display ends at it).
    val decor = window.decorView
    decor.viewTreeObserver.addOnDrawListener(
      object : ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
          if (ColdStart.mark("firstDraw")) main.post { decor.viewTreeObserver.removeOnDrawListener(this) }
        }
      },
    )
  }

  // ---------------------------------------------------------------------------------------------
  // The three forms.

  private fun startNative() {
    val marks = object : ThreewayMarks {
      override val frameMarks = "split" in experimentSet
      override val heavyDetail = "heavy" in experimentSet
      override fun now() = System.currentTimeMillis()
      override fun mark(seq: Int, name: String, atMillis: Long) = recorder.mark(seq, name, atMillis)
      override fun composed(screen: String, seq: Int) {
        // On the main thread, in the frame's composition: this frame draws it.
        recorder.mark(seq, "composed", now())
        onContentInTree(screen, seq)
      }
    }
    val api = object : ThreewayApi {
      override suspend fun items(size: String, seq: Int): ItemsPage {
        val body = withContext(Dispatchers.IO) {
          val url = API_BASE.toHttpUrl().newBuilder().addPathSegment("items")
            .addQueryParameter("size", size).addQueryParameter("seq", seq.toString()).build()
          okHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.code == 200) { "HTTP ${response.code}" }
            response.body!!.string()
          }
        }
        marks.mark(seq, "response")
        val page = withContext(Dispatchers.Default) { parseItems(body) }
        marks.mark(seq, "parsed")
        return page
      }
    }
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize().scrollProbe()) { NativeThreewayApp(api, marks) }
      }
    }
  }

  private fun startGuest() {
    // The guest's host services are the process's (ThreewayGuest), so that a guest prestarted
    // before this activity (NF4 `prestart`) reports here too; set before any content binds.
    ThreewayGuest.experiments = experiments
    val awaiting = AtomicReference<Pair<String, Int>?>(null)
    ThreewayGuest.onComposed = { screen, seq, marksJson ->
      recorder.addJson(marksJson)
      awaiting.set(screen to seq)
    }
    // The guest calls `composed` in its apply phase, then sends that frame's change batch: when the
    // host's sendChanges returns, the batch is queued on the main thread, and a message posted then
    // runs after it.
    ThreewayListener.onSendChangesEnd = {
      val done = awaiting.getAndSet(null)
      if (done != null) {
        recorder.mark(done.second, "batchSent", System.currentTimeMillis())
        main.post {
          // Hand the applied changes to the host's composition now, so the next frame recomposes.
          Snapshot.sendApplyNotifications()
          onContentInTree(done.first, done.second)
        }
      }
    }
    // NF4 `prefetch`: the list's first request goes out now unless the prestart already sent it.
    if ("prefetch" in experimentSet) ThreewayGuest.prefetch.start()
    // NF4 `prestart`: the code load this process started in bindApplication, if it did; otherwise
    // created here and started by the first content, as before.
    val app = ThreewayGuest.takePrestarted(experiments) ?: run {
      // The same main-thread work as the prestart's (`n_prestartMainMs`), here in onCreate.
      val began = SystemClock.elapsedRealtimeNanos()
      ThreewayGuest.create(applicationContext, lifecycleScope)
        .also { ColdStart.mainThreadNanos("guestCreateMain", SystemClock.elapsedRealtimeNanos() - began) }
    }
    guestApp = app
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize().scrollProbe()) {
          val widgetSystem = remember {
            keliverComposeGateAWidgetSystem(ComposeUiKeliverMaterialWidgetSystem(ImageLoader.Builder(applicationContext).build()))
          }
          val contentSource = remember {
            object : TreehouseContentSource<ThreewayAppService> {
              override fun get(app: ThreewayAppService) = app.launch()
            }
          }
          // NF1 `retainhost`: the guest's retained container goes back on the host, through the
          // activity's dispatcher (where the guest's BackHandlers are too). Unused by other forms.
          val hostBack = remember { AndroidHostBackDispatcher(onBackPressedDispatcher) }
          CompositionLocalProvider(LocalHostBackDispatcher provides hostBack) {
            TreehouseContent(treehouseApp = app, widgetSystem = widgetSystem, contentSource = contentSource, modifier = Modifier.fillMaxSize())
          }
        }
      }
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun startWeb() {
    val webView = WebView(this)
    webView.settings.javaScriptEnabled = true
    webView.addJavascriptInterface(
      object {
        @JavascriptInterface
        fun composed(screen: String, seq: Int, marksJson: String, paintedJson: String) {
          recorder.addJson(marksJson)
          recorder.addJson(paintedJson)
          main.post { onContentInTree(screen, seq) }
        }

        /** The run's experiments (the `x` extra); the page reads it once, before its first render. */
        @JavascriptInterface
        fun experiments(): String = experiments

        @JavascriptInterface
        fun error(message: String) {
          Log.e(TAG, "web: $message")
        }
      },
      "Threeway",
    )
    // Scrolling series: no scrollbar (the Compose lists have none; its fade-out would add frames).
    // The probe as the Compose forms' nestedScroll: the page's scroll in px per change (the WebView
    // scrolls the document itself; no leftover, the ends come from canScrollVertically).
    if (scrollSeries) {
      webView.isVerticalScrollBarEnabled = false
      webView.setOnScrollChangeListener { _, _, y, _, oldY -> scroll?.onScrolled((y - oldY).toFloat(), 0f) }
    }
    this.webView = webView
    setContentView(webView)
    webView.loadUrl("file:///android_asset/threeway-web/index.html")
  }

  // ---------------------------------------------------------------------------------------------
  // "Drawn": the first draw of this window after a form's content is in its tree.

  private var firstContent = true
  private var waiter: ((String, Int) -> Unit)? = null

  /** Main thread. */
  private fun onContentInTree(screen: String, seq: Int) {
    recorder.mark(seq, "inTree", System.currentTimeMillis())
    val decor = window.decorView
    val listener = object : ViewTreeObserver.OnDrawListener {
      var fired = false
      override fun onDraw() {
        if (fired) return
        fired = true
        recorder.mark(seq, "drawn", System.currentTimeMillis())
        // A draw listener cannot remove itself during onDraw.
        main.post { decor.viewTreeObserver.removeOnDrawListener(this) }
        if (firstContent && screen == "data") {
          firstContent = false
          // Inside this frame's draw: the startup benchmark's timeToFullDisplay ends at this frame.
          reportFullyDrawn()
          // Once per process: a recreated activity (rotation) must not log a second "cold" line.
          if (ColdStart.mark("dataDrawn")) log(ColdStart.line(form, recorder.peek(seq)))
          decor.postInvalidate()
          main.postDelayed(::runBench, SETTLE_MILLIS)
        }
        val w = waiter
        if (w != null) main.post { w(screen, seq) }
      }
    }
    decor.viewTreeObserver.addOnDrawListener(listener)
    decor.invalidate()
  }

  // ---------------------------------------------------------------------------------------------
  // The benchmark driver: synthetic taps at the screens' fixed positions.

  private fun runBench() {
    val benches = intent.getStringExtra("bench")?.split(',')?.filter { it.isNotBlank() } ?: return
    val n = intent.getIntExtra("n", 12)
    val steps = ArrayDeque<() -> Unit>()
    var lastData = 1
    fun next() = steps.removeFirstOrNull()?.invoke() ?: log("""{"form":"$form","done":true}""")

    fun iteration(
      bench: String,
      i: Int,
      xDp: Float,
      yDp: Float,
      accept: (String, Int) -> Boolean,
      pause: Long,
      press: (() -> Long)? = null,
    ) {
      fun run(before: LongArray?) {
        val timeout = Runnable {
          waiter = null
          log("""{"form":"$form","bench":"$bench","i":$i,"timeout":true}""")
          main.postDelayed({ next() }, pause)
        }
        val tap = press?.invoke() ?: tap(xDp, yDp)
        // A system back (`sysback`): also the press's first draw, which shows the list when the host
        // goes back on its own (`retainhost`) and is the guest's frame otherwise.
        fun pressExtra(): Map<String, Long> =
          if (press != null && firstDrawAfterPress > 0) mapOf("firstDraw" to firstDrawAfterPress - tap) else emptyMap()
        waiter = { screen, seq ->
          if (accept(screen, seq)) {
            waiter = null
            main.removeCallbacks(timeout)
            if (screen == "data") lastData = seq
            if (before == null) {
              log(result(bench, i, tap, seq, extra = pressExtra()))
              main.postDelayed({ next() }, pause)
            } else {
              val marks = recorder.take(seq)
              readQuickJsMemory { after ->
                log(result(bench, i, tap, seq, marks, memory(before, after) + pressExtra()))
                main.postDelayed({ next() }, pause)
              }
            }
          }
        }
        main.postDelayed(timeout, 20_000)
      }
      steps.addLast {
        // The read walks QuickJS's whole heap on the Zipline thread: the tap waits MEMORY_PAUSE_MILLIS
        // after it, so the walk is not in the measured interval nor queued ahead of the guest's work.
        if (memoryReads) {
          readQuickJsMemory { m -> main.postDelayed({ run(m ?: LongArray(3) { -1 }) }, MEMORY_PAUSE_MILLIS) }
        } else {
          run(null)
        }
      }
    }

    fun endOfSeries(bench: String) {
      // The series' summary per step (the first `warmup` iterations dropped).
      val labels = when (bench) {
        "nav", "sysback" -> listOf("open", "back")
        "scroll" -> emptyList()
        else -> listOf(bench)
      }
      steps.addLast {
        val warmup = intent.getIntExtra("warmup", 0)
        for (label in labels) {
          val all = stepResults[label].orEmpty()
          // The navigation series' opens after the warm-up are repeat visits in a warm process (host
          // code compiled, text caches warm): "open (warm)". Its first open is the screen's first
          // composition in this process, logged apart as "open (cold, i=1)" when the series is the
          // process's first (launch with `am start -S` per series).
          val name = if (label == "open") "open (warm)" else label
          if (label == "open" && warmup > 0 && all.isNotEmpty() && bench == benches.first()) {
            log(summarizeSteps("open (cold, i=1)", all.take(1)).put("form", form).put("x", experiments).put("api", apiRoute).toString())
          }
          log(summarizeSteps(name, all.drop(warmup)).put("form", form).put("x", experiments).put("warmup", warmup).put("api", apiRoute).toString())
          stepResults.remove(label)
        }
        next()
      }
      if (!memoryReads) return
      steps.addLast {
        readQuickJsMemory { m ->
          log("""{"form":"$form","bench":"$bench","x":${JSONObject.quote(experiments)},"api":"$apiRoute","memEnd":true,"qjsUsed":${m?.get(0)},"qjsObjects":${m?.get(1)},"gcThreshold":${m?.get(2)}}""")
          next()
        }
      }
    }

    for (bench in benches) {
      steps.addLast {
        log("""{"form":"$form","bench":"$bench","x":${JSONObject.quote(experiments)},"state":${JSONObject.quote(phoneState())}}""")
        next()
      }
      when (bench) {
        "nav" -> for (i in 1..n) {
          iteration("open", i, widthDp() / 2, ROWS_TOP_DP + ROW_DP * ((i - 1) % 5) + ROW_DP / 2, { s, _ -> s == "detail" }, 700)
          iteration("back", i, 36f, 28f, { s, _ -> s == "list" }, 700)
        }
        // Back by a system back press instead of the header's button (guest `retain` or `retainhost`
        // only: as shipped, the guest has no BackHandler and the press would finish the activity).
        "sysback" -> for (i in 1..n) {
          iteration("open", i, widthDp() / 2, ROWS_TOP_DP + ROW_DP * ((i - 1) % 5) + ROW_DP / 2, { s, _ -> s == "detail" }, 700)
          iteration("back", i, 0f, 0f, { s, _ -> s == "list" }, 700, press = ::systemBack)
        }
        "api-small", "api-large" -> for (i in 1..n) {
          val x = if (bench == "api-small") widthDp() / 4 else widthDp() * 3 / 4
          iteration(bench, i, x, 80f, { s, seq -> s == "data" && seq > lastData }, if (bench == "api-large") 1_500 else 800)
        }
        "scroll" -> {
          // Load the 1,000 items, then wait for flings from outside (adb shell input swipe).
          iteration("load-large", 1, widthDp() * 3 / 4, 80f, { s, seq -> s == "data" && seq > lastData }, 1_500)
          steps.addLast { startScroll() }
        }
        else -> log("""{"form":"$form","error":"unknown bench $bench"}""")
      }
      endOfSeries(bench)
    }
    next()
  }

  private var firstDrawAfterPress = 0L

  /** A system back press, as the platform delivers it, and the first draw of the window after it. */
  private fun systemBack(): Long {
    val decor = window.decorView
    firstDrawAfterPress = 0L
    val listener = object : ViewTreeObserver.OnDrawListener {
      var fired = false
      override fun onDraw() {
        if (fired) return
        fired = true
        firstDrawAfterPress = System.currentTimeMillis()
        main.post { decor.viewTreeObserver.removeOnDrawListener(this) }
      }
    }
    decor.viewTreeObserver.addOnDrawListener(listener)
    val at = System.currentTimeMillis()
    onBackPressedDispatcher.onBackPressed()
    return at
  }

  private fun startScroll() {
    if (Build.VERSION.SDK_INT < 26) return log("""{"form":"$form","error":"scroll needs API 26"}""")
    val web = webView
    val tracker = ScrollTracker(
      window = window,
      density = resources.displayMetrics.density,
      vsyncPeriodNs = { Math.round(1e9 / refreshHz()) },
      guest = form == "guest",
      blank = "blank" in experimentSet,
      webScrollY = web?.let { w -> { w.scrollY } },
      webAtEnd = web?.let { w -> { direction -> !w.canScrollVertically(direction) } },
      onFrameDetail = { detail -> logFileOnly(detail.put("form", form).put("x", experiments).toString()) },
    ) { line ->
      line.put("form", form).put("bench", "scroll").put("api", apiRoute)
      if (experiments.isNotEmpty()) line.put("x", experiments)
      val app = guestApp
      val zipline = ThreewayListener.zipline
      if (app == null || zipline == null) {
        flingDone(line)
      } else {
        // The guest's counters since the last fling, read after this one settled.
        lifecycleScope.launch {
          val stats = runCatching { takeGuestStats(app, zipline) }.getOrElse { """{"error":${JSONObject.quote(it.toString())}}""" }
          flingDone(line.put("guest", JSONObject(stats)))
        }
      }
    }
    scroll = tracker
    tracker.start()
    // Zero the guest's counters (the load's composition), then accept flings.
    val app = guestApp
    val zipline = ThreewayListener.zipline
    lifecycleScope.launch {
      if (app != null && zipline != null) {
        runCatching { takeGuestStats(app, zipline) }
      }
      tracker.ready = true
      log("""{"form":"$form","scroll":"ready","x":${JSONObject.quote(experiments)},"state":${JSONObject.quote(phoneState())}}""")
    }
  }

  /** Logs a settled fling; after every `group` flings (default 10: one direction), their summary. */
  private fun flingDone(line: JSONObject) {
    log(line.toString())
    flings += line
    val group = intent.getIntExtra("group", 10)
    if (flings.size % group == 0) {
      val last = flings.takeLast(group)
      val label = "flings ${flings.size - group + 1}-${flings.size}"
      // A summary that fails must not end the run (the fling lines above are the raw data).
      val summary = runCatching { summarizeFlings(label, last).put("form", form).put("x", experiments).put("api", apiRoute).toString() }
      log(summary.getOrElse { """{"summary":${JSONObject.quote(label)},"form":"$form","error":${JSONObject.quote(it.toString())}}""" })
    }
  }

  /** The guest's protocol counters since the last read (ThreewayGuestStats), on the Zipline thread. */
  private suspend fun takeGuestStats(app: TreehouseApp<*>, zipline: Zipline): String = withContext(app.dispatchers.zipline) {
    // Taken once: closing a taken service closes the guest's binding.
    val service = guestStats ?: zipline.take<ThreewayGuestStats>("threewayStats").also { guestStats = it }
    service.takeStats()
  }

  /** The guest's stats service, taken on the Zipline thread and kept for the process's life. */
  private var guestStats: ThreewayGuestStats? = null

  /** QuickJS's heap in use (bytes), its object count and its current GC threshold (bytes), read on the Zipline thread; null without a guest. */
  @OptIn(EngineApi::class)
  private fun readQuickJsMemory(then: (LongArray?) -> Unit) {
    val app = guestApp
    val zipline = ThreewayListener.zipline
    if (app == null || zipline == null) return then(null)
    lifecycleScope.launch {
      val m = runCatching {
        withContext(app.dispatchers.zipline) {
          val quickJs = zipline.quickJs
          val usage = quickJs.memoryUsage
          longArrayOf(usage.memoryUsedSize, usage.objectsCount, quickJs.gcThreshold)
        }
      }.getOrNull()
      then(m)
    }
  }

  private fun memory(before: LongArray, after: LongArray?): Map<String, Long> = linkedMapOf(
    "n_qjsUsedBefore" to before[0],
    "n_qjsUsedAfter" to (after?.get(0) ?: -1),
    "n_qjsObjectsBefore" to before[1],
    "n_qjsObjectsAfter" to (after?.get(1) ?: -1),
    "n_gcThresholdBefore" to before[2],
    "n_gcThresholdAfter" to (after?.get(2) ?: -1),
  )

  private fun widthDp(): Float {
    val content = findViewById<View>(android.R.id.content)
    return content.width / resources.displayMetrics.density
  }

  /** Taps at ([xDp], [yDp]) of the content view; returns the time the UP was dispatched (epoch ms). */
  private fun tap(xDp: Float, yDp: Float): Long {
    val content = findViewById<View>(android.R.id.content)
    val location = IntArray(2)
    content.getLocationInWindow(location)
    val density = resources.displayMetrics.density
    val x = location[0] + xDp * density
    val y = location[1] + yDp * density
    val downTime = SystemClock.uptimeMillis()
    dispatch(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
    SystemClock.sleep(TAP_HOLD_MILLIS)
    val upAt = System.currentTimeMillis()
    dispatch(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
    return upAt
  }

  private fun dispatch(event: MotionEvent) {
    event.source = InputDevice.SOURCE_TOUCHSCREEN
    window.decorView.dispatchTouchEvent(event)
    event.recycle()
  }

  private fun result(
    bench: String,
    i: Int,
    tap: Long,
    seq: Int,
    marks: Map<String, Long> = recorder.take(seq),
    extra: Map<String, Long> = emptyMap(),
  ): String {
    val o = JSONObject().put("form", form).put("bench", bench).put("i", i).put("seq", seq)
    if (experiments.isNotEmpty()) o.put("x", experiments)
    // `n_*` are the guest's counters and summed durations (split experiment), not times; [extra]
    // holds values too (QuickJS's memory around the step).
    val (values, times) = marks.entries.partition { it.key.startsWith("n_") }
    for ((name, at) in times.sortedBy { it.value }) o.put(name, at - tap)
    for ((name, value) in values) o.put(name, value)
    for ((name, value) in extra) o.put(name, value)
    stepResults.getOrPut(bench) { mutableListOf() } += o
    return o.toString()
  }

  private fun log(line: String) {
    Log.i(TAG, line)
    logFileOnly(line)
  }

  /** The results file only (lines too long for logcat: the scrolling series' per-frame detail). */
  private fun logFileOnly(line: String) {
    runCatching { File(getExternalFilesDir(null), "threeway-results.txt").appendText("${System.currentTimeMillis()} $line\n") }
  }

  /** Which fixture API this process uses: `app` (LocalFixture, `--ez localApi true`) or `mac` (through adb reverse). */
  private val apiRoute: String get() = if (intent.getBooleanExtra("localApi", false)) "app" else "mac"

  @Suppress("DEPRECATION")
  private fun refreshHz(): Float = (if (Build.VERSION.SDK_INT >= 30) display else null)?.refreshRate ?: windowManager.defaultDisplay.refreshRate

  private fun phoneState(): String {
    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: -1
    val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
    val metrics = resources.displayMetrics
    return "battery=$level% plugged=$plugged temperature=${temperature}C powerSave=${power.isPowerSaveMode} thermal=$thermal" +
      " refreshHz=${refreshHz()} density=${metrics.density} api=$apiRoute sdk=${Build.VERSION.SDK_INT}"
  }

  private companion object {
    const val TAG = "Threeway"

    /** The fixture API (docs/THREE_WAY_BENCHMARK.md), on the Mac, through `adb reverse`. */
    const val API_BASE = "http://localhost:8491"

    // The screens' fixed geometry (ThreewayScreens.kt, index.html).
    const val ROWS_TOP_DP = 56f + 48f + 24f
    const val ROW_DP = 72f
    const val TAP_HOLD_MILLIS = 40L
    const val SETTLE_MILLIS = 2_000L

    /** Between a QuickJS memory read and the tap it precedes (memory reads only). */
    const val MEMORY_PAUSE_MILLIS = 300L

    val okHttp: OkHttpClient get() = ThreewayHttp.okHttp
  }
}

internal object ThreewayHttp {
  /** The app's one HTTP client, used by the native form directly and by the guest's HostHttp. */
  val okHttp: OkHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
  }
}

/** Marks per request or navigation, epoch ms; filled from the host, the guest and the page. */
internal class Recorder {
  private val marks = ConcurrentHashMap<Int, ConcurrentHashMap<String, Long>>()

  fun mark(seq: Int, name: String, atMillis: Long) {
    marks.getOrPut(seq) { ConcurrentHashMap() }[name] = atMillis
  }

  /** Adds `{"seq":{"name":ms}}` from the guest or the page. */
  fun addJson(json: String) {
    val o = JSONObject(json)
    for (seq in o.keys()) {
      val inner = o.getJSONObject(seq)
      for (name in inner.keys()) mark(seq.toInt(), name, inner.getLong(name))
    }
  }

  fun take(seq: Int): Map<String, Long> = marks.remove(seq) ?: emptyMap()

  fun peek(seq: Int): Map<String, Long> = marks[seq]?.toMap() ?: emptyMap()
}

/**
 * NF0's cold-start split (docs/THREE_WAY_BENCHMARK.md, "Cold start (NF0)"): when each point of the
 * process's start was first reached (epoch ms), logged once per process as a `"cold"` line when the
 * list's data is first drawn, in ms from the app's start (`Process.getStartUptimeMillis`, set at
 * bindApplication on Android 12, about 50 ms after the fork). Always on (a few map writes per process). Guest
 * only: the code load's steps (from the EventListener) and its module loads, summed.
 */
internal object ColdStart {
  private val marks = ConcurrentHashMap<String, Long>()

  /** Module loads (QuickJS loading each module's bytecode): count, summed ms and the slowest. */
  @Volatile var modules = 0
  @Volatile var moduleLoadNanos = 0L
  @Volatile var slowestModule = ""
  @Volatile var slowestModuleNanos = 0L

  /** Main-thread work measured once per process (NF4 `prestart`), logged as `n_<name>Ms` (fractional). */
  private val mainThread = ConcurrentHashMap<String, Long>()

  /** Records [name] once; returns true the first time. */
  fun mark(name: String): Boolean = marks.putIfAbsent(name, System.currentTimeMillis()) == null

  fun mainThreadNanos(name: String, nanos: Long) {
    mainThread.putIfAbsent(name, nanos)
  }

  /** Records [name], replacing an earlier value (the last occurrence). */
  fun markLast(name: String) {
    marks[name] = System.currentTimeMillis()
  }

  fun line(form: String, requestMarks: Map<String, Long>): String {
    // The process start on the epoch clock: uptime and epoch advance together while the phone is awake.
    val processStart = System.currentTimeMillis() - (SystemClock.uptimeMillis() - Process.getStartUptimeMillis())
    val o = JSONObject().put("cold", form)
    val all = HashMap(marks)
    for ((name, at) in requestMarks) all.putIfAbsent(name, at)
    for ((name, at) in all.entries.sortedBy { it.value }) o.put(name, at - processStart)
    if (modules > 0) {
      o.put("n_modules", modules).put("n_moduleLoadMs", moduleLoadNanos / 1_000_000)
        .put("slowestModule", slowestModule).put("n_slowestModuleMs", slowestModuleNanos / 1_000_000)
        .put("n_modulesFromAssets", ThreewayListener.modulesFromAssets)
    }
    for ((name, nanos) in mainThread) o.put("n_${name}Ms", nanos / 100_000 / 10.0)
    return o.toString()
  }
}

/**
 * The guest's HTTP: the app's OkHttp client behind the `HostHttp` service (a minimal version of
 * the production-host template's OkHttpHostHttp: same base, same thread hop; no request policy).
 * Marks, on the host's clock: `hostIn` (the call arrived from the guest), `hostNet` (the body has
 * been read) and `hostOut` (returning to the guest).
 */
internal class BenchHostHttp(
  private val client: OkHttpClient,
  private val recorder: Recorder,
  private val prefetch: FirstRequestPrefetch? = null,
) : HostHttpProvider {
  override suspend fun execute(request: HttpRequest): HttpResponse {
    val seq = request.query["seq"]?.toIntOrNull() ?: -1
    recorder.mark(seq, "hostIn", System.currentTimeMillis())
    // NF4 `prefetch`: the host already sent this request; a failed or non-200 one is not used.
    val prefetched = prefetch?.take(request)?.let { runCatching { it.await() }.getOrNull() }?.takeIf { it.status == 200 }
    if (prefetched != null) recorder.mark(seq, "prefetched", System.currentTimeMillis())
    val response = prefetched ?: withContext(Dispatchers.IO) {
      send(client, request).also { recorder.mark(seq, "hostNet", System.currentTimeMillis()) }
    }
    return response.also { recorder.mark(seq, "hostOut", System.currentTimeMillis()) }
  }

  companion object {
    /** Blocking: the call and its body. */
    fun send(client: OkHttpClient, request: HttpRequest): HttpResponse {
      val url = "http://localhost:8491".toHttpUrl().newBuilder().apply {
        request.path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        request.query.forEach { (k, v) -> addQueryParameter(k, v) }
      }.build()
      return client.newCall(Request.Builder().url(url).method(request.method, null).build()).execute().use { response ->
        HttpResponse(status = response.code, body = response.body?.string().orEmpty(), headers = emptyMap())
      }
    }
  }
}

/** Serves the bundle from the APK's `assets/threeway/` by file name (no network for code). */
internal class AssetsHttpClient(private val assets: AssetManager) : ZiplineHttpClient() {
  override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString = withContext(Dispatchers.IO) {
    val name = url.substringAfterLast('/')
    assets.open("threeway/$name").use { it.readBytes().toByteString() }
  }
}

/** The guest's code load and bridge: reports `sendChanges` returning, and logs failures. */
internal object ThreewayListener : EventListener.Factory {
  @Volatile var onSendChangesEnd: (() -> Unit)? = null

  /** Whether this process's code load read the modules from assets (an empty cache) or the cache. */
  @Volatile var modulesFromAssets = 0

  /** The `gc<MiB>` experiment: QuickJS's GC threshold, set when the Zipline is created; null = QuickJS's default. */
  @Volatile var gcThresholdBytes: Long? = null

  /** The `gc<MiB>`/`gcoff` experiment in [experiments] (comma-separated), in bytes; null = none. */
  fun gcThresholdBytes(experiments: String): Long? = experiments.split(',').map { it.trim() }.firstNotNullOfOrNull {
    if (it == "gcoff") -1L else Regex("gc(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toLong()?.let { mib -> mib * 1024 * 1024 }
  }

  /** What the listener does for the run's [experiments]; before Zipline starts (the activity, or the prestart). */
  fun applyExperiments(experiments: String) {
    gcThresholdBytes = gcThresholdBytes(experiments)
    traceCalls = "trace" in experiments.split(',').map { it.trim() }
  }

  /** The guest's Zipline (for memory reads and the scrolling series' counters), on its thread only. */
  @Volatile var zipline: Zipline? = null

  /**
   * The scrolling series: the guest's frames while [recordGuestFrames], each `[start ns, duration
   * ns, 1 if the frame sent a change batch]`. `AppLifecycle.sendFrame` is synchronous: the guest
   * composes and sends its batch inside it.
   */
  @Volatile var recordGuestFrames = false

  /**
   * `x=trace`: a trace section around every non-suspending call into or out of the guest (set before
   * Zipline starts). Suspending calls are skipped: they can overlap or end on another thread, which
   * would mis-nest synchronous sections.
   */
  @Volatile var traceCalls = false
  val guestFrames = CopyOnWriteArrayList<LongArray>()
  private var frameChanges = false

  /** While recording: the start of the guest frame in progress (0 = none), and the end of the last one (System.nanoTime). */
  @Volatile var guestFrameStartNs = 0L
  @Volatile var lastGuestFrameEndNs = 0L

  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener = object : EventListener() {
    override fun codeLoadStart(): Any? {
      ColdStart.mark("codeLoadStart")
      return null
    }

    override fun manifestReady(manifest: ZiplineManifest) {
      ColdStart.mark("manifestReady")
    }

    override fun moduleLoadStart(moduleId: String): Any? {
      ColdStart.mark("firstModuleLoadStart")
      return System.nanoTime()
    }

    override fun moduleLoadEnd(moduleId: String, startValue: Any?) {
      ColdStart.markLast("lastModuleLoadEnd")
      val took = System.nanoTime() - (startValue as? Long ?: return)
      ColdStart.modules++
      ColdStart.moduleLoadNanos += took
      if (took > ColdStart.slowestModuleNanos) {
        ColdStart.slowestModuleNanos = took
        ColdStart.slowestModule = moduleId
      }
    }

    override fun mainFunctionStart(applicationName: String): Any? {
      ColdStart.mark("mainStart")
      return null
    }

    override fun mainFunctionEnd(applicationName: String, startValue: Any?) {
      ColdStart.mark("mainEnd")
    }

    @OptIn(EngineApi::class)
    override fun ziplineCreated(zipline: Zipline) {
      ColdStart.mark("ziplineCreated")
      ThreewayListener.zipline = zipline
      val quickJs = zipline.quickJs
      val default = quickJs.gcThreshold
      gcThresholdBytes?.let { quickJs.gcThreshold = it }
      Log.i("Threeway", "ziplineCreated gcThreshold default=$default now=${quickJs.gcThreshold} memoryLimit=${quickJs.memoryLimit}")
    }

    override fun callStart(call: Call): Any? {
      if (traceCalls && !call.function.signature.startsWith("suspend ")) {
        val name = call.function.signature.substringAfter("fun ").substringBefore('(')
        val size = (call.args.firstOrNull() as? List<*>)?.size
        Trace.beginSection(if (size != null) "zl:$name n=$size" else "zl:$name")
      }
      if (!recordGuestFrames || !call.function.signature.startsWith("fun sendFrame(")) return null
      frameChanges = false
      return System.nanoTime().also { guestFrameStartNs = it }
    }

    override fun downloadStart(url: String): Any? {
      if (!url.endsWith("manifest.zipline.json")) modulesFromAssets++
      return null
    }

    override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) {
      ColdStart.mark("codeLoadSuccess")
      Log.i("Threeway", "codeLoadSuccess modules=${manifest.modules.size} modulesFromAssets=$modulesFromAssets")
    }

    override fun codeLoadFailed(exception: Exception, startValue: Any?) {
      Log.e("Threeway", "codeLoadFailed", exception)
    }

    override fun uncaughtException(exception: Throwable) {
      Log.e("Threeway", "uncaughtException", exception)
    }

    override fun callEnd(call: Call, result: CallResult, startValue: Any?) {
      if (traceCalls && !call.function.signature.startsWith("suspend ")) Trace.endSection()
      if ("sendChanges" in call.function.signature) {
        ColdStart.mark("firstBatchSent")
        frameChanges = true
        onSendChangesEnd?.invoke()
      }
      if (startValue is Long) {
        val end = System.nanoTime()
        guestFrames += longArrayOf(startValue, end - startValue, if (frameChanges) 1L else 0L)
        lastGuestFrameEndNs = end
        guestFrameStartNs = 0L
      }
    }
  }

  override fun close() {}
}
