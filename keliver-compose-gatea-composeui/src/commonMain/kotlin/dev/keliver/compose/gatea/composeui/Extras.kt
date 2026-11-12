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
import dev.keliver.compose.gatea.widget.ExtraLazyItem
import dev.keliver.compose.gatea.widget.ExtraShimmer
import dev.keliver.compose.ui.graphics.RectangleShape as K_RectangleShape
import dev.keliver.compose.ui.graphics.Shape as K_Shape

/**
 * One item of a generated lazy list's window (design §16): structure, not a widget a screen names.
 * The list's renderer draws [content] inside the real `LazyListScope.items` lambda; [key] is the
 * guest's item key, typed (`s:…`, `i:…`), so the host's item keeps its state across window moves.
 */
internal class ComposeUiExtraLazyItem :
  ExtraLazyItem<@Composable (Modifier) -> Unit>,
  GeneratedWidget {
  override var modifier: KeliverModifier = KeliverModifier
  override var modifierEventSink: ((Int) -> Unit)? = null
  var key: String? = null
    private set

  override fun key(key: String?) {
    this.key = key
  }

  override val content: ScopedChildren = ScopedChildren()

  /** Only if a guest places an item outside a lazy list: its children, unscoped. */
  override val value: @Composable (Modifier) -> Unit = { content.Render(null) }
}

/**
 * The one hand-written renderer: `dev.keliver.compose.extras.Shimmer`, a Keliver extra (not
 * Compose API). Same animation and colours as keliver-material's Shimmer.
 */
internal class ComposeUiExtraShimmer :
  ExtraShimmer<@Composable (Modifier) -> Unit>,
  GeneratedWidget {
  override var modifier: KeliverModifier by mutableStateOf<KeliverModifier>(KeliverModifier)
  override var modifierEventSink: ((Int) -> Unit)? = null
  private var shape: K_Shape by mutableStateOf(K_RectangleShape)
  override fun shape(shape: K_Shape) {
    this.shape = shape
  }
  override fun modifierEvent(modifierEvent: ((Int) -> Unit)?) {
    modifierEventSink = modifierEvent
  }

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
