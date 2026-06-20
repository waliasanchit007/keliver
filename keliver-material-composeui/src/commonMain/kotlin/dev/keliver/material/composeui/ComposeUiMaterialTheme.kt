/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.keliver.material.widget.Theme
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren
import dev.keliver.Modifier as RedwoodModifier

/** Resolve a keliver color-role int to a Material3 scheme color (0 => Unspecified). */
@Composable
internal fun colorForRole(role: Int): Color = when (role) {
  1 -> MaterialTheme.colorScheme.primary
  2 -> MaterialTheme.colorScheme.onPrimary
  3 -> MaterialTheme.colorScheme.secondary
  4 -> MaterialTheme.colorScheme.surface
  5 -> MaterialTheme.colorScheme.onSurface
  6 -> MaterialTheme.colorScheme.onSurfaceVariant
  7 -> MaterialTheme.colorScheme.background
  8 -> MaterialTheme.colorScheme.error
  9 -> MaterialTheme.colorScheme.outline
  else -> Color.Unspecified
}

internal class ComposeUiTheme : Theme<@Composable (Modifier) -> Unit> {
  private var dark by mutableStateOf(false)
  private var primaryArgb by mutableStateOf(0)
  private var onPrimaryArgb by mutableStateOf(0)
  private var secondaryArgb by mutableStateOf(0)
  private var onSecondaryArgb by mutableStateOf(0)
  private var surfaceArgb by mutableStateOf(0)
  private var onSurfaceArgb by mutableStateOf(0)
  private var surfaceVariantArgb by mutableStateOf(0)
  private var onSurfaceVariantArgb by mutableStateOf(0)
  private var backgroundArgb by mutableStateOf(0)
  private var onBackgroundArgb by mutableStateOf(0)
  private var errorArgb by mutableStateOf(0)
  private var outlineArgb by mutableStateOf(0)
  override val content: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    val base = if (dark) darkColorScheme() else lightColorScheme()
    fun ov(argb: Int, default: Color) = if (argb != 0) Color(argb) else default
    val scheme = base.copy(
      primary = ov(primaryArgb, base.primary),
      onPrimary = ov(onPrimaryArgb, base.onPrimary),
      secondary = ov(secondaryArgb, base.secondary),
      onSecondary = ov(onSecondaryArgb, base.onSecondary),
      surface = ov(surfaceArgb, base.surface),
      onSurface = ov(onSurfaceArgb, base.onSurface),
      surfaceVariant = ov(surfaceVariantArgb, base.surfaceVariant),
      onSurfaceVariant = ov(onSurfaceVariantArgb, base.onSurfaceVariant),
      background = ov(backgroundArgb, base.background),
      onBackground = ov(onBackgroundArgb, base.onBackground),
      error = ov(errorArgb, base.error),
      outline = ov(outlineArgb, base.outline),
    )
    MaterialTheme(colorScheme = scheme) {
      Box(modifier = m) { (content as ComposeWidgetChildren).Render() }
    }
  }
  override fun dark(dark: Boolean) { this.dark = dark }
  override fun primaryArgb(primaryArgb: Int) { this.primaryArgb = primaryArgb }
  override fun onPrimaryArgb(onPrimaryArgb: Int) { this.onPrimaryArgb = onPrimaryArgb }
  override fun secondaryArgb(secondaryArgb: Int) { this.secondaryArgb = secondaryArgb }
  override fun onSecondaryArgb(onSecondaryArgb: Int) { this.onSecondaryArgb = onSecondaryArgb }
  override fun surfaceArgb(surfaceArgb: Int) { this.surfaceArgb = surfaceArgb }
  override fun onSurfaceArgb(onSurfaceArgb: Int) { this.onSurfaceArgb = onSurfaceArgb }
  override fun surfaceVariantArgb(surfaceVariantArgb: Int) { this.surfaceVariantArgb = surfaceVariantArgb }
  override fun onSurfaceVariantArgb(onSurfaceVariantArgb: Int) { this.onSurfaceVariantArgb = onSurfaceVariantArgb }
  override fun backgroundArgb(backgroundArgb: Int) { this.backgroundArgb = backgroundArgb }
  override fun onBackgroundArgb(onBackgroundArgb: Int) { this.onBackgroundArgb = onBackgroundArgb }
  override fun errorArgb(errorArgb: Int) { this.errorArgb = errorArgb }
  override fun outlineArgb(outlineArgb: Int) { this.outlineArgb = outlineArgb }
}
