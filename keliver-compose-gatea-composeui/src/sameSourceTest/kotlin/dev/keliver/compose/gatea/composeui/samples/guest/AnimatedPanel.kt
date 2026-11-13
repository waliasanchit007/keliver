/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.compose.gatea.composeui.samples.guest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.keliver.compose.animation.AnimatedVisibility
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
import dev.keliver.compose.foundation.layout.padding
import dev.keliver.compose.foundation.layout.size
import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.material3.Text
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.draw.alpha
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.dp

// Gate B-e's animation sample (docs/GATE_B_RESULTS.md): the native copy differs only in its imports.

/** Host-side animation: `animate*AsState` (animated references), `AnimatedVisibility`, `animateContentSize`. */
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun AnimatedPanel(expanded: Boolean) {
  val alpha by animateFloatAsState(if (expanded) 1f else 0.4f, tween(durationMillis = 400, easing = LinearEasing), label = "alpha")
  val size by animateDpAsState(if (expanded) 96.dp else 48.dp, tween(durationMillis = 400, easing = LinearEasing), label = "size")
  val color by animateColorAsState(if (expanded) Color(0xFF6750A4) else Color(0xFF7D5260), tween(durationMillis = 400, easing = LinearEasing), label = "color")
  Column(Modifier.size(200.dp, 240.dp).padding(16.dp)) {
    Box(Modifier.size(size).alpha(alpha).background(color, RoundedCornerShape(12.dp)))
    AnimatedVisibility(expanded) {
      Text("Shown when expanded")
    }
    Text(if (expanded) "Two\nlines" else "One line", Modifier.animateContentSize())
  }
}

/** Guest-side animation: `Animatable` in a `LaunchedEffect`, on the guest's frame clock. */
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun FrameClockPanel(expanded: Boolean) {
  val alpha = remember { Animatable(0.4f) }
  val size = remember { Animatable(48f) }
  LaunchedEffect(expanded) {
    alpha.animateTo(if (expanded) 1f else 0.4f, tween(durationMillis = 400, easing = LinearEasing))
  }
  LaunchedEffect(expanded) {
    size.animateTo(if (expanded) 96f else 48f, tween(durationMillis = 400, easing = LinearEasing))
  }
  Column(Modifier.size(200.dp, 240.dp).padding(16.dp)) {
    Box(Modifier.size(size.value.dp).alpha(alpha.value).background(Color(0xFF6750A4), RoundedCornerShape(12.dp)))
  }
}
