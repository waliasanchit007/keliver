/*
 * Copyright (C) 2021 Square, Inc.
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
package dev.keliver.widget.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.widget.Widget
import kotlin.jvm.JvmOverloads

/**
 * Pluggable translator: turns a widget's keliver [KeliverModifier] chain into a
 * real Compose [Modifier], so UNIVERSAL visual modifiers (background, corner,
 * border, shadow, padding, …) compose on ANY widget — the keliver answer to
 * native `Modifier.background().clip().padding()`. A widget set installs its
 * applier (e.g. keliver-material's `applyKeliverVisuals`) when its widget system
 * is created; until then it's identity (a no-op). Applied centrally here so no
 * per-widget renderer needs to know about modifiers.
 */
public var keliverVisualModifierApplier: (Modifier, KeliverModifier) -> Modifier = { m, _ -> m }

public class ComposeWidgetChildren @JvmOverloads constructor(
  private val onModifierUpdated: () -> Unit = {},
) : Widget.Children<@Composable (Modifier) -> Unit> {
  private var modifierTick by mutableIntStateOf(0)

  private val _widgets = mutableStateListOf<Widget<@Composable (Modifier) -> Unit>>()
  override val widgets: List<Widget<@Composable (Modifier) -> Unit>> get() = _widgets

  @Composable
  public fun Render() {
    // Observe the modifier count so we recompose if a child's modifier changes.
    modifierTick

    for (index in _widgets.indices) {
      val widget = _widgets[index]
      _widgets[index].value(keliverVisualModifierApplier(Modifier, widget.modifier))
    }
  }

  override fun insert(index: Int, widget: Widget<@Composable (Modifier) -> Unit>) {
    _widgets.add(index, widget)
  }

  override fun move(fromIndex: Int, toIndex: Int, count: Int) {
    _widgets.move(fromIndex, toIndex, count)
  }

  override fun remove(index: Int, count: Int) {
    _widgets.remove(index, count)
  }

  override fun onModifierUpdated(index: Int, widget: Widget<@Composable (Modifier) -> Unit>) {
    modifierTick++
    onModifierUpdated.invoke()
  }

  override fun detach() {
  }
}
