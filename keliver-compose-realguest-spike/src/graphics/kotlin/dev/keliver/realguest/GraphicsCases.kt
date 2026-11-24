/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradient
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** ui-graphics: Color, Brush, Shape, Outline, path data. */
fun graphicsCases(): List<Case> = listOf(
  Case("graphics.color", bench = { n ->
    var c = Color.Red
    repeat(n) { c = lerp(c, Color.Blue, 0.01f).copy(alpha = 0.9f) }
    c
  }) {
    val c = Color(0xFF336699)
    val half = Color.Red.copy(alpha = 0.5f)
    "argb=${c.toArgb().toUInt().toString(16)} red=${c.red} lum=${c.luminance()} half=$half " +
      "lerp=${lerp(Color.Red, Color.Blue, 0.5f)} over=${half.compositeOver(Color.White)} " +
      "eq=${Color(0xFFFF0000) == Color.Red} hsv=${Color.hsv(210f, 0.5f, 0.6f)} space=${c.colorSpace.name}"
  },
  // Theme references as the mirror does them (design §6: a colour-space id Compose never assigns).
  // The real Color's public constructor takes any 64-bit value: what do reads of such a value do?
  Case("graphics.colorRefTrick") {
    val ref = Color((40uL and 0x3fuL) or (7uL shl 16))
    val out = StringBuilder("value=${ref.value} ")
    out.append(runCatching { "space=${ref.colorSpace.name}" }.getOrElse { "space!${it::class.simpleName}" }).append(' ')
    out.append(runCatching { "alpha=${ref.alpha}" }.getOrElse { "alpha!${it::class.simpleName}" }).append(' ')
    out.append(runCatching { "copy=${ref.copy(alpha = 0.5f).value}" }.getOrElse { "copy!${it::class.simpleName}" }).append(' ')
    out.append(runCatching { "lum=${ref.luminance()}" }.getOrElse { "lum!${it::class.simpleName}" })
    out.toString()
  },
  // An alternative tag for theme references on the REAL Color: an sRGB colour keeps its ARGB in the
  // high 32 bits and colour-space id 0 in the low 6; bits 6..31 are unused. Do reads ignore them?
  Case("graphics.colorLowBitTag") {
    val plain = Color(0xFF6750A4)
    val tagged = Color(plain.value or (5uL shl 6))
    val tag = ((tagged.value shr 6) and 0x3FFFFFFuL).toInt()
    "tag=$tag space=${tagged.colorSpace.name} rgba=${tagged.red},${tagged.green},${tagged.blue},${tagged.alpha} " +
      "argbSame=${tagged.toArgb() == plain.toArgb()} lumSame=${tagged.luminance() == plain.luminance()} " +
      "eqPlain=${tagged == plain} copyKeepsTag=${((tagged.copy(alpha = 0.5f).value shr 6) and 0x3FFFFFFuL) != 0uL} " +
      "copySameAsPlainCopy=${tagged.copy(alpha = 0.5f) == plain.copy(alpha = 0.5f)} over=${tagged.compositeOver(Color.White) == plain.compositeOver(Color.White)} " +
      "lerp=${lerp(tagged, Color.White, 0.5f) == lerp(plain, Color.White, 0.5f)} str=$tagged"
  },
  Case("graphics.colorCopyAlpha", bench = { n ->
    var c = Color(0xFF6750A4)
    repeat(n) { c = c.copy(alpha = 0.38f) }
    c
  }) { "copy=${Color(0xFF6750A4).copy(alpha = 0.38f)}" },
  Case("graphics.colorLiteral", bench = { n ->
    var x = 0
    repeat(n) { x += Color(0xFF000000 + it).toArgb() }
    x
  }) { "argb=${Color(0xFF6750A4).toArgb()}" },
  Case("graphics.colorLerp", bench = { n ->
    var c = Color.Red
    repeat(n) { c = lerp(Color.Red, Color.Blue, (it % 100) / 100f) }
    c
  }) { "lerp=${lerp(Color.Red, Color.Blue, 0.5f)}" },
  Case("graphics.brush", bench = { n ->
    var b: Brush = SolidColor(Color.Red)
    repeat(n) { b = Brush.linearGradient(listOf(Color.Red, Color.Blue), Offset.Zero, Offset(100f, 0f)) }
    b
  }) {
    val b = Brush.linearGradient(listOf(Color.Red, Color.Blue), start = Offset.Zero, end = Offset(100f, 0f))
    val same = Brush.linearGradient(listOf(Color.Red, Color.Blue), start = Offset.Zero, end = Offset(100f, 0f))
    val v = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent)
    "brush=$b eq=${b == same} isLinear=${b is LinearGradient} intrinsic=${b.intrinsicSize} vertical=$v " +
      "radial=${Brush.radialGradient(listOf(Color.Red, Color.Blue))} sweep=${Brush.sweepGradient(listOf(Color.Red, Color.Blue))} " +
      "tile=${TileMode.Clamp}"
  },
  Case("graphics.shapeOutline") {
    val o = RectangleShape.createOutline(Size(10f, 20f), LayoutDirection.Ltr, Density(2f))
    "outline=$o rect=${(o as Outline.Rectangle).rect} shadow=${Shadow(Color.Black, Offset(1f, 2f), 3f)}"
  },
  Case("graphics.pathData", bench = { n ->
    var size = 0
    repeat(n) { size += PathParser().parsePathString(SEARCH_PATH).toNodes().size }
    size
  }) {
    val nodes = PathParser().parsePathString(SEARCH_PATH).toNodes()
    "nodes=${nodes.size} first=${nodes.first()} last=${nodes.last()}"
  },
)

/** Material's "Search" icon path (filled, 24×24), as an ImageVector builder would read it. */
const val SEARCH_PATH =
  "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16" +
    "c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5" +
    " 14,7.01 14,9.5 11.99,14 9.5,14z"
