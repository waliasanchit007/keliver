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
package dev.keliver.compose.gatea.composeui.samples.native

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

/** A modifier inserted ahead of an animated one while it animates. */
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun InsertedModifierPanel(expanded: Boolean, inset: Boolean) {
  val alpha by animateFloatAsState(if (expanded) 1f else 0.2f, tween(durationMillis = 400, easing = LinearEasing), label = "alpha")
  Column(Modifier.size(200.dp, 240.dp).padding(16.dp)) {
    Box(Modifier.size(96.dp).then(if (inset) Modifier.padding(8.dp) else Modifier).alpha(alpha).background(Color(0xFF6750A4)))
  }
}
