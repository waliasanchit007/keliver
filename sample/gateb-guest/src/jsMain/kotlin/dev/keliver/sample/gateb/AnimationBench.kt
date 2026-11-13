/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.gateb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.compose.animation.AnimatedVisibility
import dev.keliver.compose.animation.Animatable
import dev.keliver.compose.animation.animateColorAsState
import dev.keliver.compose.animation.animateContentSize
import dev.keliver.compose.animation.core.Animatable
import dev.keliver.compose.animation.core.LinearEasing
import dev.keliver.compose.animation.core.animateDpAsState
import dev.keliver.compose.animation.core.animateFloatAsState
import dev.keliver.compose.animation.core.tween
import dev.keliver.compose.foundation.background
import dev.keliver.compose.foundation.layout.Box
import dev.keliver.compose.foundation.layout.Column
import dev.keliver.compose.foundation.layout.Row
import dev.keliver.compose.foundation.layout.padding
import dev.keliver.compose.foundation.layout.size
import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.material3.Text
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.draw.alpha
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Gate B-e's benchmark screen (docs/GATE_B_RESULTS.md), written once as plain Compose. The guest
// compiles it against the generated API; the Android host compiles a copy whose imports say
// `androidx.compose.` instead of `dev.keliver.compose.` (sample/host-android/build.gradle.kts).

private const val ROWS = 4
private const val COLUMNS = 3
private const val PERIOD_MILLIS = 700L
private const val DURATION_MILLIS = 600

private val colors = listOf(Color(0xFF6750A4), Color(0xFF7D5260), Color(0xFF386A20))
private val accents = listOf(Color(0xFFE8DEF8), Color(0xFFFFD8E4), Color(0xFFB7F397))

/**
 * Twelve boxes whose alpha, size and colour animate back and forth every [PERIOD_MILLIS], and a
 * panel that appears (`AnimatedVisibility`) and a text that grows (`animateContentSize`).
 *
 * - `host`: `animate*AsState`. In the guest these are animated references: the host animates.
 * - `guest`: `Animatable.animateTo` in a `LaunchedEffect`, on the guest's frame clock: each frame
 *   is a new value in the guest, and a change to the host.
 */
@Composable
fun AnimationBench(mode: String) {
  var flip by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(PERIOD_MILLIS)
      flip = !flip
    }
  }
  Column(Modifier.padding(16.dp)) {
    Text("mode: $mode")
    for (row in 0 until ROWS) {
      Row(Modifier.padding(4.dp)) {
        for (column in 0 until COLUMNS) {
          val index = row * COLUMNS + column
          Box(Modifier.size(96.dp).padding(4.dp)) {
            if (mode == "guest") FrameClockBox(flip, index) else StateBox(flip, index)
          }
        }
      }
    }
    AnimatedVisibility(flip) {
      Text("Shown on every other period")
    }
    Text(if (flip) "Two\nlines" else "One line", Modifier.animateContentSize())
  }
}

@Composable
private fun StateBox(flip: Boolean, index: Int) {
  val alpha by animateFloatAsState(if (flip) 1f else 0.3f, tween(durationMillis = DURATION_MILLIS, easing = LinearEasing), label = "alpha")
  val size by animateDpAsState(if (flip) 80.dp else 40.dp, tween(durationMillis = DURATION_MILLIS, easing = LinearEasing), label = "size")
  val color by animateColorAsState(
    if (flip) colors[index % colors.size] else accents[index % accents.size],
    tween(durationMillis = DURATION_MILLIS, easing = LinearEasing),
    label = "color",
  )
  Box(Modifier.size(size).alpha(alpha).background(color, RoundedCornerShape(8.dp)))
}

@Composable
private fun FrameClockBox(flip: Boolean, index: Int) {
  val alpha = remember { Animatable(0.3f) }
  val size = remember { Animatable(40f) }
  val color = remember { Animatable(accents[index % accents.size]) }
  LaunchedEffect(flip) {
    launch { alpha.animateTo(if (flip) 1f else 0.3f, tween(durationMillis = DURATION_MILLIS, easing = LinearEasing)) }
    launch { size.animateTo(if (flip) 80f else 40f, tween(durationMillis = DURATION_MILLIS, easing = LinearEasing)) }
    color.animateTo(if (flip) colors[index % colors.size] else accents[index % accents.size], tween(durationMillis = DURATION_MILLIS, easing = LinearEasing))
  }
  Box(Modifier.size(size.value.dp).alpha(alpha.value).background(color.value, RoundedCornerShape(8.dp)))
}
