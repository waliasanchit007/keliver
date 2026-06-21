/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.AnimatedBorder
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren

internal class ComposeUiAnimatedBorder : AnimatedBorder<@Composable (Modifier) -> Unit> {
  private var cornerRadiusDp by mutableStateOf(8)
  private var strokeWidthDp by mutableStateOf(1)
  private var baseColorArgb by mutableStateOf(0)
  private var cometColorArgb by mutableStateOf(0xFFFC14AB.toInt())
  private var durationMs by mutableStateOf(1800)
  private var segmentLenDp by mutableStateOf(50)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  private var effect by mutableStateOf(0)
  private var colorsArgb by mutableStateOf<List<Int>>(emptyList())
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier

  override val value: @Composable (Modifier) -> Unit = { incoming ->
    val shape = RoundedCornerShape(cornerRadiusDp.dp)
    val comet = Color(cometColorArgb)
    val base = if (baseColorArgb != 0) Color(baseColorArgb) else comet.copy(alpha = 0.15f)
    val palette = if (colorsArgb.size >= 2) colorsArgb.map { Color(it) } else listOf(comet, comet)
    val transition = rememberInfiniteTransition(label = "anim_border")

    var m = incoming.clip(shape)
    onClick?.let { cb -> m = m.clickable { cb() } }
    m = when (effect) {
      1 -> { // gradientSweep — rotating gradient ring ("AI glow")
        val angle by transition.animateFloat(
          0f, 360f, infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart), "sweep",
        )
        m.drawWithCache {
          val sw = strokeWidthDp.dp.toPx()
          val path = roundedPath(size, sw / 2f, cornerRadiusDp.dp.toPx())
          val center = Offset(size.width / 2f, size.height / 2f)
          onDrawWithContent {
            drawContent()
            drawPath(path, color = base, style = Stroke(width = sw))
            rotate(angle, center) {
              drawPath(path, brush = Brush.sweepGradient(palette, center), style = Stroke(width = sw, cap = StrokeCap.Round))
            }
          }
        }
      }
      2 -> { // pulse — ring breathes in width + alpha
        val p by transition.animateFloat(
          0f, 1f, infiniteRepeatable(tween(durationMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), "pulse",
        )
        m.drawWithCache {
          val sw = strokeWidthDp.dp.toPx()
          val cr = cornerRadiusDp.dp.toPx()
          onDrawWithContent {
            drawContent()
            val w = sw * (1f + p * 1.5f)
            drawPath(roundedPath(size, w / 2f, cr), color = comet.copy(alpha = 0.3f + 0.7f * p), style = Stroke(width = w))
          }
        }
      }
      3 -> { // marchingAnts — dashed ring marches around
        val phase by transition.animateFloat(
          0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart), "ants",
        )
        m.drawWithCache {
          val sw = strokeWidthDp.dp.toPx()
          val dash = segmentLenDp.dp.toPx().coerceAtLeast(4f)
          val path = roundedPath(size, sw / 2f, cornerRadiusDp.dp.toPx())
          onDrawWithContent {
            drawContent()
            drawPath(
              path, color = comet,
              style = Stroke(width = sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), phase * dash * 2f)),
            )
          }
        }
      }
      4 -> { // glow — soft pulsing halo behind a crisp ring
        val p by transition.animateFloat(
          0f, 1f, infiniteRepeatable(tween(durationMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), "glow",
        )
        m.drawWithCache {
          val sw = strokeWidthDp.dp.toPx()
          val path = roundedPath(size, sw / 2f, cornerRadiusDp.dp.toPx())
          onDrawWithContent {
            drawContent()
            drawPath(path, color = comet.copy(alpha = 0.08f + 0.25f * p), style = Stroke(width = sw * (3f + 6f * p)))
            drawPath(path, color = comet, style = Stroke(width = sw))
          }
        }
      }
      else -> { // 0 comet — bright segment travels the perimeter
        // Head leads (fast-out-slow-in), tail trails (linear-out-slow-in).
        val headProgress by transition.animateFloat(
          0f, 1f, infiniteRepeatable(tween(durationMs, easing = FastOutSlowInEasing), RepeatMode.Restart), "head",
        )
        val tailProgress by transition.animateFloat(
          0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearOutSlowInEasing), RepeatMode.Restart), "tail",
        )
        val gradientComet = colorsArgb.size >= 2
        m.drawWithCache {
          val sw = strokeWidthDp.dp.toPx()
          val fullPath = roundedPath(size, sw / 2f, cornerRadiusDp.dp.toPx())
          val measure = PathMeasure().apply { setPath(fullPath, true) }
          val totalLen = measure.length
          val minSegmentPx = segmentLenDp.dp.toPx()
          val head = headProgress * totalLen
          var tail = tailProgress * totalLen
          val rawLen = if (head >= tail) head - tail else (totalLen - tail) + head
          if (rawLen < minSegmentPx) tail = (head - minSegmentPx + totalLen) % totalLen
          val segPath = Path()
          if (head >= tail) {
            measure.getSegment(tail, head, segPath, true)
          } else {
            measure.getSegment(tail, totalLen, segPath, true)
            measure.getSegment(0f, head, segPath, true)
          }
          onDrawWithContent {
            drawContent()
            drawPath(fullPath, color = base, style = Stroke(width = sw))
            if (gradientComet) {
              drawPath(segPath, brush = Brush.linearGradient(palette), style = Stroke(width = sw, cap = StrokeCap.Round))
            } else {
              drawPath(segPath, color = comet, style = Stroke(width = sw, cap = StrokeCap.Round))
            }
          }
        }
      }
    }
    Box(modifier = m, contentAlignment = Alignment.Center) {
      (children as ComposeWidgetChildren).Render()
    }
  }

  override fun cornerRadiusDp(cornerRadiusDp: Int) { this.cornerRadiusDp = cornerRadiusDp }
  override fun strokeWidthDp(strokeWidthDp: Int) { this.strokeWidthDp = strokeWidthDp }
  override fun baseColorArgb(baseColorArgb: Int) { this.baseColorArgb = baseColorArgb }
  override fun cometColorArgb(cometColorArgb: Int) { this.cometColorArgb = cometColorArgb }
  override fun durationMs(durationMs: Int) { this.durationMs = durationMs }
  override fun segmentLenDp(segmentLenDp: Int) { this.segmentLenDp = segmentLenDp }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
  override fun effect(effect: Int) { this.effect = effect }
  override fun colorsArgb(colorsArgb: List<Int>) { this.colorsArgb = colorsArgb }
}

/** Rounded-rect perimeter [Path], inset by half the stroke so it isn't clipped. */
private fun roundedPath(size: Size, inset: Float, cornerPx: Float): Path = Path().apply {
  addRoundRect(
    RoundRect(
      left = inset, top = inset,
      right = size.width - inset, bottom = size.height - inset,
      cornerRadius = CornerRadius(cornerPx, cornerPx),
    ),
  )
}
