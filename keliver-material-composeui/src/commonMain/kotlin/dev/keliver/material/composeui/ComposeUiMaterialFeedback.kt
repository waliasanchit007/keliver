/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.CircularProgressIndicator as M3CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator as M3LinearProgressIndicator
import androidx.compose.material3.Snackbar as M3Snackbar
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog as ComposeDialog
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.AlertDialog
import dev.keliver.material.widget.CircularProgressIndicator
import dev.keliver.material.widget.Dialog
import dev.keliver.material.widget.LinearProgressIndicator
import dev.keliver.material.widget.Snackbar
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren

internal class ComposeUiAlertDialog : AlertDialog<@Composable (Modifier) -> Unit> {
  private var title by mutableStateOf("")
  private var text by mutableStateOf("")
  private var confirmText by mutableStateOf("OK")
  private var dismissText by mutableStateOf("")
  private var onConfirm by mutableStateOf<(() -> Unit)?>(null)
  private var onDismiss by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { _ ->
    M3AlertDialog(
      onDismissRequest = { onDismiss?.invoke() },
      confirmButton = {
        M3TextButton(onClick = { onConfirm?.invoke() }) { M3Text(confirmText) }
      },
      dismissButton = if (dismissText.isNotEmpty()) {
        { M3TextButton(onClick = { onDismiss?.invoke() }) { M3Text(dismissText) } }
      } else {
        null
      },
      title = { M3Text(title) },
      text = { M3Text(text) },
    )
  }
  override fun title(title: String) { this.title = title }
  override fun text(text: String) { this.text = text }
  override fun confirmText(confirmText: String) { this.confirmText = confirmText }
  override fun dismissText(dismissText: String) { this.dismissText = dismissText }
  override fun onConfirm(onConfirm: (() -> Unit)?) { this.onConfirm = onConfirm }
  override fun onDismiss(onDismiss: (() -> Unit)?) { this.onDismiss = onDismiss }
}

internal class ComposeUiDialog : Dialog<@Composable (Modifier) -> Unit> {
  private var onDismiss by mutableStateOf<(() -> Unit)?>(null)
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { _ ->
    ComposeDialog(onDismissRequest = { onDismiss?.invoke() }) {
      (children as ComposeWidgetChildren).Render()
    }
  }
  override fun onDismiss(onDismiss: (() -> Unit)?) { this.onDismiss = onDismiss }
}

internal class ComposeUiSnackbar : Snackbar<@Composable (Modifier) -> Unit> {
  private var message by mutableStateOf("")
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Snackbar(modifier = m) { M3Text(message) }
  }
  override fun message(message: String) { this.message = message }
}

internal class ComposeUiCircularProgressIndicator : CircularProgressIndicator<@Composable (Modifier) -> Unit> {
  private var progress by mutableStateOf(-1f)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    if (progress < 0f) {
      M3CircularProgressIndicator(modifier = m)
    } else {
      M3CircularProgressIndicator(progress = { progress }, modifier = m)
    }
  }
  override fun progress(progress: Float) { this.progress = progress }
}

internal class ComposeUiLinearProgressIndicator : LinearProgressIndicator<@Composable (Modifier) -> Unit> {
  private var progress by mutableStateOf(-1f)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    if (progress < 0f) {
      M3LinearProgressIndicator(modifier = m)
    } else {
      M3LinearProgressIndicator(progress = { progress }, modifier = m)
    }
  }
  override fun progress(progress: Float) { this.progress = progress }
}
