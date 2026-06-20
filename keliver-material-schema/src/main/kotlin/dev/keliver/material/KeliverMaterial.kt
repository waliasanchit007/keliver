/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material

import dev.keliver.layout.RedwoodLayout
import dev.keliver.lazylayout.RedwoodLazyLayout
import dev.keliver.material.api.TextFieldState
import dev.keliver.schema.Children
import dev.keliver.schema.Modifier
import dev.keliver.schema.Property
import dev.keliver.schema.Schema
import dev.keliver.schema.Schema.Dependency
import dev.keliver.schema.Widget

/**
 * `keliver-material` — a batteries-included, Compose/Material3-parity widget
 * schema. Adopters depend on this single schema to author server-driven UI that
 * reads like native Compose, with no per-app widget definitions. Composes on the
 * layout + lazylayout primitives.
 *
 * Widget IDs (`@Widget(n)`) and trait tags (`@Property`/`@Children`, which share
 * one namespace per widget) are WIRE-STABLE once shipped: never renumber, only
 * append. Batch 0 = tags 1-15; later batches append toward full parity.
 */
@Schema(
  members = [
    // inherited primitives (from keliver-ui-basic)
    TextInput::class,
    Text::class,
    Image::class,
    Button::class,
    // Batch 0 seed (Material3)
    StyledText::class,
    Card::class,
    Switch::class,
    Checkbox::class,
    TextField::class,
    Divider::class,
    Chip::class,
    IconButton::class,
    AsyncImage::class,
    ScrollableColumn::class,
    BottomSheet::class,
    // Batch 1: buttons & inputs
    OutlinedButton::class,
    TextButton::class,
    ElevatedButton::class,
    FilledTonalButton::class,
    FloatingActionButton::class,
    RadioButton::class,
    Slider::class,
    OutlinedTextField::class,
    // Batch 2: containers & layout
    Surface::class,
    ElevatedCard::class,
    OutlinedCard::class,
    FlowRow::class,
    FlowColumn::class,
    // Batch 3: navigation & scaffolding
    Scaffold::class,
    TopAppBar::class,
    BottomAppBar::class,
    NavigationBar::class,
    TabRow::class,
    Tab::class,
    // Batch 4: feedback & overlays
    AlertDialog::class,
    Dialog::class,
    Snackbar::class,
    CircularProgressIndicator::class,
    LinearProgressIndicator::class,
    // Batch 5: chips & badges
    FilterChip::class,
    InputChip::class,
    SuggestionChip::class,
    Badge::class,
    // Batch 6: advanced
    NavigationRail::class,
    VerticalDivider::class,
    ExtendedFloatingActionButton::class,
    SegmentedButtonRow::class,
    DropdownMenu::class,
    // Batch 7: lazy grids, pagers, tooltip
    LazyVerticalGrid::class,
    LazyHorizontalGrid::class,
    HorizontalPager::class,
    VerticalPager::class,
    Tooltip::class,
    // Batch 8: styled container (background/gradient/shape/size) for pixel-exact UI
    StyledBox::class,
    Reuse::class,
    // Batch 9: universal VISUAL MODIFIERS — composable like native Compose
    // (Modifier.background(...).cornerRadius(...).padding(...)). Unscoped: valid
    // on any widget; the host applies them in declaration order.
    Background::class,
    GradientBackground::class,
    CornerRadius::class,
    Border::class,
    Shadow::class,
    Padding::class,
    Size::class,
    FillWidth::class,
    Offset::class,
    Blur::class,
    Alpha::class,
  ],
  dependencies = [
    Dependency(1, RedwoodLayout::class),
    Dependency(2, RedwoodLazyLayout::class),
  ],
)
public interface KeliverMaterial

// ---------------------------------------------------------------------------
// Inherited primitives (unchanged from ui-basic).
// ---------------------------------------------------------------------------

@Widget(1)
public data class TextInput(
  @Property(1) val state: TextFieldState = TextFieldState(),
  @Property(2) val hint: String = "",
  @Property(3) val onChange: ((TextFieldState) -> Unit)? = null,
)

@Widget(2)
public data class Text(
  @Property(1) val text: String,
)

@Widget(3)
public data class Image(
  @Property(1) val url: String,
  @Property(2) val onClick: (() -> Unit)? = null,
)

@Widget(4)
public data class Button(
  @Property(1) val text: String?,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onClick: (() -> Unit)? = null,
)

// ---------------------------------------------------------------------------
// Batch 0 seed widgets (Material3 defaults).
// ---------------------------------------------------------------------------

/** Styled text: weight, size, color, alignment, line clamp. */
@Widget(5)
public data class StyledText(
  @Property(1) val text: String,
  @Property(2) val fontSize: Int = 14,
  @Property(3) val bold: Boolean = false,
  /** ARGB int; 0 => theme `onSurface`. */
  @Property(4) val colorArgb: Int = 0,
  /** 0 start, 1 center, 2 end. */
  @Property(5) val align: Int = 0,
  /** 0 => unlimited. */
  @Property(6) val maxLines: Int = 0,
)

/** Material3 elevated card container. */
@Widget(6)
public data class Card(
  @Children(1) val children: () -> Unit,
)

/** Material3 toggle switch. */
@Widget(7)
public data class Switch(
  @Property(1) val checked: Boolean,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onCheckedChange: ((Boolean) -> Unit)? = null,
)

/** Material3 checkbox. */
@Widget(8)
public data class Checkbox(
  @Property(1) val checked: Boolean,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onCheckedChange: ((Boolean) -> Unit)? = null,
)

/** Single-line text field (Compose `value` / `onValueChange` contract). */
@Widget(9)
public data class TextField(
  // NB: named `text`, not `value` — `value` is reserved (it's the Widget render
  // slot) and collides in the generated test doubles.
  @Property(1) val text: String,
  @Property(2) val placeholder: String = "",
  @Property(3) val onValueChange: ((String) -> Unit)? = null,
)

/** Horizontal divider of [thickness] dp. */
@Widget(10)
public data class Divider(
  @Property(1) val thickness: Int = 1,
)

/** Material3 assist chip with a text label. */
@Widget(11)
public data class Chip(
  @Property(1) val label: String,
  @Property(2) val onClick: (() -> Unit)? = null,
)

/** Icon-only button rendering a URL image inside an `IconButton`. */
@Widget(12)
public data class IconButton(
  @Property(1) val imageUrl: String,
  @Property(2) val onClick: (() -> Unit)? = null,
)

/** Network image via Coil. [contentScale]: 0 fit, 1 crop, 2 fillBounds. */
@Widget(13)
public data class AsyncImage(
  @Property(1) val url: String,
  @Property(2) val contentScale: Int = 0,
  @Property(3) val onClick: (() -> Unit)? = null,
  /** Fixed width in dp; 0 => unconstrained. */
  @Property(4) val widthDp: Int = 0,
  /** Fixed height in dp; 0 => unconstrained. */
  @Property(5) val heightDp: Int = 0,
  /** Gaussian blur radius in dp; 0 => sharp. Used e.g. for masked QR previews. */
  @Property(6) val blurDp: Int = 0,
  /** Fill the parent's width and scale height by aspect ratio (ContentScale
   *  .FillWidth) — for full-bleed banners. Overrides [widthDp]/[contentScale]. */
  @Property(7) val fillWidth: Boolean = false,
)

/** Vertically scrollable column container. */
@Widget(14)
public data class ScrollableColumn(
  @Children(1) val children: () -> Unit,
)

/** Material3 modal bottom sheet; content in [children]. */
@Widget(15)
public data class BottomSheet(
  @Property(1) val visible: Boolean,
  @Property(2) val onDismiss: (() -> Unit)? = null,
  @Children(3) val children: () -> Unit,
)

// ---------------------------------------------------------------------------
// Batch 1: buttons & inputs (Material3).
// ---------------------------------------------------------------------------

@Widget(16)
public data class OutlinedButton(
  @Property(1) val text: String,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onClick: (() -> Unit)? = null,
)

@Widget(17)
public data class TextButton(
  @Property(1) val text: String,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onClick: (() -> Unit)? = null,
)

@Widget(18)
public data class ElevatedButton(
  @Property(1) val text: String,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onClick: (() -> Unit)? = null,
)

@Widget(19)
public data class FilledTonalButton(
  @Property(1) val text: String,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onClick: (() -> Unit)? = null,
)

/** Floating action button with a short text label. */
@Widget(20)
public data class FloatingActionButton(
  @Property(1) val text: String = "",
  @Property(2) val onClick: (() -> Unit)? = null,
)

@Widget(21)
public data class RadioButton(
  @Property(1) val selected: Boolean,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onClick: (() -> Unit)? = null,
)

/** Slider; [position] is 0f..1f. */
@Widget(22)
public data class Slider(
  @Property(1) val position: Float = 0f,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onValueChange: ((Float) -> Unit)? = null,
)

@Widget(23)
public data class OutlinedTextField(
  @Property(1) val text: String,
  @Property(2) val placeholder: String = "",
  @Property(3) val onValueChange: ((String) -> Unit)? = null,
)

// ---------------------------------------------------------------------------
// Batch 2: containers & layout (Material3).
// ---------------------------------------------------------------------------

/** Material3 Surface container. */
@Widget(24)
public data class Surface(
  @Children(1) val children: () -> Unit,
)

@Widget(25)
public data class ElevatedCard(
  @Children(1) val children: () -> Unit,
)

@Widget(26)
public data class OutlinedCard(
  @Children(1) val children: () -> Unit,
)

/** Flow layout — children wrap horizontally. */
@Widget(27)
public data class FlowRow(
  @Children(1) val children: () -> Unit,
)

/** Flow layout — children wrap vertically. */
@Widget(28)
public data class FlowColumn(
  @Children(1) val children: () -> Unit,
)

// ---------------------------------------------------------------------------
// Batch 3: navigation & scaffolding (Material3).
// ---------------------------------------------------------------------------

/** Material3 Scaffold with a top-bar slot and a content slot. */
@Widget(29)
public data class Scaffold(
  @Children(1) val topBar: () -> Unit,
  @Children(2) val content: () -> Unit,
)

@Widget(30)
public data class TopAppBar(
  @Property(1) val title: String,
)

@Widget(31)
public data class BottomAppBar(
  @Children(1) val children: () -> Unit,
)

@Widget(32)
public data class NavigationBar(
  @Children(1) val children: () -> Unit,
)

@Widget(33)
public data class TabRow(
  @Property(1) val selectedIndex: Int = 0,
  @Children(2) val tabs: () -> Unit,
)

@Widget(34)
public data class Tab(
  @Property(1) val text: String,
  @Property(2) val selected: Boolean = false,
  @Property(3) val onClick: (() -> Unit)? = null,
)

// ---------------------------------------------------------------------------
// Batch 4: feedback & overlays (Material3).
// ---------------------------------------------------------------------------

@Widget(35)
public data class AlertDialog(
  @Property(1) val title: String,
  @Property(2) val text: String,
  @Property(3) val confirmText: String = "OK",
  @Property(4) val dismissText: String = "",
  @Property(5) val onConfirm: (() -> Unit)? = null,
  @Property(6) val onDismiss: (() -> Unit)? = null,
)

/** Generic modal dialog; content in [children]. */
@Widget(36)
public data class Dialog(
  @Property(1) val onDismiss: (() -> Unit)? = null,
  @Children(2) val children: () -> Unit,
)

@Widget(37)
public data class Snackbar(
  @Property(1) val message: String,
)

/** Circular progress; [progress] 0f..1f, or < 0 for indeterminate. */
@Widget(38)
public data class CircularProgressIndicator(
  @Property(1) val progress: Float = -1f,
)

/** Linear progress; [progress] 0f..1f, or < 0 for indeterminate. */
@Widget(39)
public data class LinearProgressIndicator(
  @Property(1) val progress: Float = -1f,
)

// ---------------------------------------------------------------------------
// Batch 5: chips & badges (Material3).
// ---------------------------------------------------------------------------

@Widget(40)
public data class FilterChip(
  @Property(1) val label: String,
  @Property(2) val selected: Boolean = false,
  @Property(3) val onClick: (() -> Unit)? = null,
)

@Widget(41)
public data class InputChip(
  @Property(1) val label: String,
  @Property(2) val selected: Boolean = false,
  @Property(3) val onClick: (() -> Unit)? = null,
)

@Widget(42)
public data class SuggestionChip(
  @Property(1) val label: String,
  @Property(2) val onClick: (() -> Unit)? = null,
)

@Widget(43)
public data class Badge(
  @Property(1) val text: String = "",
)

// ---------------------------------------------------------------------------
// Batch 6: advanced (Material3).
// ---------------------------------------------------------------------------

@Widget(44)
public data class NavigationRail(
  @Children(1) val children: () -> Unit,
)

@Widget(45)
public data class VerticalDivider(
  @Property(1) val thickness: Int = 1,
)

@Widget(46)
public data class ExtendedFloatingActionButton(
  @Property(1) val text: String,
  @Property(2) val onClick: (() -> Unit)? = null,
)

/** Single-choice segmented button row over [options]. */
@Widget(47)
public data class SegmentedButtonRow(
  @Property(1) val options: List<String>,
  @Property(2) val selectedIndex: Int = 0,
  @Property(3) val onSelect: ((Int) -> Unit)? = null,
)

/** Dropdown menu; renders [options] as items while [expanded]. */
@Widget(48)
public data class DropdownMenu(
  @Property(1) val expanded: Boolean = false,
  @Property(2) val options: List<String>,
  @Property(3) val onSelect: ((Int) -> Unit)? = null,
  @Property(4) val onDismiss: (() -> Unit)? = null,
)

// ---------------------------------------------------------------------------
// Batch 7: lazy grids, pagers, tooltip (Material3 / foundation).
// ---------------------------------------------------------------------------

/** Lazy grid; children laid out in [columns] columns, only visible composed. */
@Widget(49)
public data class LazyVerticalGrid(
  @Property(1) val columns: Int = 2,
  @Children(2) val children: () -> Unit,
)

@Widget(50)
public data class LazyHorizontalGrid(
  @Property(1) val rows: Int = 2,
  @Children(2) val children: () -> Unit,
)

/** Horizontal pager; each child is a page. */
@Widget(51)
public data class HorizontalPager(
  /** Auto-advance interval in ms; 0 => no auto-scroll. Pauses while dragging. */
  @Property(1) val autoScrollMs: Int = 0,
  /** Show page-indicator dots below the pager (only when >1 page). */
  @Property(2) val showIndicator: Boolean = false,
  /** Active / inactive dot color ARGB (defaults: primary / light gray). */
  @Property(3) val indicatorActiveArgb: Int = 0,
  @Property(4) val indicatorInactiveArgb: Int = 0,
  /** Horizontal content padding in dp (peek of adjacent pages). */
  @Property(5) val contentPaddingDp: Int = 0,
  // children holds the highest tag → stays the trailing slot in the generated composable.
  @Children(6) val children: () -> Unit,
)

@Widget(52)
public data class VerticalPager(
  @Children(1) val children: () -> Unit,
)

/** Wraps [children] with a plain tooltip showing [text]. */
@Widget(53)
public data class Tooltip(
  @Property(1) val text: String,
  @Children(2) val children: () -> Unit,
)

/**
 * Styled container — a Box with an optional solid/gradient background, rounded
 * corners, fixed size, padding, and content alignment. Enables real-world UI
 * (gradient headers, colored avatar circles, icon chips) that the Material
 * widgets alone can't express. All dimensions in dp; colors are ARGB ints.
 */
@Widget(54)
public data class StyledBox(
  /** Solid background ARGB; 0 => transparent. */
  @Property(1) val colorArgb: Int = 0,
  /** Linear-gradient start ARGB; 0 (with [gradientEndArgb] 0) => no gradient. */
  @Property(2) val gradientStartArgb: Int = 0,
  /** Linear-gradient end ARGB. */
  @Property(3) val gradientEndArgb: Int = 0,
  /** Corner radius in dp; a large value on a square box yields a circle. */
  @Property(4) val cornerRadiusDp: Int = 0,
  /** Fixed width in dp; 0 => wrap content. */
  @Property(5) val widthDp: Int = 0,
  /** Fixed height in dp; 0 => wrap content. */
  @Property(6) val heightDp: Int = 0,
  /** Uniform inner padding in dp. */
  @Property(7) val paddingDp: Int = 0,
  /** Whether width should fill the parent. */
  @Property(8) val fillWidth: Boolean = false,
  /** Content alignment: 0 topStart,1 topCenter,2 topEnd,3 centerStart,4 center,
   *  5 centerEnd,6 bottomStart,7 bottomCenter,8 bottomEnd. */
  @Property(9) val contentAlignment: Int = 0,
  /** Vertical draw offset in dp (signed). Negative pulls the box UP over the
   *  previous sibling — e.g. a rounded white sheet lapping over a header. */
  @Property(10) val offsetYDp: Int = 0,
  /** Drop-shadow elevation in dp (cards); 0 => flat. */
  @Property(11) val elevationDp: Int = 0,
  /** Border color ARGB; 0 => no border. */
  @Property(12) val borderColorArgb: Int = 0,
  /** Border width in dp (with [borderColorArgb]). */
  @Property(13) val borderWidthDp: Int = 0,
  /** Tap handler; non-null makes the whole box clickable (rows, buttons, back). */
  @Property(14) val onClick: (() -> Unit)? = null,
  // children must hold the HIGHEST tag so the generated composable keeps it as
  // the trailing slot (enables `StyledBox(...) { content }` lambda syntax).
  @Children(15) val children: () -> Unit,
)

@Modifier(-4_543_827) // reserved tag, inherited from ui-basic Reuse.
public object Reuse

// ---------------------------------------------------------------------------
// Batch 9: universal visual modifiers (unscoped — usable on ANY widget).
// Authored guest-side like native Compose: `SomeWidget(modifier =
// Modifier.background(argb).cornerRadius(16).padding(8))`. Applied host-side in
// the order declared. Spike set (background/corner/padding); the full set
// (border, shadow, offset, blur, size, fillWidth, clickable) follows once proven.
// ---------------------------------------------------------------------------

/** Solid background fill. ARGB int. */
@Modifier(60)
public data class Background(
  val colorArgb: Int,
)

/** Clip to rounded corners of [radiusDp]. */
@Modifier(61)
public data class CornerRadius(
  val radiusDp: Int,
)

/** Uniform inner padding of [allDp]. */
@Modifier(62)
public data class Padding(
  val allDp: Int,
)

/** Border stroke. Rounds to a preceding [CornerRadius] (any order). */
@Modifier(63)
public data class Border(
  val widthDp: Int,
  val colorArgb: Int,
)

/** Drop-shadow elevation. Rounds to a preceding [CornerRadius] (any order). */
@Modifier(64)
public data class Shadow(
  val elevationDp: Int,
)

/** Fixed size; 0 on an axis => leave it unconstrained. */
@Modifier(65)
public data class Size(
  val widthDp: Int,
  val heightDp: Int,
)

/** Fill the parent's available width. */
@Modifier(66)
public object FillWidth

/** Translate drawing by (x, y) dp (signed). */
@Modifier(67)
public data class Offset(
  val xDp: Int,
  val yDp: Int,
)

/** Gaussian blur of [radiusDp]. */
@Modifier(68)
public data class Blur(
  val radiusDp: Int,
)

/** Opacity, 0..100 (%). */
@Modifier(69)
public data class Alpha(
  val pct: Int,
)

/** Vertical linear-gradient background ([startArgb] top → [endArgb] bottom). */
@Modifier(70)
public data class GradientBackground(
  val startArgb: Int,
  val endArgb: Int,
)
