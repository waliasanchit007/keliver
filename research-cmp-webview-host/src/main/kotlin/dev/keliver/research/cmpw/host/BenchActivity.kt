/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.research.cmpw.host

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import dev.keliver.research.cmpw.CmpApp
import dev.keliver.research.cmpw.NAV_BASE
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The research route (efficiency review C2): the same Compose screens as `native` (compiled into
 * this app) or as the Compose Multiplatform web build in a WebView (`cmpw` wasmJs, `cmpj` js; a
 * `-http` suffix serves them from [AssetServer] instead of shouldInterceptRequest).
 *
 * Method, as docs/THREE_WAY_BENCHMARK.md's: each form reports when a screen's composition is applied
 * (native: the screen's DisposableEffect; web: the same effect, then the page's next animation frame
 * and a task after it); the route takes the next draw of this window as "drawn". Times in epoch ms.
 *
 * Extras: `form`; `rows` (the list's length, 50 by default); `bench` = `nav` (synthetic taps: open a row, back; `n`, `warmup`) or `scroll`
 * (synthetic flings, `flings` each way; frame times on the thread that runs Compose).
 * Results: logcat tag `Cmpw` and `cmpw-results.txt` in the app's external files.
 */
class BenchActivity : ComponentActivity() {
  private val main = Handler(Looper.getMainLooper())
  private lateinit var form: String
  private var rows = 50
  private lateinit var content: View
  private var web: WebView? = null
  private var firstContent = true
  private var waiter: ((String, Int, Long) -> Unit)? = null
  private var lastNav = NAV_BASE

  override fun onCreate(savedInstanceState: Bundle?) {
    Cold.mark("activityCreate")
    super.onCreate(savedInstanceState)
    form = intent.getStringExtra("form") ?: "native"
    rows = intent.getIntExtra("rows", 50)
    content = if (form == "native") {
      ComposeView(this).apply { setContent { CmpApp(rows) { name, seq -> onComposed(name, seq) } } }
    } else {
      createWebView()
    }
    setContentView(content)
  }

  private fun onComposed(name: String, seq: Int) {
    if (firstContent && name == "list") Cold.mark("listComposed")
    onScreen(name, seq)
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(): WebView {
    Cold.mark("webViewNew")
    val w = WebView(this)
    Cold.mark("webViewCreated")
    w.settings.javaScriptEnabled = true
    w.addJavascriptInterface(Bridge(), "KMark")
    w.webChromeClient = object : WebChromeClient() {
      override fun onConsoleMessage(m: ConsoleMessage): Boolean {
        Log.i("CmpwPage", "${m.message()} @${m.sourceId()}:${m.lineNumber()}")
        return true
      }
    }
    w.webViewClient = object : WebViewClient() {
      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != ASSET_HOST) return null
        val path = url.path.orEmpty().removePrefix("/")
        return try {
          WebResourceResponse(mimeOf(path), null, assets.open(path))
        } catch (e: Exception) {
          Log.w(TAG, "missing asset $path")
          WebResourceResponse("text/plain", null, 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
      }

      override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        Cold.mark("pageStarted")
      }

      override fun onPageFinished(view: WebView, url: String) {
        Cold.mark("pageFinished")
      }
    }
    web = w
    val dir = if (form.startsWith("cmpj")) "cmpj" else "cmpw"
    // `--ez a11y false`: turns off CMP web's semantics mirror in the DOM (wasmJs; on by default).
    val query = "n=$rows" + if (intent.hasExtra("a11y")) "&a11y=${if (intent.getBooleanExtra("a11y", true)) 1 else 0}" else ""
    val url = if (form.endsWith("-http")) {
      "http://127.0.0.1:${AssetServer.start(assets)}/$dir/index.html?$query"
    } else {
      "https://$ASSET_HOST/$dir/index.html?$query"
    }
    Log.i(TAG, "form=$form url=$url webview=${WebView.getCurrentWebViewPackage()?.versionName}")
    w.loadUrl(url)
    return w
  }

  /** Called by the page (on the JavaBridge thread). */
  inner class Bridge {
    @JavascriptInterface
    fun point(name: String, at: Double) {
      Cold.markAt(name, at.toLong())
    }

    @JavascriptInterface
    fun composed(name: String, seq: Int, at: Double) {
      if (name == "list") Cold.markAt("listComposed", at.toLong())
    }

    @JavascriptInterface
    fun mark(name: String, seq: Int, at: Double) {
      if (name == "list") Cold.markAt("listPainted", at.toLong())
      main.post { onScreen(name, seq) }
    }
  }

  /** The screen's content is in its tree: the next draw of this window is "drawn". */
  private fun onScreen(screen: String, seq: Int) {
    val decor = window.decorView
    val listener = object : ViewTreeObserver.OnDrawListener {
      var fired = false
      override fun onDraw() {
        if (fired) return
        fired = true
        val now = System.currentTimeMillis()
        main.post { decor.viewTreeObserver.removeOnDrawListener(this) }
        if (firstContent && screen == "list") {
          firstContent = false
          Cold.markAt("drawn", now)
          reportFullyDrawn()
          main.postAtFrontOfQueue {
            Cold.mark("frameEnd")
            log(Cold.line(form, web))
            main.postDelayed(::runBench, SETTLE_MILLIS)
          }
        }
        val w = waiter
        if (w != null) main.post { w(screen, seq, now) }
      }
    }
    decor.viewTreeObserver.addOnDrawListener(listener)
    decor.invalidate()
  }

  // ---------------------------------------------------------------------------------------------
  // The driver.

  private fun runBench() {
    when (intent.getStringExtra("bench")) {
      "nav" -> nav()
      "scroll" -> scroll()
      null -> Unit
      else -> log("""{"form":"$form","error":"unknown bench"}""")
    }
  }

  private val opens = ArrayList<Long>()
  private val backs = ArrayList<Long>()

  private fun nav() {
    val n = intent.getIntExtra("n", 14)
    val warmup = intent.getIntExtra("warmup", 2)
    fun step(i: Int) {
      if (i > n) {
        log(summary("open", opens.drop(warmup)))
        log(summary("back", backs.drop(warmup)))
        log("""{"form":"$form","done":true}""")
        return
      }
      val row = (i - 1) % 5
      tapThen(widthDp() / 3, TOP_BAR_DP + ROW_DP * row + ROW_DP / 2, "detail") { open ->
        opens += open
        log("""{"form":"$form","bench":"open","i":$i,"ms":$open}""")
        main.postDelayed({
          tapThen(BACK_X_DP, BACK_Y_DP, "list") { back ->
            backs += back
            log("""{"form":"$form","bench":"back","i":$i,"ms":$back}""")
            main.postDelayed({ step(i + 1) }, PAUSE_MILLIS)
          }
        }, PAUSE_MILLIS)
      }
    }
    step(1)
  }

  /** Taps (DOWN, UP 40 ms later) and reports drawn − UP for the first newer [want] screen. */
  private fun tapThen(xDp: Float, yDp: Float, want: String, done: (Long) -> Unit) {
    val density = resources.displayMetrics.density
    val x = xDp * density
    val y = yDp * density
    val downTime = SystemClock.uptimeMillis()
    dispatch(MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
    main.postDelayed({
      val up = System.currentTimeMillis()
      val timeout = Runnable {
        waiter = null
        log("""{"form":"$form","want":"$want","timeout":true}""")
        done(-1)
      }
      waiter = { screen, seq, drawnAt ->
        if (screen == want && seq > lastNav) {
          waiter = null
          main.removeCallbacks(timeout)
          lastNav = seq
          done(drawnAt - up)
        }
      }
      main.postDelayed(timeout, 20_000)
      dispatch(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), x, y)
    }, TAP_HOLD_MILLIS)
  }

  private fun dispatch(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
    val e = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
    e.source = InputDevice.SOURCE_TOUCHSCREEN
    content.dispatchTouchEvent(e)
    e.recycle()
  }

  // Scrolling: synthetic flings, alternately up the list and back (run it with `rows` large enough
  // that a fling never reaches an end); the frame times of the thread that runs Compose (native: this
  // app's main thread, Choreographer; web: the page's main thread, requestAnimationFrame) and of
  // this app's main thread in every form.
  private val appFrames = ArrayList<Double>()
  private var recording = false
  private val frameCallback = object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
      if (!recording) return
      appFrames += System.currentTimeMillis().toDouble()
      Choreographer.getInstance().postFrameCallback(this)
    }
  }
  private val flingStarts = ArrayList<Double>()

  private fun scroll() {
    val flings = intent.getIntExtra("flings", 10)
    val steps = intent.getIntExtra("steps", 20)
    recording = true
    Choreographer.getInstance().postFrameCallback(this.frameCallback)
    web?.evaluateJavascript("window.__kstart()", null)
    fun one(i: Int) {
      if (i > 2 * flings) {
        main.postDelayed(::endScroll, 500)
        return
      }
      flingStarts += System.currentTimeMillis().toDouble()
      fling(down = i % 2 == 1, steps = steps) { main.postDelayed({ one(i + 1) }, FLING_PAUSE_MILLIS) }
    }
    main.postDelayed({ one(1) }, 500)
  }

  /** A fling: DOWN, [steps] MOVEs 8 ms apart over 45% of the height, UP. */
  private fun fling(down: Boolean, steps: Int, done: () -> Unit) {
    val h = content.height.toFloat()
    val x = content.width / 3f
    val y0 = if (down) h * 0.75f else h * 0.30f
    val y1 = if (down) h * 0.30f else h * 0.75f
    val downTime = SystemClock.uptimeMillis()
    dispatch(MotionEvent.ACTION_DOWN, downTime, downTime, x, y0)
    for (k in 1..steps + 1) {
      main.postDelayed({
        val t = SystemClock.uptimeMillis()
        if (k <= steps) {
          dispatch(MotionEvent.ACTION_MOVE, downTime, t, x, y0 + (y1 - y0) * k / steps)
        } else {
          dispatch(MotionEvent.ACTION_UP, downTime, t, x, y1)
          done()
        }
      }, MOVE_MILLIS * k)
    }
  }

  private fun endScroll() {
    recording = false
    val app = frameStats("app", appFrames)
    val w = web
    if (w == null) {
      log(JSONObject().put("form", form).put("bench", "scroll").put("compose", app).put("app", app).toString())
      log("""{"form":"$form","done":true}""")
    } else {
      w.evaluateJavascript("window.__kstop()") { json ->
        // evaluateJavascript returns the value JSON-encoded: a string holding the array.
        val arr = JSONArray(JSONArray("[$json]").getString(0))
        val page = ArrayList<Double>(arr.length())
        for (i in 0 until arr.length()) page += arr.getDouble(i)
        log(JSONObject().put("form", form).put("bench", "scroll").put("compose", frameStats("page", page)).put("app", app).toString())
        log("""{"form":"$form","done":true}""")
      }
    }
  }

  /** Intervals between consecutive frames inside each fling's first [FLING_WINDOW_MILLIS]. */
  private fun frameStats(name: String, frames: List<Double>): JSONObject {
    val vsync = 1000.0 / (display?.refreshRate ?: 60f)
    val gaps = ArrayList<Double>()
    for (start in flingStarts) {
      val inWindow = frames.filter { it >= start && it <= start + FLING_WINDOW_MILLIS }
      for (i in 1 until inWindow.size) gaps += inWindow[i] - inWindow[i - 1]
    }
    val sorted = gaps.sorted()
    fun pct(p: Double) = if (sorted.isEmpty()) -1.0 else sorted[((sorted.size - 1) * p).toInt()]
    val over25 = gaps.count { it > 25.0 }
    val missed = gaps.sumOf { maxOf(0L, Math.round(it / vsync) - 1) }
    return JSONObject().put("source", name).put("intervals", gaps.size).put("median", pct(0.5)).put("p90", pct(0.9))
      .put("p99", pct(0.99)).put("max", sorted.lastOrNull() ?: -1.0).put("over25", over25)
      .put("over25Pct", if (gaps.isEmpty()) -1.0 else 100.0 * over25 / gaps.size)
      .put("missedVsyncs", missed).put("missedPerFling", missed.toDouble() / flingStarts.size.coerceAtLeast(1))
      .put("vsyncMs", vsync)
  }

  private fun summary(step: String, values: List<Long>): String {
    val s = values.filter { it >= 0 }.sorted()
    fun pct(p: Double) = if (s.isEmpty()) -1 else s[((s.size - 1) * p).toInt()]
    val median = if (s.isEmpty()) -1.0 else if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    return JSONObject().put("summary", step).put("form", form).put("n", s.size).put("median", median)
      .put("p90", pct(0.9)).put("min", s.firstOrNull() ?: -1).put("max", s.lastOrNull() ?: -1).toString()
  }

  private fun widthDp(): Float = content.width / resources.displayMetrics.density

  private fun log(line: String) {
    Log.i(TAG, line)
    runCatching { File(getExternalFilesDir(null), "cmpw-results.txt").appendText("${System.currentTimeMillis()} $line\n") }
  }

  private companion object {
    const val TAG = "Cmpw"
    const val ASSET_HOST = "appassets.androidplatform.net"

    // Material 3 geometry (CmpScreens.kt): TopAppBar 64 dp, two-line ListItem rows 72 dp, the
    // navigation icon's 48 dp button 4 dp from the start.
    const val TOP_BAR_DP = 64f
    const val ROW_DP = 72f
    const val BACK_X_DP = 28f
    const val BACK_Y_DP = 32f
    const val TAP_HOLD_MILLIS = 40L
    const val PAUSE_MILLIS = 700L
    const val SETTLE_MILLIS = 2_000L
    const val MOVE_MILLIS = 8L
    const val FLING_PAUSE_MILLIS = 1_500L
    const val FLING_WINDOW_MILLIS = 1_200.0
  }
}

internal fun mimeOf(path: String): String = when (path.substringAfterLast('.', "")) {
  "html" -> "text/html"
  "js", "mjs" -> "text/javascript"
  "wasm" -> "application/wasm"
  "json" -> "application/json"
  "ttf" -> "font/ttf"
  "otf" -> "font/otf"
  "png" -> "image/png"
  "map" -> "application/json"
  else -> "application/octet-stream"
}

/** Cold-start marks (epoch ms, first occurrence), logged once as ms from the process's start. */
internal object Cold {
  private val marks = ConcurrentHashMap<String, Long>()

  fun mark(name: String) {
    marks.putIfAbsent(name, System.currentTimeMillis())
  }

  fun markAt(name: String, at: Long) {
    marks.putIfAbsent(name, at)
  }

  fun line(form: String, web: WebView?): String {
    val processStart = System.currentTimeMillis() - (SystemClock.uptimeMillis() - Process.getStartUptimeMillis())
    val o = JSONObject().put("cold", form)
    for ((name, at) in marks.entries.sortedBy { it.value }) o.put(name, at - processStart)
    if (web != null) o.put("webview", WebView.getCurrentWebViewPackage()?.versionName)
    return o.toString()
  }
}
