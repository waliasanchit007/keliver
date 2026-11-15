/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.host.threeway

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import org.json.JSONArray
import org.json.JSONObject

/**
 * The scrolling series (`bench=scroll`; docs/THREE_WAY_BENCHMARK.md, "Scrolling"), BENCHMARK-ONLY.
 * The flings come from outside the app (`adb shell input swipe`, through the input system as a
 * finger's would); this class sees them in the activity's `dispatchTouchEvent` and, per fling (a
 * DOWN to the moment no frame has been drawn for [QUIET_MILLIS]), logs:
 *
 * - every frame's duration from the window's FrameMetrics (`TOTAL_DURATION`: from the frame's
 *   intended vsync to the RenderThread's completion; the numbers `dumpsys gfxinfo` reports, read in
 *   the app so each fling's frames can be told apart), the same source for the three forms, as all
 *   three draw through this window (the WebView through its draw functor);
 * - the end of the fling to the last frame (`tailMs`: the list shows settled content: nothing on
 *   screen changes after it);
 * - the list's position before and after (Compose: the lazy list's semantics, in rows; web: the
 *   WebView's scrollY, in rows of 72 dp; Compose's value is the lazy list's own semantics
 *   estimate, logged as it is);
 * - guest only: its frames in the fling (`AppLifecycle.sendFrame` calls; with a change batch; their
 *   summed and longest time, which is the guest's composition plus recording plus the batch) and,
 *   read after the fling from the guest (ThreewayGuestStats), the widgets it created;
 * - with `x=blank`: per frame, how much of the list's viewport no delivered row covers (the guest's
 *   host shows an item it has not composed yet as an empty 48 dp placeholder). This reads the
 *   semantics tree in every frame's draw, so it runs in its own series and its frame times are not
 *   the ones reported.
 */
@RequiresApi(26)
internal class ScrollTracker(
  private val window: Window,
  private val density: Float,
  private val guest: Boolean,
  private val blank: Boolean,
  /** The web form's vertical scroll in px, or null for the Compose forms. */
  private val webScrollY: (() -> Int)?,
  /** Called on the main thread when a fling has settled, with its line (without guest stats). */
  private val onFling: (JSONObject) -> Unit,
) {
  private val thread = HandlerThread("threeway-frames").apply { start() }
  private val frames = ArrayList<LongArray>() // intended vsync ns, total ns
  private val main = Handler(android.os.Looper.getMainLooper())
  private var fling: Fling? = null
  private var index = 0
  var ready = false

  private class Fling(val downNs: Long, val from: Double?) {
    var upNs = 0L
    val samples = ArrayList<DoubleArray>() // draw time ns, blank px (-1: unknown), position
  }

  private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
    if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
    val row = longArrayOf(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP), metrics.getMetric(FrameMetrics.TOTAL_DURATION))
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
        if (previous != null) finish(previous) // a DOWN before the last fling settled
        fling = Fling(System.nanoTime(), position())
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        val f = fling ?: return
        f.upNs = System.nanoTime()
        main.postDelayed({ checkSettled(f) }, CHECK_MILLIS)
      }
    }
  }

  private fun lastFrameEnd(): Long = synchronized(frames) { frames.maxOfOrNull { it[0] + it[1] } ?: 0L }

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
    finish(f)
  }

  private fun finish(f: Fling) {
    fling = null
    index++
    // The app draws nothing between flings, so every frame since the DOWN is this fling's.
    val inFling = synchronized(frames) {
      val mine = frames.filter { it[0] >= f.downNs - FRAME_SLACK_NS }
      frames.clear()
      mine
    }
    val end = inFling.maxOfOrNull { it[0] + it[1] } ?: f.upNs
    val o = JSONObject()
      .put("fling", index)
      .put("moveMs", ms(f.upNs - f.downNs))
      .put("tailMs", ms(end - f.upNs))
      .put("frames", JSONArray(inFling.sortedBy { it[0] }.map { Math.round(it[1] / 100_000.0) / 10.0 }))
      .put("framesCount", inFling.size)
      .put("framesAfterUp", inFling.count { it[0] >= f.upNs })
    val from = f.from
    val to = position()
    if (from != null) o.put("from", from)
    if (to != null) o.put("to", to)
    // The guest's frames in this fling (recorded on the Zipline thread by ThreewayListener).
    if (guest) {
      // The guest's frames since this fling's DOWN (recorded continuously since `ready`).
      val g = ThreewayListener.guestFrames.filter { it[0] >= f.downNs }
      ThreewayListener.guestFrames.clear()
      o.put("gFrames", g.size)
        .put("gFramesWithChanges", g.count { it[2] == 1L })
        .put("gMs", ms(g.sumOf { it[1] }))
        .put("gMaxMs", ms(g.maxOfOrNull { it[1] } ?: 0L))
        .put("gAfterUp", g.count { it[0] >= f.upNs })
        .put("gLastEndAfterUpMs", g.maxOfOrNull { it[0] + it[1] }?.let { ms(it - f.upNs) } ?: 0.0)
    }
    if (blank) {
      val rowPx = 48 * density
      val s = f.samples
      val withBlank = s.filter { it[1] > BLANK_MIN_PX }
      // The last drawn frame at which the list was still moving (its position differs from the
      // frame before), and the last one that still showed a placeholder: the list shows settled
      // content after both.
      var lastMove = f.downNs.toDouble()
      for (k in 1 until s.size) if (s[k][2] != s[k - 1][2]) lastMove = s[k][0]
      val lastBlank = withBlank.maxOfOrNull { it[0] }
      o.put("bFrames", s.size)
        .put("bFramesWithBlank", withBlank.size)
        .put("bMaxPlaceholders", Math.round((withBlank.maxOfOrNull { it[1] } ?: 0.0) / rowPx))
        .put("bPlaceholderFrames", withBlank.sumOf { Math.round(it[1] / rowPx) })
        .put("bFirstBlankAfterDownMs", withBlank.minOfOrNull { it[0] }?.let { ms((it - f.downNs).toLong()) } ?: JSONObject.NULL)
        .put("bLastBlankAfterUpMs", lastBlank?.let { ms((it - f.upNs).toLong()) } ?: JSONObject.NULL)
        .put("bLastMoveAfterUpMs", ms((lastMove - f.upNs).toLong()))
        .put("bSettledAfterUpMs", ms((maxOf(lastMove, lastBlank ?: 0.0) - f.upNs).toLong()))
        .put("bAtEnd", blankPx() ?: -1f)
    }
    onFling(o)
  }

  /** The list's first visible row (fractional): Compose from the lazy list's semantics, web from scrollY. */
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

  /** Pixels of the list's viewport that no row with content covers (a placeholder or nothing). */
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

  private companion object {
    /** No frame for this long after the fling's UP: the list has settled. */
    const val QUIET_MILLIS = 1_000L
    const val CHECK_MILLIS = 100L

    /** A frame whose intended vsync came up to a frame before the DOWN still belongs to the fling. */
    const val FRAME_SLACK_NS = 17_000_000L

    /** Less than this many px uncovered is rounding at the viewport's edges, not a placeholder. */
    const val BLANK_MIN_PX = 4f
  }
}
