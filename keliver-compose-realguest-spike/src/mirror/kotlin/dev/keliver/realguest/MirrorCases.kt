/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import dev.keliver.compose.animation.core.Animatable
import dev.keliver.compose.animation.core.tween
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.text.TextStyle
import dev.keliver.compose.ui.text.font.FontWeight
import dev.keliver.compose.ui.unit.sp

/*
 * Today's hand-written guest mirror (keliver-compose-values, Gate B-e's guest-clock Animatable) on
 * the same frame driver and spec as anim.longTweenFrames: the per-frame cost to compare with the
 * real animation-core's.
 */
fun mirrorCases(): List<Case> = listOf(
  Case("mirror.longTweenFrames", bench = { n ->
    val f = Frames()
    val a = Animatable(0f)
    f.launch { a.animateTo(1f, tween(1_000_000)) }
    repeat(n) { f.frame() }
    f.job.cancel()
    f.dispatcher.drain()
    a.value
  }) {
    val f = Frames()
    val a = Animatable(0f)
    f.launch { a.animateTo(1f, tween(300)) }
    while (a.isRunning && f.frames < 100) f.frame()
    "frames=${f.frames} last=${a.value}"
  },
  // The same ops as graphics.colorCopyAlpha, graphics.colorLiteral and text.textStyle, on the mirrors.
  Case("mirror.colorCopyAlpha", bench = { n ->
    var c = Color(0xFF6750A4)
    repeat(n) { c = c.copy(alpha = 0.38f) }
    c
  }) { "copy=${Color(0xFF6750A4).copy(alpha = 0.38f)}" },
  Case("mirror.colorLiteral", bench = { n ->
    var x = 0
    repeat(n) { x += Color(0xFF000000 + it).argb }
    x
  }) { "argb=${Color(0xFF6750A4).argb}" },
  Case("mirror.textStyle", bench = { n ->
    var s = TextStyle()
    val bold = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
    repeat(n) { s = TextStyle(color = Color.Red, fontSize = 14.sp).merge(bold).copy(letterSpacing = 0.1.sp) }
    s
  }) { "eq700=${FontWeight.Bold == FontWeight(700)} styleEq=${TextStyle(fontWeight = FontWeight(700)) == TextStyle(fontWeight = FontWeight.Bold)}" },
)
