/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect

/**
 * P3-11 canvas click-to-select. The editor injects the portal-internal
 * `SelectionTag(handle)` modifier into its in-memory preview tree; the
 * ComposeUi visual applier reports each tagged widget's bounds (root
 * coordinates) here. The editor hit-tests taps against them (smallest
 * containing rect wins = innermost widget) and draws its selection/hover
 * overlays from [bounds].
 *
 * One handle can be drawn by MANY widgets: every widget inside a project
 * component's preview carries the instance's handle, and a Repeat's mock rows
 * all carry the template's. Each widget therefore reports as its own
 * reporter: keeping one rect per handle let the last widget laid out
 * overwrite the others, so a click on most of a component instance (or on
 * any Repeat row but one) fell through to its parent.
 *
 * Compose-observable so overlays track live layout changes. Guest-side
 * measurement is impossible (the interpreter emits protocol widgets, not
 * compose) — the HOST modifier is the only place geometry exists.
 */
public object SelectionRegistry {
  /** Each handle's bounds: the union of the rects of every widget drawing it. */
  public val bounds: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Rect> = mutableStateMapOf()

  private val parts = HashMap<Any, Pair<Int, Rect>>()
  private val reportersOf = HashMap<Int, MutableSet<Any>>()

  /** One rect for [handle], replacing any other rect reported for it without a reporter. */
  public fun report(handle: Int, rect: Rect) {
    report(handle, LegacyReporter(handle), rect)
  }

  /** [reporter]'s current rect, drawing [handle]; one reporter draws one handle at a time. */
  public fun report(handle: Int, reporter: Any, rect: Rect) {
    val previous = parts.put(reporter, handle to rect)
    if (previous != null && previous.first != handle) detach(previous.first, reporter)
    reportersOf.getOrPut(handle) { LinkedHashSet() }.add(reporter)
    recompute(handle)
  }

  /** [reporter] left the composition: its rect no longer draws its handle. */
  public fun remove(reporter: Any) {
    val previous = parts.remove(reporter) ?: return
    detach(previous.first, reporter)
  }

  public fun clear() {
    parts.clear()
    reportersOf.clear()
    bounds.clear()
  }

  /**
   * Every rect currently drawing [handle] (a component's widgets, a Repeat's rows). Empty rects
   * (an image that has not loaded, a zero-width spacer) are left out: they draw nothing, and one
   * reported at the root's origin would stretch the handle's bounds across the screen.
   */
  public fun rectsOf(handle: Int): List<Rect> =
    reportersOf[handle].orEmpty().mapNotNull { parts[it]?.second }.filter { !it.isEmpty }

  /** The innermost (smallest-area) tagged widget containing the point, or null. */
  public fun hitTest(x: Float, y: Float): Int? =
    parts.values
      .filter { (_, r) -> !r.isEmpty && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom }
      .minByOrNull { (_, r) -> r.width * r.height }
      ?.first

  private fun detach(handle: Int, reporter: Any) {
    val set = reportersOf[handle] ?: return
    set.remove(reporter)
    if (set.isEmpty()) reportersOf.remove(handle)
    recompute(handle)
  }

  private fun recompute(handle: Int) {
    val rects = rectsOf(handle)
    if (rects.isEmpty()) {
      bounds.remove(handle)
    } else {
      var u = rects[0]
      for (i in 1 until rects.size) {
        val r = rects[i]
        u = Rect(minOf(u.left, r.left), minOf(u.top, r.top), maxOf(u.right, r.right), maxOf(u.bottom, r.bottom))
      }
      if (bounds[handle] != u) bounds[handle] = u
    }
  }

  private data class LegacyReporter(val handle: Int)
}
