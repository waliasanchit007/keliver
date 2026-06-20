/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.animation.AnimatedVisibility as M3AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.keliver.material.widget.AnimatedVisibility
import dev.keliver.material.widget.Clickable
import dev.keliver.material.widget.Shimmer
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren
import dev.keliver.Modifier as RedwoodModifier

internal class ComposeUiShimmer : Shimmer<@Composable (Modifier) -> Unit> {
  private var widthDp by mutableStateOf(0)
  private var heightDp by mutableStateOf(16)
  private var cornerRadiusDp by mutableStateOf(8)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { incoming ->
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
      initialValue = -400f,
      targetValue = 800f,
      animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
      label = "x",
    )
    val brush = Brush.linearGradient(
      colors = listOf(Color(0xFFE7E9EC), Color(0xFFF3F5F7), Color(0xFFE7E9EC)),
      start = Offset(x, 0f),
      end = Offset(x + 400f, 0f),
    )
    var m = incoming
    m = if (widthDp > 0) m.width(widthDp.dp) else m.fillMaxWidth()
    m = m.height(heightDp.dp).clip(RoundedCornerShape(cornerRadiusDp.dp)).background(brush)
    Box(modifier = m)
  }
  override fun widthDp(widthDp: Int) { this.widthDp = widthDp }
  override fun heightDp(heightDp: Int) { this.heightDp = heightDp }
  override fun cornerRadiusDp(cornerRadiusDp: Int) { this.cornerRadiusDp = cornerRadiusDp }
}

internal class ComposeUiClickable : Clickable<@Composable (Modifier) -> Unit> {
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override val content: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    val cb = onClick
    Box(modifier = if (cb != null) m.clickable { cb() } else m) {
      (content as ComposeWidgetChildren).Render()
    }
  }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiAnimatedVisibility : AnimatedVisibility<@Composable (Modifier) -> Unit> {
  private var visible by mutableStateOf(true)
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3AnimatedVisibility(
      visible = visible,
      modifier = m,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      (children as ComposeWidgetChildren).Render()
    }
  }
  override fun visible(visible: Boolean) { this.visible = visible }
}
