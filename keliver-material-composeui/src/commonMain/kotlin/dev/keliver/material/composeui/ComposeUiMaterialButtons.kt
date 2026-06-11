/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.material3.ElevatedButton as M3ElevatedButton
import androidx.compose.material3.FilledTonalButton as M3FilledTonalButton
import androidx.compose.material3.FloatingActionButton as M3FloatingActionButton
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.OutlinedTextField as M3OutlinedTextField
import androidx.compose.material3.RadioButton as M3RadioButton
import androidx.compose.material3.Slider as M3Slider
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.ElevatedButton
import dev.keliver.material.widget.FilledTonalButton
import dev.keliver.material.widget.FloatingActionButton
import dev.keliver.material.widget.OutlinedButton
import dev.keliver.material.widget.OutlinedTextField
import dev.keliver.material.widget.RadioButton
import dev.keliver.material.widget.Slider
import dev.keliver.material.widget.TextButton

internal class ComposeUiOutlinedButton : OutlinedButton<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var enabled by mutableStateOf(true)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3OutlinedButton(onClick = { onClick?.invoke() }, enabled = enabled, modifier = m) { M3Text(text) }
  }
  override fun text(text: String) { this.text = text }
  override fun enabled(enabled: Boolean) { this.enabled = enabled }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiTextButton : TextButton<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var enabled by mutableStateOf(true)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3TextButton(onClick = { onClick?.invoke() }, enabled = enabled, modifier = m) { M3Text(text) }
  }
  override fun text(text: String) { this.text = text }
  override fun enabled(enabled: Boolean) { this.enabled = enabled }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiElevatedButton : ElevatedButton<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var enabled by mutableStateOf(true)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3ElevatedButton(onClick = { onClick?.invoke() }, enabled = enabled, modifier = m) { M3Text(text) }
  }
  override fun text(text: String) { this.text = text }
  override fun enabled(enabled: Boolean) { this.enabled = enabled }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiFilledTonalButton : FilledTonalButton<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var enabled by mutableStateOf(true)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3FilledTonalButton(onClick = { onClick?.invoke() }, enabled = enabled, modifier = m) { M3Text(text) }
  }
  override fun text(text: String) { this.text = text }
  override fun enabled(enabled: Boolean) { this.enabled = enabled }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiFloatingActionButton : FloatingActionButton<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3FloatingActionButton(onClick = { onClick?.invoke() }, modifier = m) { M3Text(text) }
  }
  override fun text(text: String) { this.text = text }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiRadioButton : RadioButton<@Composable (Modifier) -> Unit> {
  private var selected by mutableStateOf(false)
  private var enabled by mutableStateOf(true)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3RadioButton(selected = selected, onClick = { onClick?.invoke() }, enabled = enabled, modifier = m)
  }
  override fun selected(selected: Boolean) { this.selected = selected }
  override fun enabled(enabled: Boolean) { this.enabled = enabled }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiSlider : Slider<@Composable (Modifier) -> Unit> {
  private var position by mutableStateOf(0f)
  private var enabled by mutableStateOf(true)
  private var onValueChange by mutableStateOf<((Float) -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Slider(
      value = position,
      onValueChange = { onValueChange?.invoke(it) },
      enabled = enabled,
      modifier = m,
    )
  }
  override fun position(position: Float) { this.position = position }
  override fun enabled(enabled: Boolean) { this.enabled = enabled }
  override fun onValueChange(onValueChange: ((Float) -> Unit)?) { this.onValueChange = onValueChange }
}

internal class ComposeUiOutlinedTextField : OutlinedTextField<@Composable (Modifier) -> Unit> {
  private var textState by mutableStateOf("")
  private var placeholderState by mutableStateOf("")
  private var onValueChange by mutableStateOf<((String) -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3OutlinedTextField(
      value = textState,
      onValueChange = { onValueChange?.invoke(it) },
      placeholder = { M3Text(placeholderState) },
      singleLine = true,
      modifier = m,
    )
  }
  override fun text(text: String) { this.textState = text }
  override fun placeholder(placeholder: String) { this.placeholderState = placeholder }
  override fun onValueChange(onValueChange: ((String) -> Unit)?) { this.onValueChange = onValueChange }
}
