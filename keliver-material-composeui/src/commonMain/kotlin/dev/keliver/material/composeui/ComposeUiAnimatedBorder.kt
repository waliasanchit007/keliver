/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier

  override val value: @Composable (Modifier) -> Unit = { incoming ->
    val shape = RoundedCornerShape(cornerRadiusDp.dp)
    val comet = Color(cometColorArgb)
    val base = if (baseColorArgb != 0) Color(baseColorArgb) else comet.copy(alpha = 0.15f)

    // Head leaps ahead (fast-out-slow-in), tail trails (linear-out-slow-in); both
    // loop on the same duration so the lit segment circulates the perimeter.
    val transition = rememberInfiniteTransition(label = "border_travel")
    val headProgress by transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(durationMs, easing = FastOutSlowInEasing), RepeatMode.Restart),
      label = "border_head",
    )
    val tailProgress by transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearOutSlowInEasing), RepeatMode.Restart),
      label = "border_tail",
    )

    var m = incoming.clip(shape)
    onClick?.let { cb -> m = m.clickable { cb() } }
    m = m.drawWithCache {
      val strokeWidthPx = strokeWidthDp.dp.toPx()
      val cornerPx = cornerRadiusDp.dp.toPx()
      val inset = strokeWidthPx / 2f
      val roundRect = RoundRect(
        left = inset, top = inset,
        right = size.width - inset, bottom = size.height - inset,
        cornerRadius = CornerRadius(cornerPx, cornerPx),
      )
      val fullPath = Path().apply { addRoundRect(roundRect) }
      val measure = PathMeasure().apply { setPath(fullPath, true) }
      val totalLen = measure.length
      val minSegmentPx = segmentLenDp.dp.toPx()
      val head = headProgress * totalLen
      var tail = tailProgress * totalLen
      // Enforce a minimum visible segment between tail and head.
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
        drawPath(fullPath, color = base, style = Stroke(width = strokeWidthPx))
        drawPath(segPath, color = comet, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round))
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
}
