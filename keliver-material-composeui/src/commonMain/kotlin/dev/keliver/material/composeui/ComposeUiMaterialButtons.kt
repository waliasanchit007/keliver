/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text as M3FieldText
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage as CoilAsyncImage
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.ElevatedButton
import dev.keliver.material.widget.FilledTonalButton
import dev.keliver.material.widget.FloatingActionButton
import dev.keliver.material.widget.OutlinedButton
import dev.keliver.material.widget.OutlinedTextField
import dev.keliver.material.widget.RadioButton
import dev.keliver.material.widget.Slider
import dev.keliver.material.widget.TextButton

internal class ComposeUiOutlinedButton(
  private val imageLoader: ImageLoader,
) : OutlinedButton<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var enabled by mutableStateOf(true)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  private var iconUrl by mutableStateOf("")
  private var iconSizeDp by mutableStateOf(18)
  private var containerArgb by mutableStateOf(0)
  private var contentArgb by mutableStateOf(0)
  private var borderArgb by mutableStateOf(0)
  private var cornerRadiusDp by mutableStateOf(0)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    val colors = ButtonDefaults.outlinedButtonColors(
      containerColor = if (containerArgb != 0) Color(containerArgb) else Color.Transparent,
      contentColor = if (contentArgb != 0) Color(contentArgb) else ButtonDefaults.outlinedButtonColors().contentColor,
    )
    val border = if (borderArgb != 0) BorderStroke(1.dp, Color(borderArgb)) else ButtonDefaults.outlinedButtonBorder(enabled)
    M3OutlinedButton(
      onClick = { onClick?.invoke() },
      enabled = enabled,
      modifier = m,
      colors = colors,
      border = border,
      shape = if (cornerRadiusDp > 0) RoundedCornerShape(cornerRadiusDp.dp) else ButtonDefaults.outlinedShape,
    ) {
      if (iconUrl.isNotEmpty()) {
        CoilAsyncImage(
          model = iconUrl,
          imageLoader = imageLoader,
          contentDescription = null,
          modifier = Modifier.size(iconSizeDp.dp),
        )
        Spacer(Modifier.width(8.dp))
      }
      M3Text(text)
    }
  }
  override fun text(text: String) { this.text = text }
  override fun enabled(enabled: Boolean) { this.enabled = enabled }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
  override fun iconUrl(iconUrl: String) { this.iconUrl = iconUrl }
  override fun iconSizeDp(iconSizeDp: Int) { this.iconSizeDp = iconSizeDp }
  override fun containerArgb(containerArgb: Int) { this.containerArgb = containerArgb }
  override fun contentArgb(contentArgb: Int) { this.contentArgb = contentArgb }
  override fun borderArgb(borderArgb: Int) { this.borderArgb = borderArgb }
  override fun cornerRadiusDp(cornerRadiusDp: Int) { this.cornerRadiusDp = cornerRadiusDp }
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
  private var incoming by mutableStateOf("")
  private var placeholderState by mutableStateOf("")
  private var onValueChange by mutableStateOf<((String) -> Unit)?>(null)
  private var label by mutableStateOf("")
  private var leadingText by mutableStateOf("")
  private var keyboardType by mutableStateOf(0)
  private var singleLine by mutableStateOf(true)
  private var maxLines by mutableStateOf(1)
  private var isError by mutableStateOf(false)
  private var supportingText by mutableStateOf("")
  private var borderArgb by mutableStateOf(0)
  private var cornerRadiusDp by mutableStateOf(0)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    // Host-local editing buffer (see ComposeUiTextField) — keeps typing responsive.
    var local by remember { mutableStateOf(TextFieldValue(incoming, TextRange(incoming.length))) }
    LaunchedEffect(incoming) {
      if (incoming != local.text) local = TextFieldValue(incoming, TextRange(incoming.length))
    }
    val shape = if (cornerRadiusDp > 0) RoundedCornerShape(cornerRadiusDp.dp) else OutlinedTextFieldDefaults.shape
    val colors = if (borderArgb != 0) {
      OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = Color(borderArgb),
        focusedBorderColor = Color(borderArgb),
      )
    } else {
      OutlinedTextFieldDefaults.colors()
    }
    M3OutlinedTextField(
      value = local,
      onValueChange = { local = it; onValueChange?.invoke(it.text) },
      placeholder = { if (placeholderState.isNotEmpty()) M3FieldText(placeholderState) },
      label = if (label.isNotEmpty()) ({ M3FieldText(label) }) else null,
      leadingIcon = if (leadingText.isNotEmpty()) ({ M3FieldText(leadingText) }) else null,
      supportingText = if (supportingText.isNotEmpty()) ({ M3FieldText(supportingText) }) else null,
      isError = isError,
      singleLine = singleLine,
      maxLines = if (singleLine) 1 else maxLines,
      shape = shape,
      colors = colors,
      visualTransformation = if (keyboardType == 5) PasswordVisualTransformation() else VisualTransformation.None,
      keyboardOptions = KeyboardOptions(keyboardType = toKeyboardType(keyboardType)),
      modifier = m,
    )
  }
  override fun text(text: String) { this.incoming = text }
  override fun placeholder(placeholder: String) { this.placeholderState = placeholder }
  override fun onValueChange(onValueChange: ((String) -> Unit)?) { this.onValueChange = onValueChange }
  override fun label(label: String) { this.label = label }
  override fun leadingText(leadingText: String) { this.leadingText = leadingText }
  override fun keyboardType(keyboardType: Int) { this.keyboardType = keyboardType }
  override fun singleLine(singleLine: Boolean) { this.singleLine = singleLine }
  override fun maxLines(maxLines: Int) { this.maxLines = maxLines }
  override fun isError(isError: Boolean) { this.isError = isError }
  override fun supportingText(supportingText: String) { this.supportingText = supportingText }
  override fun borderArgb(borderArgb: Int) { this.borderArgb = borderArgb }
  override fun cornerRadiusDp(cornerRadiusDp: Int) { this.cornerRadiusDp = cornerRadiusDp }
}

private fun toKeyboardType(i: Int): KeyboardType = when (i) {
  1 -> KeyboardType.Number
  2 -> KeyboardType.Decimal
  3 -> KeyboardType.Email
  4 -> KeyboardType.Phone
  5 -> KeyboardType.Password
  else -> KeyboardType.Text
}
