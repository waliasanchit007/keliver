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
import dev.keliver.material.widget.ElevatedButton
import dev.keliver.material.widget.FilledTonalButton
import dev.keliver.material.widget.FloatingActionButton
import dev.keliver.material.widget.OutlinedButton
import dev.keliver.material.widget.OutlinedTextField
import dev.keliver.material.widget.RadioButton
import dev.keliver.material.widget.Slider
import dev.keliver.material.widget.TextButton
import dev.keliver.material.widget.ElevatedCard
import dev.keliver.material.widget.FlowColumn
import dev.keliver.material.widget.FlowRow
import dev.keliver.material.widget.OutlinedCard
import dev.keliver.material.widget.Surface
import dev.keliver.material.widget.BottomAppBar
import dev.keliver.material.widget.NavigationBar
import dev.keliver.material.widget.Scaffold
import dev.keliver.material.widget.Tab
import dev.keliver.material.widget.TabRow
import dev.keliver.material.widget.TopAppBar
import dev.keliver.material.widget.AlertDialog
import dev.keliver.material.widget.CircularProgressIndicator
import dev.keliver.material.widget.Dialog
import dev.keliver.material.widget.LinearProgressIndicator
import dev.keliver.material.widget.Snackbar
import dev.keliver.material.widget.Badge
import dev.keliver.material.widget.FilterChip
import dev.keliver.material.widget.InputChip
import dev.keliver.material.widget.SuggestionChip
import dev.keliver.material.widget.DropdownMenu
import dev.keliver.material.widget.ExtendedFloatingActionButton
import dev.keliver.material.widget.NavigationRail
import dev.keliver.material.widget.SegmentedButtonRow
import dev.keliver.material.widget.VerticalDivider
import dev.keliver.material.widget.HorizontalPager
import dev.keliver.material.widget.LazyHorizontalGrid
import dev.keliver.material.widget.LazyVerticalGrid
import dev.keliver.material.widget.StyledBox
import dev.keliver.material.widget.Tooltip
import dev.keliver.material.widget.VerticalPager

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

  // Batch 1: buttons & inputs.
  override fun OutlinedButton(): OutlinedButton<@Composable (Modifier) -> Unit> = ComposeUiOutlinedButton(imageLoader)
  override fun TextButton(): TextButton<@Composable (Modifier) -> Unit> = ComposeUiTextButton()
  override fun ElevatedButton(): ElevatedButton<@Composable (Modifier) -> Unit> = ComposeUiElevatedButton()
  override fun FilledTonalButton(): FilledTonalButton<@Composable (Modifier) -> Unit> = ComposeUiFilledTonalButton()
  override fun FloatingActionButton(): FloatingActionButton<@Composable (Modifier) -> Unit> = ComposeUiFloatingActionButton()
  override fun RadioButton(): RadioButton<@Composable (Modifier) -> Unit> = ComposeUiRadioButton()
  override fun Slider(): Slider<@Composable (Modifier) -> Unit> = ComposeUiSlider()
  override fun OutlinedTextField(): OutlinedTextField<@Composable (Modifier) -> Unit> = ComposeUiOutlinedTextField()

  // Batch 2: containers & layout.
  override fun Surface(): Surface<@Composable (Modifier) -> Unit> = ComposeUiSurface()
  override fun ElevatedCard(): ElevatedCard<@Composable (Modifier) -> Unit> = ComposeUiElevatedCard()
  override fun OutlinedCard(): OutlinedCard<@Composable (Modifier) -> Unit> = ComposeUiOutlinedCard()
  override fun FlowRow(): FlowRow<@Composable (Modifier) -> Unit> = ComposeUiFlowRow()
  override fun FlowColumn(): FlowColumn<@Composable (Modifier) -> Unit> = ComposeUiFlowColumn()

  // Batch 3: navigation & scaffolding.
  override fun Scaffold(): Scaffold<@Composable (Modifier) -> Unit> = ComposeUiScaffold()
  override fun TopAppBar(): TopAppBar<@Composable (Modifier) -> Unit> = ComposeUiTopAppBar()
  override fun BottomAppBar(): BottomAppBar<@Composable (Modifier) -> Unit> = ComposeUiBottomAppBar()
  override fun NavigationBar(): NavigationBar<@Composable (Modifier) -> Unit> = ComposeUiNavigationBar()
  override fun TabRow(): TabRow<@Composable (Modifier) -> Unit> = ComposeUiTabRow()
  override fun Tab(): Tab<@Composable (Modifier) -> Unit> = ComposeUiTab()

  // Batch 4: feedback & overlays.
  override fun AlertDialog(): AlertDialog<@Composable (Modifier) -> Unit> = ComposeUiAlertDialog()
  override fun Dialog(): Dialog<@Composable (Modifier) -> Unit> = ComposeUiDialog()
  override fun Snackbar(): Snackbar<@Composable (Modifier) -> Unit> = ComposeUiSnackbar()
  override fun CircularProgressIndicator(): CircularProgressIndicator<@Composable (Modifier) -> Unit> = ComposeUiCircularProgressIndicator()
  override fun LinearProgressIndicator(): LinearProgressIndicator<@Composable (Modifier) -> Unit> = ComposeUiLinearProgressIndicator()

  // Batch 5: chips & badges.
  override fun FilterChip(): FilterChip<@Composable (Modifier) -> Unit> = ComposeUiFilterChip()
  override fun InputChip(): InputChip<@Composable (Modifier) -> Unit> = ComposeUiInputChip()
  override fun SuggestionChip(): SuggestionChip<@Composable (Modifier) -> Unit> = ComposeUiSuggestionChip()
  override fun Badge(): Badge<@Composable (Modifier) -> Unit> = ComposeUiBadge()

  // Batch 6: advanced.
  override fun NavigationRail(): NavigationRail<@Composable (Modifier) -> Unit> = ComposeUiNavigationRail()
  override fun VerticalDivider(): VerticalDivider<@Composable (Modifier) -> Unit> = ComposeUiVerticalDivider()
  override fun ExtendedFloatingActionButton(): ExtendedFloatingActionButton<@Composable (Modifier) -> Unit> = ComposeUiExtendedFloatingActionButton()
  override fun SegmentedButtonRow(): SegmentedButtonRow<@Composable (Modifier) -> Unit> = ComposeUiSegmentedButtonRow()
  override fun DropdownMenu(): DropdownMenu<@Composable (Modifier) -> Unit> = ComposeUiDropdownMenu()

  // Batch 7: lazy grids, pagers, tooltip.
  override fun LazyVerticalGrid(): LazyVerticalGrid<@Composable (Modifier) -> Unit> = ComposeUiLazyVerticalGrid()
  override fun LazyHorizontalGrid(): LazyHorizontalGrid<@Composable (Modifier) -> Unit> = ComposeUiLazyHorizontalGrid()
  override fun HorizontalPager(): HorizontalPager<@Composable (Modifier) -> Unit> = ComposeUiHorizontalPager()
  override fun VerticalPager(): VerticalPager<@Composable (Modifier) -> Unit> = ComposeUiVerticalPager()
  override fun Tooltip(): Tooltip<@Composable (Modifier) -> Unit> = ComposeUiTooltip()
  override fun StyledBox(): StyledBox<@Composable (Modifier) -> Unit> = ComposeUiStyledBox()
  override fun Shimmer(): dev.keliver.material.widget.Shimmer<@Composable (Modifier) -> Unit> = ComposeUiShimmer()
  override fun AnimatedVisibility(): dev.keliver.material.widget.AnimatedVisibility<@Composable (Modifier) -> Unit> = ComposeUiAnimatedVisibility()
  override fun Theme(): dev.keliver.material.widget.Theme<@Composable (Modifier) -> Unit> = ComposeUiTheme()
  override fun Clickable(): dev.keliver.material.widget.Clickable<@Composable (Modifier) -> Unit> = ComposeUiClickable()
  override fun RichText(): dev.keliver.material.widget.RichText<@Composable (Modifier) -> Unit> = ComposeUiRichText()

  override fun Reuse(value: @Composable (Modifier) -> Unit, modifier: Reuse) {
  }

  // Universal visual modifiers are applied SELF-APPLY (each renderer reads its own
  // modifier via applyKeliverVisuals); these dispatch hooks are intentionally no-ops
  // for the Compose backend (they exist for imperative/View backends).
  override fun Background(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Background) {}
  override fun GradientBackground(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.GradientBackground) {}
  override fun CornerRadius(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.CornerRadius) {}
  override fun Border(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Border) {}
  override fun Shadow(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Shadow) {}
  override fun Padding(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Padding) {}
  override fun Size(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Size) {}
  override fun FillWidth(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.FillWidth) {}
  override fun Offset(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Offset) {}
  override fun Blur(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Blur) {}
  override fun Alpha(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Alpha) {}
  override fun CornerRadiusEach(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.CornerRadiusEach) {}
  override fun PaddingEach(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.PaddingEach) {}
  override fun AspectRatio(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.AspectRatio) {}
  override fun FillMaxHeight(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.FillMaxHeight) {}
  override fun FillMaxSize(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.FillMaxSize) {}
  override fun Rotate(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Rotate) {}
  override fun Scale(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.Scale) {}
  override fun AnimateContentSize(value: @Composable (Modifier) -> Unit, modifier: dev.keliver.material.modifier.AnimateContentSize) {}
}

@Suppress("FunctionName") // Acting like a type.
public fun ComposeUiKeliverMaterialWidgetSystem(
  imageLoader: ImageLoader,
): KeliverMaterialWidgetSystem<@Composable (Modifier) -> Unit> {
  // Install keliver-material's visual-modifier translator into the central
  // child renderer, so Modifier.background()/cornerRadius()/border()/… compose
  // on EVERY widget (material + layout + lazylayout) with no per-widget code.
  dev.keliver.widget.compose.keliverVisualModifierApplier = { m, km -> m.applyKeliverVisuals(km) }
  return KeliverMaterialWidgetSystem(
    KeliverMaterial = ComposeUiKeliverMaterialWidgetFactory(imageLoader),
    RedwoodLayout = ComposeUiRedwoodLayoutWidgetFactory(),
    RedwoodLazyLayout = ComposeUiRedwoodLazyLayoutWidgetFactory(),
  )
}
