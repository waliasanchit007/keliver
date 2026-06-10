/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column as FoundationColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.Checkbox as M3Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextField as M3TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage as CoilAsyncImage
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.AsyncImage
import dev.keliver.material.widget.BottomSheet
import dev.keliver.material.widget.Card
import dev.keliver.material.widget.Checkbox
import dev.keliver.material.widget.Chip
import dev.keliver.material.widget.Divider
import dev.keliver.material.widget.IconButton
import dev.keliver.material.widget.ScrollableColumn
import dev.keliver.material.widget.StyledText
import dev.keliver.material.widget.Switch
import dev.keliver.material.widget.TextField
import dev.keliver.widget.Widget
import dev.keliver.widget.compose.ComposeWidgetChildren

internal class ComposeUiStyledText : StyledText<@Composable (Modifier) -> Unit> {
  private var text by mutableStateOf("")
  private var fontSize by mutableStateOf(14)
  private var bold by mutableStateOf(false)
  private var colorArgb by mutableStateOf(0)
  private var align by mutableStateOf(0)
  private var maxLines by mutableStateOf(0)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Text(
      text = text,
      fontSize = fontSize.sp,
      fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
      color = if (colorArgb == 0) Color.Unspecified else Color(colorArgb),
      textAlign = when (align) {
        1 -> TextAlign.Center
        2 -> TextAlign.End
        else -> TextAlign.Start
      },
      maxLines = if (maxLines <= 0) Int.MAX_VALUE else maxLines,
      modifier = m,
    )
  }
  override fun text(text: String) { this.text = text }
  override fun fontSize(fontSize: Int) { this.fontSize = fontSize }
  override fun bold(bold: Boolean) { this.bold = bold }
  override fun colorArgb(colorArgb: Int) { this.colorArgb = colorArgb }
  override fun align(align: Int) { this.align = align }
  override fun maxLines(maxLines: Int) { this.maxLines = maxLines }
}

internal class ComposeUiCard : Card<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Card(modifier = m) { (children as ComposeWidgetChildren).Render() }
  }
}

internal class ComposeUiSwitch : Switch<@Composable (Modifier) -> Unit> {
  private var checked by mutableStateOf(false)
  private var enabled by mutableStateOf(true)
  private var onCheckedChange by mutableStateOf<((Boolean) -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Switch(
      checked = checked,
      onCheckedChange = { onCheckedChange?.invoke(it) },
      enabled = enabled,
      modifier = m,
    )
  }
  override fun checked(checked: Boolean) { this.checked = checked }
  override fun enabled(enabled: Boolean) { this.enabled = enabled }
  override fun onCheckedChange(onCheckedChange: ((Boolean) -> Unit)?) { this.onCheckedChange = onCheckedChange }
}

internal class ComposeUiCheckbox : Checkbox<@Composable (Modifier) -> Unit> {
  private var checked by mutableStateOf(false)
  private var enabled by mutableStateOf(true)
  private var onCheckedChange by mutableStateOf<((Boolean) -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3Checkbox(
      checked = checked,
      onCheckedChange = { onCheckedChange?.invoke(it) },
      enabled = enabled,
      modifier = m,
    )
  }
  override fun checked(checked: Boolean) { this.checked = checked }
  override fun enabled(enabled: Boolean) { this.enabled = enabled }
  override fun onCheckedChange(onCheckedChange: ((Boolean) -> Unit)?) { this.onCheckedChange = onCheckedChange }
}

internal class ComposeUiTextField : TextField<@Composable (Modifier) -> Unit> {
  private var valueState by mutableStateOf("")
  private var placeholderState by mutableStateOf("")
  private var onValueChange by mutableStateOf<((String) -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3TextField(
      value = valueState,
      onValueChange = { onValueChange?.invoke(it) },
      placeholder = { M3Text(placeholderState) },
      singleLine = true,
      modifier = m,
    )
  }
  override fun value(value: String) { this.valueState = value }
  override fun placeholder(placeholder: String) { this.placeholderState = placeholder }
  override fun onValueChange(onValueChange: ((String) -> Unit)?) { this.onValueChange = onValueChange }
}

internal class ComposeUiDivider : Divider<@Composable (Modifier) -> Unit> {
  private var thickness by mutableStateOf(1)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    HorizontalDivider(modifier = m, thickness = thickness.dp)
  }
  override fun thickness(thickness: Int) { this.thickness = thickness }
}

internal class ComposeUiChip : Chip<@Composable (Modifier) -> Unit> {
  private var label by mutableStateOf("")
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    AssistChip(
      onClick = { onClick?.invoke() },
      label = { M3Text(label) },
      modifier = m,
    )
  }
  override fun label(label: String) { this.label = label }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiIconButton(
  private val imageLoader: ImageLoader,
) : IconButton<@Composable (Modifier) -> Unit> {
  private var imageUrl by mutableStateOf("")
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    M3IconButton(onClick = { onClick?.invoke() }, modifier = m) {
      CoilAsyncImage(model = imageUrl, imageLoader = imageLoader, contentDescription = null)
    }
  }
  override fun imageUrl(imageUrl: String) { this.imageUrl = imageUrl }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiAsyncImage(
  private val imageLoader: ImageLoader,
) : AsyncImage<@Composable (Modifier) -> Unit> {
  private var url by mutableStateOf("")
  private var contentScale by mutableStateOf(0)
  private var onClick by mutableStateOf<(() -> Unit)?>(null)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    CoilAsyncImage(
      model = url,
      imageLoader = imageLoader,
      contentDescription = null,
      contentScale = when (contentScale) {
        1 -> ContentScale.Crop
        2 -> ContentScale.FillBounds
        else -> ContentScale.Fit
      },
      modifier = onClick?.let { cb -> m.clickable { cb() } } ?: m,
    )
  }
  override fun url(url: String) { this.url = url }
  override fun contentScale(contentScale: Int) { this.contentScale = contentScale }
  override fun onClick(onClick: (() -> Unit)?) { this.onClick = onClick }
}

internal class ComposeUiScrollableColumn : ScrollableColumn<@Composable (Modifier) -> Unit> {
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    FoundationColumn(modifier = m.verticalScroll(rememberScrollState())) {
      (children as ComposeWidgetChildren).Render()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
internal class ComposeUiBottomSheet : BottomSheet<@Composable (Modifier) -> Unit> {
  private var visible by mutableStateOf(false)
  private var onDismiss by mutableStateOf<(() -> Unit)?>(null)
  override val children: Widget.Children<@Composable (Modifier) -> Unit> = ComposeWidgetChildren()
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { _ ->
    if (visible) {
      ModalBottomSheet(onDismissRequest = { onDismiss?.invoke() }) {
        (children as ComposeWidgetChildren).Render()
      }
    }
  }
  override fun visible(visible: Boolean) { this.visible = visible }
  override fun onDismiss(onDismiss: (() -> Unit)?) { this.onDismiss = onDismiss }
}
