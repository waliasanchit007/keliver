/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp

/** ui-unit, ui-geometry, ui-util: Dp, TextUnit, Offset, Size, Density. */
fun unitCases(): List<Case> = listOf(
  Case("unit.dp", bench = { n ->
    var d = 0.dp
    repeat(n) { d = (d + 1.dp) * 0.5f }
    d
  }) {
    val d = 16.dp
    val s = d + 4.dp
    "dp=$d sum=$s value=${s.value} times=${(d * 2).value} max=${max(d, 3.dp)} " +
      "unspecified=${Dp.Unspecified.isSpecified} lerp=${lerp(0.dp, 10.dp, 0.25f)} eq=${16.dp == Dp(16f)} " +
      "size=${DpSize(10.dp, 20.dp)} offset=${DpOffset(1.dp, 2.dp)}"
  },
  Case("unit.textUnit", bench = { n ->
    var t = 1.sp
    repeat(n) { t = (t * 1.0001f) }
    t
  }) {
    val t = 14.sp
    val e = 1.2.em
    "sp=$t value=${t.value} type=${t.type == TextUnitType.Sp} em=$e emType=${e.type == TextUnitType.Em} " +
      "unspecified=${TextUnit.Unspecified} isSp=${t.isSp} eq=${14.sp == TextUnit(14f, TextUnitType.Sp)}"
  },
  Case("geometry.offsetSize", bench = { n ->
    var o = Offset.Zero
    repeat(n) { o = (o + Offset(1f, 2f)) * 0.5f }
    o
  }) {
    val o = Offset(3f, 4f)
    val s = Size(10f, 20f)
    "dist=${o.getDistance()} plus=${o + Offset(1f, 1f)} min=${s.minDimension} center=${s.center} " +
      "rect=${Rect(Offset.Zero, s).contains(o)} unspecified=${Offset.Unspecified.isSpecified} eq=${Offset(3f, 4f) == o}"
  },
  Case("unit.density") {
    val density = Density(2.75f, 1.2f)
    with(density) {
      "px=${16.dp.toPx()} sp=${14.sp.toPx()} roundPx=${1.5.dp.roundToPx()} toDp=${44.toDp()} " +
        "intOffset=${IntOffset(3, 4) + IntOffset(1, 1)} intSize=${IntSize(10, 20)}"
    }
  },
)
