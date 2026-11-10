/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.gatea.composeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.keliverVisualModifierApplier

/** A generated renderer: it applies its own generated modifiers and serves lambda modifiers' events. */
public interface GeneratedWidget {
  public var modifierEventSink: ((Int) -> Unit)?
}

/** A property the guest has not sent yet (Redwood sends every property before the first frame). */
internal object Unset

/**
 * A generated container's children (docs/COMPOSE_GENERATOR_DESIGN.md §9). Each child's scoped
 * modifiers (`weight`, `align`) are applied with the parent's real scope as receiver; a generated
 * child applies its own unscoped modifiers, and a legacy child gets the legacy visual modifiers,
 * as `ComposeWidgetChildren` gives them.
 */
public class ScopedChildren : Widget.Children<@Composable (Modifier) -> Unit> {
  private var modifierTick by mutableIntStateOf(0)
  private val _widgets = mutableStateListOf<Widget<@Composable (Modifier) -> Unit>>()
  override val widgets: List<Widget<@Composable (Modifier) -> Unit>> get() = _widgets

  @Composable
  public fun Render(scope: Any?) {
    modifierTick
    for (index in _widgets.indices) {
      val widget = _widgets[index]
      key(widget) {
        val scoped = Modifier.applyScoped(widget.modifier, scope)
        if (widget is GeneratedWidget) {
          widget.value(scoped)
        } else {
          widget.value(keliverVisualModifierApplier(scoped, widget.modifier))
        }
      }
    }
  }

  override fun insert(index: Int, widget: Widget<@Composable (Modifier) -> Unit>) {
    _widgets.add(index, widget)
  }

  override fun move(fromIndex: Int, toIndex: Int, count: Int) {
    val moved = (0 until count).map { _widgets.removeAt(fromIndex) }
    val target = if (toIndex > fromIndex) toIndex - count else toIndex
    _widgets.addAll(target, moved)
  }

  override fun remove(index: Int, count: Int) {
    repeat(count) { _widgets.removeAt(index) }
  }

  override fun onModifierUpdated(index: Int, widget: Widget<@Composable (Modifier) -> Unit>) {
    modifierTick++
  }

  override fun detach() {}
}

@Suppress("unused")
private val keepImport: KeliverModifier = KeliverModifier
