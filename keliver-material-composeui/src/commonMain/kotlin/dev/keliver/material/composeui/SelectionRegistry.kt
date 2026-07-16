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
 * coordinates) here via onGloballyPositioned. The editor hit-tests taps
 * against [bounds] (smallest containing rect wins = innermost widget) and
 * draws its selection/hover overlays from the same map.
 *
 * Compose-observable so overlays track live layout changes. Guest-side
 * measurement is impossible (the interpreter emits protocol widgets, not
 * compose) — the HOST modifier is the only place geometry exists.
 */
public object SelectionRegistry {
  public val bounds: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Rect> = mutableStateMapOf()

  public fun report(handle: Int, rect: Rect) {
    bounds[handle] = rect
  }

  public fun clear() {
    bounds.clear()
  }

  /** The innermost (smallest-area) tagged widget containing the point, or null. */
  public fun hitTest(x: Float, y: Float): Int? =
    bounds.entries
      .filter { (_, r) -> x >= r.left && x <= r.right && y >= r.top && y <= r.bottom }
      .minByOrNull { (_, r) -> r.width * r.height }
      ?.key
}
