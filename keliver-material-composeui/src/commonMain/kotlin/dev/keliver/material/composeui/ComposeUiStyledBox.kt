/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren
import dev.keliver.material.widget.StyledBox
import dev.keliver.Modifier as RedwoodModifier

internal class ComposeUiStyledBox : StyledBox<@Composable (Modifier) -> Unit> {
  private var colorArgb by mutableStateOf(0)
  private var gradientStartArgb by mutableStateOf(0)
  private var gradientEndArgb by mutableStateOf(0)
  private var cornerRadiusDp by mutableStateOf(0)
  private var widthDp by mutableStateOf(0)
  private var heightDp by mutableStateOf(0)
  private var paddingDp by mutableStateOf(0)
  private var fillWidth by mutableStateOf(false)
  private var contentAlignment by mutableStateOf(0)
  private var elevationDp by mutableStateOf(0)
  private var borderColorArgb by mutableStateOf(0)
  private var borderWidthDp by mutableStateOf(0)
  private var offsetYDp by mutableStateOf(0)
  private var gradientVertical by mutableStateOf(false)
  private var gradientColorsArgb by mutableStateOf<List<Int>>(emptyList())
  private var gradientStops by mutableStateOf<List<Float>>(emptyList())
  private var gradientDirection by mutableStateOf(0)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)

  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier

  override val value: @Composable (Modifier) -> Unit = { incoming ->
    var m = incoming
    // Draw-offset (e.g. a rounded sheet lapping up over a header). Applied first
    // so the whole box (shadow/bg/border) shifts together.
    if (offsetYDp != 0) m = m.offset(y = offsetYDp.dp)
    if (fillWidth) m = m.fillMaxWidth()
    if (widthDp > 0) m = m.width(widthDp.dp)
    if (heightDp > 0) m = m.height(heightDp.dp)
    val shape = RoundedCornerShape(cornerRadiusDp.dp)
    // shadow() must precede clip()/background() so the drop-shadow renders.
    if (elevationDp > 0) m = m.shadow(elevationDp.dp, shape, clip = false)
    if (cornerRadiusDp > 0) m = m.clip(shape)
    val hasMultiStop = gradientColorsArgb.size >= 2
    val has2Stop = gradientStartArgb != 0 || gradientEndArgb != 0
    m = when {
      hasMultiStop -> m.background(buildGradient(gradientColorsArgb, gradientStops, gradientDirection))
      has2Stop -> {
        val cols = listOf(Color(gradientStartArgb), Color(gradientEndArgb))
        m.background(if (gradientVertical) Brush.verticalGradient(cols) else Brush.linearGradient(cols))
      }
      colorArgb != 0 -> m.background(Color(colorArgb))
      else -> m
    }
    if (borderWidthDp > 0 && borderColorArgb != 0) {
      m = m.border(borderWidthDp.dp, Color(borderColorArgb), shape)
    }
    onClick?.let { cb -> m = m.clickable { cb() } }
    if (paddingDp > 0) m = m.padding(paddingDp.dp)
    Box(modifier = m, contentAlignment = toAlignment(contentAlignment)) {
      (children as ComposeWidgetChildren).Render()
    }
  }

  override fun colorArgb(colorArgb: Int) { this.colorArgb = colorArgb }
  override fun gradientStartArgb(gradientStartArgb: Int) { this.gradientStartArgb = gradientStartArgb }
  override fun gradientEndArgb(gradientEndArgb: Int) { this.gradientEndArgb = gradientEndArgb }
  override fun cornerRadiusDp(cornerRadiusDp: Int) { this.cornerRadiusDp = cornerRadiusDp }
  override fun widthDp(widthDp: Int) { this.widthDp = widthDp }
  override fun heightDp(heightDp: Int) { this.heightDp = heightDp }
  override fun paddingDp(paddingDp: Int) { this.paddingDp = paddingDp }
  override fun fillWidth(fillWidth: Boolean) { this.fillWidth = fillWidth }
  override fun contentAlignment(contentAlignment: Int) { this.contentAlignment = contentAlignment }
  override fun elevationDp(elevationDp: Int) { this.elevationDp = elevationDp }
  override fun offsetYDp(offsetYDp: Int) { this.offsetYDp = offsetYDp }
  override fun gradientVertical(gradientVertical: Boolean) { this.gradientVertical = gradientVertical }
  override fun gradientColorsArgb(gradientColorsArgb: List<Int>) { this.gradientColorsArgb = gradientColorsArgb }
  override fun gradientStops(gradientStops: List<Float>) { this.gradientStops = gradientStops }
  override fun gradientDirection(gradientDirection: Int) { this.gradientDirection = gradientDirection }
  override fun borderColorArgb(borderColorArgb: Int) { this.borderColorArgb = borderColorArgb }
  override fun borderWidthDp(borderWidthDp: Int) { this.borderWidthDp = borderWidthDp }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

/**
 * Build a multi-stop linear gradient. [direction]: 0 diagonal TL→BR, 1 vertical
 * T→B, 2 horizontal L→R, 3 diagonal BL→TR. Compose treats `Float.POSITIVE_INFINITY`
 * offset components as the drawn size, so the diagonals span the full box.
 */
internal fun buildGradient(colorsArgb: List<Int>, stops: List<Float>, direction: Int): Brush {
  val colors = colorsArgb.map { Color(it) }
  val inf = Float.POSITIVE_INFINITY
  val (start, end) = when (direction) {
    1 -> Offset(0f, 0f) to Offset(0f, inf)        // vertical T→B
    2 -> Offset(0f, 0f) to Offset(inf, 0f)        // horizontal L→R
    3 -> Offset(0f, inf) to Offset(inf, 0f)       // diagonal BL→TR
    else -> Offset(0f, 0f) to Offset(inf, inf)    // diagonal TL→BR
  }
  return if (stops.size == colors.size && stops.isNotEmpty()) {
    Brush.linearGradient(*Array(colors.size) { stops[it] to colors[it] }, start = start, end = end)
  } else {
    Brush.linearGradient(colors, start = start, end = end)
  }
}

private fun toAlignment(i: Int): Alignment = when (i) {
  1 -> Alignment.TopCenter
  2 -> Alignment.TopEnd
  3 -> Alignment.CenterStart
  4 -> Alignment.Center
  5 -> Alignment.CenterEnd
  6 -> Alignment.BottomStart
  7 -> Alignment.BottomCenter
  8 -> Alignment.BottomEnd
  else -> Alignment.TopStart
}
