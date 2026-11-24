/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** foundation-layout and foundation's value types: Arrangement, PaddingValues, shapes, BorderStroke. */
fun foundationCases(): List<Case> = listOf(
  Case("foundation.arrangement", bench = { n ->
    val out = IntArray(3)
    repeat(n) { with(Arrangement.spacedBy(8.dp)) { Density(2f).arrange(300, intArrayOf(10, 20, 30), LayoutDirection.Ltr, out) } }
    out[2]
  }) {
    val out = IntArray(3)
    with(Arrangement.SpaceBetween) { Density(1f).arrange(100, intArrayOf(10, 20, 30), LayoutDirection.Ltr, out) }
    "spacedBy=${Arrangement.spacedBy(8.dp)} between=${out.toList()} start=${Arrangement.Start} " +
      "eq=${Arrangement.spacedBy(8.dp) == Arrangement.spacedBy(8.dp)} spacing=${Arrangement.spacedBy(8.dp).spacing}"
  },
  Case("foundation.padding") {
    val p = PaddingValues(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 0.dp)
    "padding=$p left=${p.calculateLeftPadding(LayoutDirection.Rtl)} top=${p.calculateTopPadding()} " +
      "eq=${PaddingValues(16.dp) == PaddingValues(16.dp)} border=${BorderStroke(1.dp, Color.Red)}"
  },
  Case("foundation.roundedShape", bench = { n ->
    var s = RoundedCornerShape(0.dp)
    repeat(n) { s = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) }
    s
  }) {
    val r = RoundedCornerShape(16.dp)
    val pct = RoundedCornerShape(50)
    val outline = r.createOutline(Size(100f, 40f), LayoutDirection.Ltr, Density(2f))
    "shape=$r corner=${r.topStart} pct=${pct.topStart} circle=${CircleShape} eq=${RoundedCornerShape(16.dp) == r} " +
      "cornerPx=${r.topStart.toPx(Size(100f, 40f), Density(2f))} outline=$outline"
  },
  // Constructing a CutCornerShape is fine; its outline is a Path (skia set).
  Case("foundation.cutCornerValue") {
    val c = CutCornerShape(8.dp)
    "shape=$c corner=${c.topStart} eq=${c == CutCornerShape(8.dp)}"
  },
)
