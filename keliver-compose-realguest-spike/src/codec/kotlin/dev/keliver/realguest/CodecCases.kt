/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.KeyframesSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * What a GENERATED generic codec could do with the real types: encode a value through its PUBLIC
 * API only (properties, or public functions it can probe), decode it with public constructors and
 * factories, and compare with the original (Compose's own equals). One line per type: `rt=true`
 * when the round trip is exact, and HOW the state was read:
 *  - props: public properties that match a public constructor's parameters (a rule a generator can
 *    derive from metadata);
 *  - probe: public functions called with chosen inputs (a per-type rule, written once);
 *  - none: the state is private or internal; only toString() shows it.
 */
private val sizeA = Size(100f, 100f)
private val sizeB = Size(200f, 200f)

private fun encodeCorner(c: CornerSize): CornerSize {
  val a1 = c.toPx(sizeA, Density(1f))
  val a2 = c.toPx(sizeA, Density(2f))
  val b1 = c.toPx(sizeB, Density(1f))
  return when {
    a2 == 2 * a1 && b1 == a1 -> CornerSize(a1.dp) // density-scaled, size-independent: dp
    b1 == 2 * a1 && a2 == a1 -> CornerSize((a1 / 100f * 100f).toInt()) // proportional to size: percent of 100
    else -> CornerSize(a1) // px
  }
}

private fun textUnitOf(t: TextUnit): TextUnit = if (t == TextUnit.Unspecified) t else TextUnit(t.value, t.type)

fun codecCases(): List<Case> = listOf(
  Case("codec.textStyle") {
    val s = TextStyle(color = Color(0xFF202124), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic, letterSpacing = 0.5.sp, lineHeight = 28.sp, textAlign = TextAlign.Center, textDecoration = TextDecoration.Underline, shadow = Shadow(Color.Black, Offset(1f, 2f), 3f))
    val d = TextStyle(
      color = Color(s.color.value),
      fontSize = textUnitOf(s.fontSize),
      fontWeight = s.fontWeight?.let { FontWeight(it.weight) },
      fontStyle = s.fontStyle?.let { st -> FontStyle.values().first { it == st } },
      letterSpacing = textUnitOf(s.letterSpacing),
      lineHeight = textUnitOf(s.lineHeight),
      textAlign = TextAlign.values().first { it.toString() == s.textAlign.toString() },
      textDecoration = s.textDecoration?.let { td -> listOf(TextDecoration.Underline, TextDecoration.LineThrough).filter { td.contains(it) }.let { TextDecoration.combine(it) } },
      shadow = s.shadow?.let { Shadow(Color(it.color.value), Offset(it.offset.x, it.offset.y), it.blurRadius) },
    )
    "rt=${d == s} via=props(+probe for TextDecoration, TextAlign by name)"
  },
  Case("codec.borderStroke") {
    val b = BorderStroke(1.dp, SolidColor(Color.Red))
    val d = BorderStroke(Dp(b.width.value), SolidColor(Color((b.brush as SolidColor).value.value)))
    "rt=${d == b} via=props"
  },
  Case("codec.alignment") {
    val a = Alignment.BottomEnd as BiasAlignment
    "rt=${BiasAlignment(a.horizontalBias, a.verticalBias) == a} via=props"
  },
  Case("codec.roundedShape") {
    val shapes = listOf(RoundedCornerShape(16.dp), RoundedCornerShape(50), RoundedCornerShape(4f), RoundedCornerShape(CornerSize(8.dp), CornerSize(0.dp), CornerSize(30), CornerSize(0.dp)))
    val ok = shapes.map { s -> RoundedCornerShape(encodeCorner(s.topStart), encodeCorner(s.topEnd), encodeCorner(s.bottomEnd), encodeCorner(s.bottomStart)) == s }
    "rt=${ok.all { it }} each=$ok via=props(corners)+probe(CornerSize: private classes; toPx at two densities and sizes)"
  },
  Case("codec.padding") {
    val p = PaddingValues(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 2.dp)
    val d = PaddingValues(p.calculateStartPadding(LayoutDirection.Ltr), p.calculateTopPadding(), p.calculateEndPadding(LayoutDirection.Ltr), p.calculateBottomPadding())
    val abs = PaddingValues.Absolute(left = 1.dp, right = 9.dp)
    val absAsRelative = PaddingValues(abs.calculateStartPadding(LayoutDirection.Ltr), 0.dp, abs.calculateEndPadding(LayoutDirection.Ltr), 0.dp)
    "rt=${d == p} absolute=${absAsRelative == abs} absRtlDiffers=${abs.calculateStartPadding(LayoutDirection.Rtl) != absAsRelative.calculateStartPadding(LayoutDirection.Rtl)} via=probe(calculate*; Absolute needs both directions)"
  },
  Case("codec.arrangement") {
    val plain = Arrangement.spacedBy(8.dp)
    val aligned = Arrangement.spacedBy(8.dp, Alignment.End)
    "plainRt=${Arrangement.spacedBy(plain.spacing) == plain} alignedRt=${Arrangement.spacedBy(aligned.spacing) == aligned} " +
      "alignedToString=$aligned via=props(spacing) + none(alignment is internal)"
  },
  Case("codec.springSpec") {
    val s = spring(dampingRatio = 0.5f, stiffness = 200f, visibilityThreshold = 0.01f) as SpringSpec<Float>
    "rt=${spring<Float>(s.dampingRatio, s.stiffness, s.visibilityThreshold) == s} via=props"
  },
  Case("codec.tweenSpec") {
    val named = tween<Float>(300, 50, FastOutSlowInEasing) as TweenSpec<Float>
    val custom = tween<Float>(300, 0, CubicBezierEasing(0.1f, 0.2f, 0.3f, 0.4f)) as TweenSpec<Float>
    val known = listOf(FastOutSlowInEasing, LinearEasing)
    val namedRt = tween<Float>(named.durationMillis, named.delay, known.first { it == named.easing }) == named
    "namedRt=$namedRt customEasingReadable=false customToString=${custom.easing} via=props + identity(named easings); CubicBezierEasing's points are private"
  },
  Case("codec.keyframes") {
    val k = keyframes<Float> { durationMillis = 300; 0.5f at 100 } as KeyframesSpec<Float>
    "durationMillis=${k.config.durationMillis} keyframesReadable=false via=none(the keyframe map is internal)"
  },
  Case("codec.linearGradient") {
    val b = Brush.linearGradient(listOf(Color.Red, Color.Blue), Offset.Zero, Offset(100f, 0f))
    "publicState=intrinsicSize:${b.intrinsicSize} colorsReadable=false toString=$b via=none(colors, stops, start, end, tileMode are private)"
  },
)
