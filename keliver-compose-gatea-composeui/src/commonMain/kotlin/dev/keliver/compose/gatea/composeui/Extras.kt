/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.gatea.composeui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.compose.gatea.widget.ExtraShimmer
import dev.keliver.compose.ui.graphics.RectangleShape as K_RectangleShape
import dev.keliver.compose.ui.graphics.Shape as K_Shape

/**
 * The one hand-written renderer: `dev.keliver.compose.extras.Shimmer`, a Keliver extra (not
 * Compose API). Same animation and colours as keliver-material's Shimmer.
 */
internal class ComposeUiExtraShimmer : ExtraShimmer<@Composable (Modifier) -> Unit>, GeneratedWidget {
  override var modifier: KeliverModifier by mutableStateOf<KeliverModifier>(KeliverModifier)
  override var modifierEventSink: ((Int) -> Unit)? = null
  private var shape: K_Shape by mutableStateOf(K_RectangleShape)
  override fun shape(shape: K_Shape) { this.shape = shape }
  override fun modifierEvent(modifierEvent: ((Int) -> Unit)?) { modifierEventSink = modifierEvent }

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
    Box(incoming.applyGenerated(modifier, this).clip(shape.toCompose()).background(brush))
  }
}
