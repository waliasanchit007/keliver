/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import dev.keliver.layout.composeui.ComposeUiRedwoodLayoutWidgetFactory
import dev.keliver.lazylayout.composeui.ComposeUiRedwoodLazyLayoutWidgetFactory
import dev.keliver.material.modifier.Reuse
import dev.keliver.material.widget.AsyncImage
import dev.keliver.material.widget.BottomSheet
import dev.keliver.material.widget.Button
import dev.keliver.material.widget.Card
import dev.keliver.material.widget.Checkbox
import dev.keliver.material.widget.Chip
import dev.keliver.material.widget.Divider
import dev.keliver.material.widget.IconButton
import dev.keliver.material.widget.Image
import dev.keliver.material.widget.KeliverMaterialWidgetFactory
import dev.keliver.material.widget.KeliverMaterialWidgetSystem
import dev.keliver.material.widget.ScrollableColumn
import dev.keliver.material.widget.StyledText
import dev.keliver.material.widget.Switch
import dev.keliver.material.widget.Text
import dev.keliver.material.widget.TextField
import dev.keliver.material.widget.TextInput

public class ComposeUiKeliverMaterialWidgetFactory(
  private val imageLoader: ImageLoader,
) : KeliverMaterialWidgetFactory<@Composable (Modifier) -> Unit> {
  // Inherited primitives (Material2).
  override fun TextInput(): TextInput<@Composable (Modifier) -> Unit> = ComposeUiTextInput()
  override fun Text(): Text<@Composable (Modifier) -> Unit> = ComposeUiText()
  override fun Image(): Image<@Composable (Modifier) -> Unit> = ComposeUiImage(imageLoader)
  override fun Button(): Button<@Composable ((Modifier) -> Unit)> = ComposeUiButton()

  // Material3 seed widgets.
  override fun StyledText(): StyledText<@Composable (Modifier) -> Unit> = ComposeUiStyledText()
  override fun Card(): Card<@Composable (Modifier) -> Unit> = ComposeUiCard()
  override fun Switch(): Switch<@Composable (Modifier) -> Unit> = ComposeUiSwitch()
  override fun Checkbox(): Checkbox<@Composable (Modifier) -> Unit> = ComposeUiCheckbox()
  override fun TextField(): TextField<@Composable (Modifier) -> Unit> = ComposeUiTextField()
  override fun Divider(): Divider<@Composable (Modifier) -> Unit> = ComposeUiDivider()
  override fun Chip(): Chip<@Composable (Modifier) -> Unit> = ComposeUiChip()
  override fun IconButton(): IconButton<@Composable (Modifier) -> Unit> = ComposeUiIconButton(imageLoader)
  override fun AsyncImage(): AsyncImage<@Composable (Modifier) -> Unit> = ComposeUiAsyncImage(imageLoader)
  override fun ScrollableColumn(): ScrollableColumn<@Composable (Modifier) -> Unit> = ComposeUiScrollableColumn()
  override fun BottomSheet(): BottomSheet<@Composable (Modifier) -> Unit> = ComposeUiBottomSheet()

  override fun Reuse(value: @Composable (Modifier) -> Unit, modifier: Reuse) {
  }
}

@Suppress("FunctionName") // Acting like a type.
public fun ComposeUiKeliverMaterialWidgetSystem(
  imageLoader: ImageLoader,
): KeliverMaterialWidgetSystem<@Composable (Modifier) -> Unit> {
  return KeliverMaterialWidgetSystem(
    KeliverMaterial = ComposeUiKeliverMaterialWidgetFactory(imageLoader),
    RedwoodLayout = ComposeUiRedwoodLayoutWidgetFactory(),
    RedwoodLazyLayout = ComposeUiRedwoodLazyLayoutWidgetFactory(),
  )
}
