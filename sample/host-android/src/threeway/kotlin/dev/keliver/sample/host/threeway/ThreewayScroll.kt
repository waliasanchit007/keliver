/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.host.threeway

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * The scrolling series (`bench=scroll`; docs/THREE_WAY_BENCHMARK.md, "Scrolling"), BENCHMARK-ONLY.
 * The flings come from outside the app (`adb shell input swipe`, through the input system as a
 * finger's would); this class sees them in the activity's `dispatchTouchEvent` and, per fling (a
 * DOWN to the moment no frame has been drawn for [QUIET_MILLIS], or the next DOWN), logs:
 *
 * - how the fling was closed (`closedBy`: `settled`, or `touch` when the next DOWN came first: such
 *   a fling's guest work may run into the next one, so the summaries drop it), whether a guest frame
 *   was still running at its DOWN (`guestBusyAtDown`, dropped too), and whether it hit an end of the
 *   list (`edge`, dropped too);
 * - the release speed (`upVelocityPxS` / `upVelocityDpS`, from the touch events with Android's
 *   VelocityTracker, the same for the three forms; Compose forms also `flingVelocityPxS`, the
 *   velocity the list's fling started with, from the nested-scroll probe);
 * - the list's travel in px and the moment it stopped (`travelPx`, `stopAfterUpMs`: the last frame
 *   in which it scrolled), from [onScrolled]: the Compose forms report every scroll delta through a
 *   nested-scroll connection around the content (the list's own consumed px, drag and fling alike),
 *   the web form through the WebView's scroll listener. Px are comparable across forms, row counts
 *   are not (the guest's undelivered rows are 48 dp placeholders, its real rows 72 dp);
 * - every frame from the window's FrameMetrics, split at the stop: `movingFrames` (intended vsync up
 *   to the stop) and `afterStopFrames` (the host applying late guest batches, for example; the
 *   native form has almost none). `TOTAL_DURATION` (intended vsync to the RenderThread's completion)
 *   depends on where a frame starts against vsync, not only on its work, so it is not a jank measure
 *   by itself; the jank measure is `missedVsyncs`: the vsyncs skipped between consecutive moving
 *   frames (gaps between their intended-vsync timestamps, in refresh periods, minus one). Per-frame
 *   components (unknown delay, input, animation, layout, draw, sync, command issue, swap, and on API
 *   31+ GPU and the deadline; plus the late start, vsync minus intended vsync) are summarised per
 *   fling (medians and p90 over the moving frames) and written in full to the results file only
 *   (`frameDetail` lines, too long for logcat);
 * - the list's position before and after (`from`/`to`; Compose: the lazy list's semantics estimate,
 *   index × 500 + px offset in the first item, NOT rows or px; web: scrollY in rows of 72 dp);
 * - guest only: its frames in the fling (`AppLifecycle.sendFrame` calls; with a change batch; their
 *   summed and longest time, which is the guest's composition plus recording plus the batch) and,
 *   read after the fling from the guest (ThreewayGuestStats), the widgets it created (counted only
 *   with `x=scroll`, which wraps the guest's protocol adapter: a cost only the guest pays);
 * - with `x=blank`: per frame, how much of the list's viewport no delivered row covers (the guest's
 *   host shows an item it has not composed yet as an empty 48 dp placeholder). This reads the
 *   semantics tree in every frame's draw, so it runs in its own series and its frame times are not
 *   the ones reported.
 */
@RequiresApi(26)
internal class ScrollTracker(
  private val window: Window,
  private val density: Float,
  /**
   * The display's current refresh period (ns), for counting missed vsyncs; read when a fling closes
   * (a phone with a variable refresh rate can change it, so each fling logs the one it used).
   */
  private val vsyncPeriodNs: () -> Long,
  private val guest: Boolean,
  private val blank: Boolean,
  /** The web form's vertical scroll in px, or null for the Compose forms. */
  private val webScrollY: (() -> Int)?,
  /** The web form: whether the page cannot scroll further in the given direction (1 down, -1 up). */
  private val webAtEnd: ((Int) -> Boolean)?,
  /** Called on the main thread with the fling's per-frame detail (results file only). */
  private val onFrameDetail: (JSONObject) -> Unit,
  /** Called on the main thread when a fling has closed, with its line (without guest stats). */
  private val onFling: (JSONObject) -> Unit,
) {
  private val thread = HandlerThread("threeway-frames").apply { start() }
  private val frames = ArrayList<LongArray>() // see [FrameCol]
  private val main = Handler(android.os.Looper.getMainLooper())
  private var fling: Fling? = null
  private var index = 0
  private var velocity: VelocityTracker? = null
  var ready = false

  private class Fling(val downNs: Long, val from: Double?, val guestBusyAtDown: Boolean) {
    var upNs = 0L
    var upVelocityPxS = Float.NaN
    var flingVelocityPxS = Float.NaN
    var travelPx = 0.0
    var lastMoveNs = 0L
    var leftoverPx = 0.0
    val samples = ArrayList<DoubleArray>() // draw time ns, blank px (-1: unknown), position
  }

  /** Columns of a frame row. */
  private object FrameCol {
    const val INTENDED = 0
    const val TOTAL = 1
    const val VSYNC = 2
    const val UNKNOWN = 3
    const val INPUT = 4
    const val ANIMATION = 5
    const val LAYOUT = 6
    const val DRAW = 7
    const val SYNC = 8
    const val COMMAND = 9
    const val SWAP = 10
    const val GPU = 11 // -1 below API 31
    const val DEADLINE = 12 // -1 below API 31
    const val SIZE = 13
  }

  private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
    if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
    val row = LongArray(FrameCol.SIZE)
    row[FrameCol.INTENDED] = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
    row[FrameCol.TOTAL] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
    row[FrameCol.VSYNC] = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
    row[FrameCol.UNKNOWN] = metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
    row[FrameCol.INPUT] = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
    row[FrameCol.ANIMATION] = metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
    row[FrameCol.LAYOUT] = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
    row[FrameCol.DRAW] = metrics.getMetric(FrameMetrics.DRAW_DURATION)
    row[FrameCol.SYNC] = metrics.getMetric(FrameMetrics.SYNC_DURATION)
    row[FrameCol.COMMAND] = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
    row[FrameCol.SWAP] = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
    if (Build.VERSION.SDK_INT >= 31) {
      row[FrameCol.GPU] = metrics.getMetric(FrameMetrics.GPU_DURATION)
      row[FrameCol.DEADLINE] = metrics.getMetric(FrameMetrics.DEADLINE)
    } else {
      row[FrameCol.GPU] = -1
      row[FrameCol.DEADLINE] = -1
    }
    synchronized(frames) { frames += row }
  }

  private val drawListener = ViewTreeObserver.OnDrawListener {
    val f = fling ?: return@OnDrawListener
    f.samples += doubleArrayOf(System.nanoTime().toDouble(), blankPx()?.toDouble() ?: -1.0, position() ?: -1.0)
  }

  fun start() {
    if (guest) ThreewayListener.recordGuestFrames = true
    window.addOnFrameMetricsAvailableListener(frameListener, Handler(thread.looper))
    if (blank) window.decorView.viewTreeObserver.addOnDrawListener(drawListener)
  }

  /** From the activity's dispatchTouchEvent (the input system's events and the driver's taps). */
  fun onTouch(event: MotionEvent) {
    if (!ready) return
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        val previous = fling
        if (previous != null) finish(previous, closedBy = "touch") // a DOWN before the last fling settled
        fling = Fling(System.nanoTime(), position(), guestBusyAtDown = guest && ThreewayListener.guestFrameStartNs != 0L)
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(event) }
      }
      MotionEvent.ACTION_MOVE -> velocity?.addMovement(event)
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        val f = fling ?: return
        f.upNs = System.nanoTime()
        velocity?.let {
          it.addMovement(event)
          it.computeCurrentVelocity(1000)
          f.upVelocityPxS = it.yVelocity
        }
        main.postDelayed({ checkSettled(f) }, CHECK_MILLIS)
      }
    }
  }

  /**
   * Main thread: the list scrolled by [consumedPx] in this frame ([leftoverPx]: what it could not
   * consume, nonzero at an end of the list). Compose forms: from a nested-scroll connection's
   * onPostScroll; web: from the WebView's scroll listener (no leftover).
   */
  fun onScrolled(consumedPx: Float, leftoverPx: Float) {
    val f = fling ?: return
    if (abs(consumedPx) > 0f) {
      f.travelPx += abs(consumedPx)
      f.lastMoveNs = System.nanoTime()
    }
    f.leftoverPx += abs(leftoverPx)
  }

  /** Compose forms: the velocity the list's fling starts with (nested-scroll onPreFling), px/s. */
  fun onFlingStart(velocityPxS: Float) {
    fling?.flingVelocityPxS = velocityPxS
  }

  private fun lastFrameEnd(): Long = synchronized(frames) { frames.maxOfOrNull { it[FrameCol.INTENDED] + it[FrameCol.TOTAL] } ?: 0L }

  private fun checkSettled(f: Fling) {
    if (fling !== f) return
    val now = System.nanoTime()
    // Settled: no frame drawn for QUIET_MILLIS and (guest) no guest frame running or run in that time.
    val quiet = now - maxOf(lastFrameEnd(), f.upNs, if (guest) ThreewayListener.lastGuestFrameEndNs else 0L)
    val guestBusy = guest && ThreewayListener.guestFrameStartNs != 0L
    if (quiet < QUIET_MILLIS * 1_000_000 || guestBusy) {
      main.postDelayed({ checkSettled(f) }, CHECK_MILLIS)
      return
    }
    finish(f, closedBy = "settled")
  }

  private fun finish(f: Fling, closedBy: String) {
    fling = null
    index++
    // The app draws nothing between flings, so every frame since the DOWN is this fling's.
    val inFling = synchronized(frames) {
      val mine = frames.filter { it[FrameCol.INTENDED] >= f.downNs - FRAME_SLACK_NS }
      frames.clear()
      mine
    }.sortedBy { it[FrameCol.INTENDED] }
    if (f.upNs == 0L) f.upNs = System.nanoTime() // closed before its UP (should not happen with input swipe)
    val stopNs = if (f.lastMoveNs != 0L) f.lastMoveNs else f.upNs
    // A frame whose intended vsync is up to the stop is a moving frame: the frame that applied the
    // last scroll delta ran its animation phase (where the delta was reported) after its vsync.
    val moving = inFling.filter { it[FrameCol.INTENDED] <= stopNs }
    val afterStop = inFling.filter { it[FrameCol.INTENDED] > stopNs }
    val period = vsyncPeriodNs()
    var missed = 0L
    for (k in 1 until moving.size) {
      val gap = moving[k][FrameCol.INTENDED] - moving[k - 1][FrameCol.INTENDED]
      missed += (Math.round(gap.toDouble() / period) - 1).coerceAtLeast(0)
    }
    val end = inFling.maxOfOrNull { it[FrameCol.INTENDED] + it[FrameCol.TOTAL] } ?: f.upNs
    val from = f.from
    val to = position()
    val edge = f.leftoverPx > EDGE_LEFTOVER_PX || atEnd(f)
    val o = JSONObject()
      .put("fling", index)
      .put("closedBy", closedBy)
      .put("edge", edge)
      .put("moveMs", ms(f.upNs - f.downNs))
      .put("upVelocityPxS", round0(f.upVelocityPxS))
      .put("upVelocityDpS", round0(f.upVelocityPxS / density))
      .put("travelPx", round0(f.travelPx.toFloat()))
      .put("stopAfterUpMs", ms(stopNs - f.upNs))
      .put("tailMs", ms(end - f.upNs))
      .put("framesCount", inFling.size)
      .put("framesAfterUp", inFling.count { it[FrameCol.INTENDED] >= f.upNs })
      .put("movingFrames", JSONArray(moving.map { ms(it[FrameCol.TOTAL]) }))
      .put("afterStopFrames", JSONArray(afterStop.map { ms(it[FrameCol.TOTAL]) }))
      .put("missedVsyncs", missed)
      .put("vsyncPeriodMs", Math.round(period / 10_000.0) / 100.0)
      .put("movingMedianMs", components(moving, 0.5))
      .put("movingP90Ms", components(moving, 0.9))
    if (!f.flingVelocityPxS.isNaN()) o.put("flingVelocityPxS", round0(f.flingVelocityPxS))
    if (from != null) o.put("from", from)
    if (to != null) o.put("to", to)
    // The guest's frames in this fling (recorded on the Zipline thread by ThreewayListener).
    if (guest) {
      // The guest's frames since this fling's DOWN (recorded continuously since `ready`). A guest
      // frame that started before this DOWN is in neither fling's counts: `guestBusyAtDown` marks
      // the fling, and the summaries drop it.
      val g = ThreewayListener.guestFrames.filter { it[0] >= f.downNs }
      ThreewayListener.guestFrames.clear()
      o.put("guestBusyAtDown", f.guestBusyAtDown)
        .put("gFrames", g.size)
        .put("gFramesWithChanges", g.count { it[2] == 1L })
        .put("gMs", ms(g.sumOf { it[1] }))
        .put("gMaxMs", ms(g.maxOfOrNull { it[1] } ?: 0L))
        .put("gAfterUp", g.count { it[0] >= f.upNs })
        .put("gLastEndAfterUpMs", g.maxOfOrNull { it[0] + it[1] }?.let { ms(it - f.upNs) } ?: 0.0)
    }
    if (blank) {
      val rowPx = 48 * density
      val s = f.samples
      // A placeholder: at least half a row uncovered. Less (but over BLANK_MIN_PX) is a sub-row gap,
      // counted apart (a row edge between two deliveries, for example), not a visible placeholder.
      val withBlank = s.filter { it[1] >= rowPx / 2 }
      val withGap = s.filter { it[1] > BLANK_MIN_PX && it[1] < rowPx / 2 }
      // The last drawn frame at which the semantics position still changed (kept for comparison
      // with the first session; the probe's stop above is the one used).
      var lastMove = f.downNs.toDouble()
      for (k in 1 until s.size) if (s[k][2] != s[k - 1][2]) lastMove = s[k][0]
      val lastBlank = withBlank.maxOfOrNull { it[0] }
      // The first frame with content after the last blank frame: the rows appear in it. The list
      // sits still waiting for the guest, so this frame can come a whole guest frame after the last
      // blank one; the first session reported the last blank frame (a lower bound).
      val contentAt = lastBlank?.let { lb -> s.firstOrNull { it[0] > lb && it[1] in 0.0..BLANK_MIN_PX.toDouble() }?.get(0) }
      val settled = maxOf(stopNs.toDouble(), contentAt ?: lastBlank ?: 0.0)
      o.put("bFrames", s.size)
        .put("bFramesWithBlank", withBlank.size)
        .put("bFramesWithGap", withGap.size)
        .put("bMaxPlaceholders", Math.round((withBlank.maxOfOrNull { it[1] } ?: 0.0) / rowPx))
        .put("bPlaceholderFrames", withBlank.sumOf { Math.round(it[1] / rowPx) })
        .put("bFirstBlankAfterDownMs", withBlank.minOfOrNull { it[0] }?.let { ms((it - f.downNs).toLong()) } ?: JSONObject.NULL)
        .put("bLastBlankAfterUpMs", lastBlank?.let { ms((it - f.upNs).toLong()) } ?: JSONObject.NULL)
        .put("bContentAfterUpMs", contentAt?.let { ms((it - f.upNs).toLong()) } ?: JSONObject.NULL)
        .put("bLastMoveAfterUpMs", ms((lastMove - f.upNs).toLong()))
        .put("bSettledAfterUpMs", ms((settled - f.upNs).toLong()))
        .put("bAtEnd", blankPx() ?: -1f)
    }
    onFling(o)
    onFrameDetail(
      JSONObject().put("frameDetail", index).put(
        "frames",
        JSONArray(
          inFling.map { r ->
            // [ms from the DOWN to the intended vsync, total, late start, unknown, input, animation,
            //  layout, draw, sync, command, swap, gpu, deadline], ms.
            JSONArray(
              listOf(ms(r[FrameCol.INTENDED] - f.downNs), ms(r[FrameCol.TOTAL]), ms(r[FrameCol.VSYNC] - r[FrameCol.INTENDED])) +
                (FrameCol.UNKNOWN..FrameCol.DEADLINE).map { c -> if (r[c] < 0) -1.0 else ms(r[c]) },
            )
          },
        ),
      ),
    )
  }

  /** Per-component nearest-rank percentile over [rows], ms. */
  private fun components(rows: List<LongArray>, p: Double): JSONObject {
    val o = JSONObject()
    if (rows.isEmpty()) return o
    fun put(name: String, value: (LongArray) -> Long) {
      val values = rows.map(value).filter { it >= 0 }.map { it / 1e6 }
      if (values.isNotEmpty()) o.put(name, Math.round(nearestRank(values, p) * 10) / 10.0)
    }
    put("total") { it[FrameCol.TOTAL] }
    put("lateStart") { it[FrameCol.VSYNC] - it[FrameCol.INTENDED] }
    put("unknown") { it[FrameCol.UNKNOWN] }
    put("input") { it[FrameCol.INPUT] }
    put("animation") { it[FrameCol.ANIMATION] }
    put("layout") { it[FrameCol.LAYOUT] }
    put("draw") { it[FrameCol.DRAW] }
    put("sync") { it[FrameCol.SYNC] }
    put("command") { it[FrameCol.COMMAND] }
    put("swap") { it[FrameCol.SWAP] }
    put("gpu") { it[FrameCol.GPU] }
    put("deadline") { it[FrameCol.DEADLINE] }
    return o
  }

  /** Whether the list is at the end it was flung towards (up the screen = down the list). */
  private fun atEnd(f: Fling): Boolean {
    if (f.upVelocityPxS.isNaN()) return false
    val down = f.upVelocityPxS < 0 // the finger moved up: the list scrolls towards its end
    webAtEnd?.let { return it(if (down) 1 else -1) }
    val node = lazyNode() ?: return false
    val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
    // The lazy list's maxValue equals its value once it cannot scroll forward.
    return if (down) range.value() >= range.maxValue() else range.value() <= 0f
  }

  /** The list's first visible row (web, fractional) or the lazy list's semantics estimate (Compose; index × 500 + px offset). */
  private fun position(): Double? {
    webScrollY?.let { return it() / (72.0 * density) }
    val node = lazyNode() ?: return null
    val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
    return range.value().toDouble()
  }

  private fun lazyNode(): SemanticsNode? {
    val root = findRoot(window.decorView) ?: return null
    val queue = ArrayDeque<SemanticsNode>()
    queue += root.semanticsOwner.rootSemanticsNode
    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      if (SemanticsProperties.VerticalScrollAxisRange in node.config) return node
      queue += node.children
    }
    return null
  }

  /**
   * Pixels of the list's viewport that no row with content covers (a placeholder or nothing). The
   * rows' covered heights are summed, not unioned: this holds only because each row is one merged
   * clickable semantics node (its children do not appear as separate nodes) and rows do not overlap.
   */
  private fun blankPx(): Float? {
    if (webScrollY != null) return null
    val list = lazyNode() ?: return null
    val viewport = list.boundsInWindow
    var covered = 0f
    for (row in list.children) {
      val b = row.boundsInWindow
      val top = maxOf(b.top, viewport.top)
      val bottom = minOf(b.bottom, viewport.bottom)
      if (bottom > top && b.width > 0f) covered += bottom - top
    }
    return (viewport.height - covered).coerceAtLeast(0f)
  }

  private fun findRoot(view: View): RootForTest? {
    if (view is RootForTest) return view
    if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
    return null
  }

  private fun ms(ns: Long): Double = Math.round(ns / 100_000.0) / 10.0

  private fun round0(x: Float): Any = if (x.isNaN()) JSONObject.NULL else Math.round(x)

  private companion object {
    /** No frame for this long after the fling's UP: the list has settled. */
    const val QUIET_MILLIS = 1_000L
    const val CHECK_MILLIS = 100L

    /** A frame whose intended vsync came up to a frame before the DOWN still belongs to the fling. */
    const val FRAME_SLACK_NS = 17_000_000L

    /** Less than this many px uncovered is rounding at the viewport's edges, not a gap. */
    const val BLANK_MIN_PX = 4f

    /** More scroll than this left unconsumed by the list during a fling: it hit an end. */
    const val EDGE_LEFTOVER_PX = 1.0
  }
}
