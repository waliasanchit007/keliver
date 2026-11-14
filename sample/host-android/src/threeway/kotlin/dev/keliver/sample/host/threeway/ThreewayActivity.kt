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
import android.os.SystemClock
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.cash.zipline.Call
import app.cash.zipline.CallResult
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineHttpClient
import coil3.ImageLoader
import dev.keliver.compose.gatea.composeui.keliverComposeGateAWidgetSystem
import dev.keliver.compose.gatea.protocol.host.KeliverComposeGateAHostProtocol
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import dev.keliver.leaks.LeakDetector
import dev.keliver.material.composeui.ComposeUiKeliverMaterialWidgetSystem
import dev.keliver.sample.threeway.ThreewayAppService
import dev.keliver.sample.threeway.ThreewayHost
import dev.keliver.sample.threeway.data.ItemsPage
import dev.keliver.sample.threeway.data.ThreewayApi
import dev.keliver.sample.threeway.data.ThreewayMarks
import dev.keliver.sample.threeway.data.parseItems
import dev.keliver.sample.threeway.nativecopy.ThreewayApp as NativeThreewayApp
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.modules.EmptySerializersModule
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString
import okio.ByteString.Companion.decodeHex
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
  private val recorder = Recorder()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    form = intent.getStringExtra("form") ?: "native"
    experiments = intent.getStringExtra("x").orEmpty()
    Log.i(TAG, "form=$form x=$experiments")
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
  }

  // ---------------------------------------------------------------------------------------------
  // The three forms.

  private fun startNative() {
    val marks = object : ThreewayMarks {
      override val frameMarks = "split" in experiments.split(',')
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
        Surface(Modifier.fillMaxSize()) { NativeThreewayApp(api, marks) }
      }
    }
  }

  private fun startGuest() {
    val host = object : ThreewayHost {
      @Volatile var awaiting: Pair<String, Int>? = null

      override fun composed(screen: String, seq: Int, marksJson: String) {
        recorder.addJson(marksJson)
        awaiting = screen to seq
      }

      override fun experiments(): String = experiments
    }
    // The guest calls `composed` in its apply phase, then sends that frame's change batch: when the
    // host's sendChanges returns, the batch is queued on the main thread, and a message posted then
    // runs after it.
    ThreewayListener.onSendChangesEnd = {
      val done = host.awaiting
      if (done != null) {
        host.awaiting = null
        recorder.mark(done.second, "batchSent", System.currentTimeMillis())
        main.post {
          // Hand the applied changes to the host's composition now, so the next frame recomposes.
          Snapshot.sendApplyNotifications()
          onContentInTree(done.first, done.second)
        }
      }
    }
    val factory = TreehouseAppFactory(
      context = applicationContext,
      httpClient = AssetsHttpClient(assets),
      manifestVerifier = ManifestVerifier.Builder()
        .addEd25519(name = "keliver-sample-ed25519", trustedKey = SAMPLE_SIGNING_PUBLIC_KEY.decodeHex())
        .build(),
      cacheName = "keliver-threeway-zipline",
      stateStore = MemoryStateStore(),
      leakDetector = LeakDetector.none(),
      hostProtocolFactory = KeliverComposeGateAHostProtocol.Factory,
    )
    val spec = object : TreehouseApp.Spec<ThreewayAppService>() {
      override val name = "keliver-threeway"
      override val manifestUrl = MutableStateFlow(MANIFEST_URL)
      override val serializersModule = EmptySerializersModule()
      override suspend fun bindServices(treehouseApp: TreehouseApp<ThreewayAppService>, zipline: Zipline) {
        zipline.bind<HostHttpProvider>("HostHttp", BenchHostHttp(okHttp, recorder))
        zipline.bind<ThreewayHost>("threewayHost", host)
      }
      override fun create(zipline: Zipline): ThreewayAppService = zipline.take("app")
    }
    val app = factory.create(appScope = lifecycleScope, spec = spec, eventListenerFactory = ThreewayListener)
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          val widgetSystem = remember {
            keliverComposeGateAWidgetSystem(ComposeUiKeliverMaterialWidgetSystem(ImageLoader.Builder(applicationContext).build()))
          }
          val contentSource = remember {
            object : TreehouseContentSource<ThreewayAppService> {
              override fun get(app: ThreewayAppService) = app.launch()
            }
          }
          TreehouseContent(treehouseApp = app, widgetSystem = widgetSystem, contentSource = contentSource, modifier = Modifier.fillMaxSize())
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

    fun iteration(bench: String, i: Int, xDp: Float, yDp: Float, accept: (String, Int) -> Boolean, pause: Long) {
      steps.addLast {
        val timeout = Runnable {
          waiter = null
          log("""{"form":"$form","bench":"$bench","i":$i,"timeout":true}""")
          main.postDelayed({ next() }, pause)
        }
        val tap = tap(xDp, yDp)
        waiter = { screen, seq ->
          if (accept(screen, seq)) {
            waiter = null
            main.removeCallbacks(timeout)
            if (screen == "data") lastData = seq
            log(result(bench, i, tap, seq))
            main.postDelayed({ next() }, pause)
          }
        }
        main.postDelayed(timeout, 20_000)
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
        "api-small", "api-large" -> for (i in 1..n) {
          val x = if (bench == "api-small") widthDp() / 4 else widthDp() * 3 / 4
          iteration(bench, i, x, 80f, { s, seq -> s == "data" && seq > lastData }, if (bench == "api-large") 1_500 else 800)
        }
        else -> log("""{"form":"$form","error":"unknown bench $bench"}""")
      }
    }
    next()
  }

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

  private fun result(bench: String, i: Int, tap: Long, seq: Int): String {
    val marks = recorder.take(seq)
    val o = JSONObject().put("form", form).put("bench", bench).put("i", i).put("seq", seq)
    if (experiments.isNotEmpty()) o.put("x", experiments)
    // `n_*` are the guest's counters and summed durations (split experiment), not times.
    val (values, times) = marks.entries.partition { it.key.startsWith("n_") }
    for ((name, at) in times.sortedBy { it.value }) o.put(name, at - tap)
    for ((name, value) in values) o.put(name, value)
    return o.toString()
  }

  private fun log(line: String) {
    Log.i(TAG, line)
    runCatching { File(getExternalFilesDir(null), "threeway-results.txt").appendText("${System.currentTimeMillis()} $line\n") }
  }

  private fun phoneState(): String {
    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: -1
    val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
    return "battery=$level% plugged=$plugged temperature=${temperature}C powerSave=${power.isPowerSaveMode} thermal=$thermal"
  }

  private companion object {
    const val TAG = "Threeway"
    const val MANIFEST_URL = "https://threeway.invalid/manifest.zipline.json"
    const val SAMPLE_SIGNING_PUBLIC_KEY = "98ecb60bb1c418e771378d4451ddfb2f9c440e3ec29cf2f89850ded8f5190cea"

    /** The fixture API (docs/THREE_WAY_BENCHMARK.md), on the Mac, through `adb reverse`. */
    const val API_BASE = "http://localhost:8491"

    // The screens' fixed geometry (ThreewayScreens.kt, index.html).
    const val ROWS_TOP_DP = 56f + 48f + 24f
    const val ROW_DP = 72f
    const val TAP_HOLD_MILLIS = 40L
    const val SETTLE_MILLIS = 2_000L

    /** The app's one HTTP client, used by the native form directly and by the guest's HostHttp. */
    val okHttp: OkHttpClient by lazy {
      OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }
  }
}

/** Marks per request or navigation, epoch ms; filled from the host, the guest and the page. */
private class Recorder {
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
}

/**
 * The guest's HTTP: the app's OkHttp client behind the `HostHttp` service (a minimal version of
 * the production-host template's OkHttpHostHttp: same base, same thread hop; no request policy).
 * Marks, on the host's clock: `hostIn` (the call arrived from the guest), `hostNet` (the body has
 * been read) and `hostOut` (returning to the guest).
 */
private class BenchHostHttp(private val client: OkHttpClient, private val recorder: Recorder) : HostHttpProvider {
  override suspend fun execute(request: HttpRequest): HttpResponse {
    val seq = request.query["seq"]?.toIntOrNull() ?: -1
    recorder.mark(seq, "hostIn", System.currentTimeMillis())
    return withContext(Dispatchers.IO) {
      val url = "http://localhost:8491".toHttpUrl().newBuilder().apply {
        request.path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        request.query.forEach { (k, v) -> addQueryParameter(k, v) }
      }.build()
      client.newCall(Request.Builder().url(url).method(request.method, null).build()).execute().use { response ->
        val body = response.body?.string().orEmpty()
        recorder.mark(seq, "hostNet", System.currentTimeMillis())
        HttpResponse(status = response.code, body = body, headers = emptyMap())
      }
    }.also { recorder.mark(seq, "hostOut", System.currentTimeMillis()) }
  }
}

/** Serves the bundle from the APK's `assets/threeway/` by file name (no network for code). */
private class AssetsHttpClient(private val assets: AssetManager) : ZiplineHttpClient() {
  override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>): ByteString = withContext(Dispatchers.IO) {
    val name = url.substringAfterLast('/')
    assets.open("threeway/$name").use { it.readBytes().toByteString() }
  }
}

/** The guest's code load and bridge: reports `sendChanges` returning, and logs failures. */
private object ThreewayListener : EventListener.Factory {
  @Volatile var onSendChangesEnd: (() -> Unit)? = null

  /** Whether this process's code load read the modules from assets (an empty cache) or the cache. */
  @Volatile var modulesFromAssets = 0

  override fun create(app: TreehouseApp<*>, manifestUrl: String?): EventListener = object : EventListener() {
    override fun downloadStart(url: String): Any? {
      if (!url.endsWith("manifest.zipline.json")) modulesFromAssets++
      return null
    }

    override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) {
      Log.i("Threeway", "codeLoadSuccess modules=${manifest.modules.size} modulesFromAssets=$modulesFromAssets")
    }

    override fun codeLoadFailed(exception: Exception, startValue: Any?) {
      Log.e("Threeway", "codeLoadFailed", exception)
    }

    override fun uncaughtException(exception: Throwable) {
      Log.e("Threeway", "uncaughtException", exception)
    }

    override fun callEnd(call: Call, result: CallResult, startValue: Any?) {
      if ("sendChanges" in call.function.signature) onSendChangesEnd?.invoke()
    }
  }

  override fun close() {}
}
