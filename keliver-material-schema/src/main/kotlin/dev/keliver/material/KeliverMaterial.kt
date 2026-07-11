/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material

import dev.keliver.layout.RedwoodLayout
import dev.keliver.lazylayout.RedwoodLazyLayout
import dev.keliver.material.api.TextFieldState
import dev.keliver.material.api.TextSpan
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
    // Batch 10: loading + animation
    Shimmer::class,
    AnimatedVisibility::class,
    // Batch 11: theming + dark mode
    Theme::class,
    // Batch 12: interactivity wrapper (clickable can't be a modifier — keliver U6:
    // modifiers are non-addressable value objects with no event channel).
    Clickable::class,
    // Batch 13: inline rich text (AnnotatedString-style multi-style/gradient runs).
    RichText::class,
    // Batch 14: animated travelling-comet border (host-side Compose animation).
    AnimatedBorder::class,
    // Batch 15: native-parity essentials (Icon by name, Material3 ListItem).
    Icon::class,
    ListItem::class,
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
    FillMaxHeight::class,
    FillMaxSize::class,
    Offset::class,
    Blur::class,
    Alpha::class,
    // Batch 9b: finer geometry (clickable stays a widget @Property — keliver
    // U6: lambda-typed modifier props break Kotlin/JS codegen).
    CornerRadiusEach::class,
    PaddingEach::class,
    AspectRatio::class,
    Rotate::class,
    Scale::class,
    AnimateContentSize::class,
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
  /** Container (fill) color ARGB; 0 => theme primary. */
  @Property(4) val containerArgb: Int = 0,
  /** Content (label) color ARGB; 0 => theme onPrimary. */
  @Property(5) val contentArgb: Int = 0,
  /** Corner radius in dp; 0 => Material default pill shape. */
  @Property(6) val cornerRadiusDp: Int = 0,
)

// ---------------------------------------------------------------------------
// Batch 0 seed widgets (Material3 defaults).
// ---------------------------------------------------------------------------

/** Styled text: weight, size, color, alignment, line clamp + rich-text controls. */
@Widget(5)
public data class StyledText(
  @Property(1) val text: String,
  @Property(2) val fontSize: Int = 14,
  @Property(3) val bold: Boolean = false,
  /** ARGB int; 0 => theme `onSurface`. */
  @Property(4) val colorArgb: Int = 0,
  /** 0 start, 1 center, 2 end, 3 justify. */
  @Property(5) val align: Int = 0,
  /** 0 => unlimited. */
  @Property(6) val maxLines: Int = 0,
  /** Overflow when clamped: 0 clip, 1 ellipsis ("…"), 2 visible. */
  @Property(7) val overflow: Int = 0,
  /** Line height in sp; 0 => font default. */
  @Property(8) val lineHeightSp: Int = 0,
  /** Letter spacing in 1/100 sp (e.g. 50 => 0.5sp); 0 => default. */
  @Property(9) val letterSpacingX100: Int = 0,
  @Property(10) val italic: Boolean = false,
  @Property(11) val underline: Boolean = false,
  @Property(12) val strikethrough: Boolean = false,
  /** Explicit weight 100..900; 0 => use [bold]. */
  @Property(13) val weight: Int = 0,
  /** Theme color role (resolved from the enclosing [Theme]); 0 => use [colorArgb].
   *  1 primary, 2 onPrimary, 3 secondary, 4 surface, 5 onSurface,
   *  6 onSurfaceVariant, 7 background, 8 error, 9 outline. */
  @Property(14) val colorRole: Int = 0,
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
  /** Tint ARGB applied as a `ColorFilter` (mono icons, like native
   *  `fromDrawable(color = …)`); 0 => draw the image's own colors. */
  @Property(8) val tintArgb: Int = 0,
)

/** Vertically scrollable column container. */
@Widget(14)
public data class ScrollableColumn(
  @Children(1) val children: () -> Unit,
)

/** Material3 modal bottom sheet; content in [children]. Lifts above the IME so
 *  text fields stay visible while editing. */
@Widget(15)
public data class BottomSheet(
  @Property(1) val visible: Boolean,
  @Property(2) val onDismiss: (() -> Unit)? = null,
  /** Uniform inner content padding in dp. */
  @Property(3) val contentPaddingDp: Int = 0,
  @Children(4) val children: () -> Unit,
)

// ---------------------------------------------------------------------------
// Batch 1: buttons & inputs (Material3).
// ---------------------------------------------------------------------------

@Widget(16)
public data class OutlinedButton(
  @Property(1) val text: String,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onClick: (() -> Unit)? = null,
  /** Optional leading icon (network URL), drawn before [text] like a Material
   *  button with `Icon()` + `Spacer()` content. Empty => text only. */
  @Property(4) val iconUrl: String = "",
  /** Leading-icon size in dp. */
  @Property(5) val iconSizeDp: Int = 18,
  /** Container (fill) color ARGB; 0 => transparent (default outlined look). */
  @Property(6) val containerArgb: Int = 0,
  /** Content (text + icon tint) color ARGB; 0 => theme default. */
  @Property(7) val contentArgb: Int = 0,
  /** Border color ARGB; 0 => theme default outline. */
  @Property(8) val borderArgb: Int = 0,
  /** Corner radius in dp; 0 => Material default pill shape. */
  @Property(9) val cornerRadiusDp: Int = 0,
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
  /** Floating label (Material). Empty => none. */
  @Property(4) val label: String = "",
  /** Leading text affix drawn inside the field (e.g. "₹"). Empty => none. */
  @Property(5) val leadingText: String = "",
  /** Soft-keyboard type: 0 text, 1 number, 2 decimal, 3 email, 4 phone, 5 password. */
  @Property(6) val keyboardType: Int = 0,
  @Property(7) val singleLine: Boolean = true,
  /** Max visible lines (multi-line when > 1 and not [singleLine]). */
  @Property(8) val maxLines: Int = 1,
  /** Error styling (red border/label) + show [supportingText] in error color. */
  @Property(9) val isError: Boolean = false,
  /** Helper / error message shown beneath the field. Empty => none. */
  @Property(10) val supportingText: String = "",
  /** Outline color ARGB when unfocused; 0 => theme default. */
  @Property(11) val borderArgb: Int = 0,
  /** Corner radius in dp; 0 => Material default. */
  @Property(12) val cornerRadiusDp: Int = 0,
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
  /** Draw the 2-stop gradient top→bottom (`Brush.verticalGradient`) instead of
   *  the default diagonal. Ignored when [gradientColorsArgb] is set. */
  @Property(15) val gradientVertical: Boolean = false,
  /** Multi-stop linear gradient (3+ colors / custom stops). When non-empty this
   *  supersedes [gradientStartArgb]/[gradientEndArgb]/[gradientVertical]. */
  @Property(16) val gradientColorsArgb: List<Int> = emptyList(),
  /** Stop offsets (0f..1f) for [gradientColorsArgb]; empty => even spacing. */
  @Property(17) val gradientStops: List<Float> = emptyList(),
  /** Direction for [gradientColorsArgb]: 0 diagonal TL→BR, 1 vertical T→B,
   *  2 horizontal L→R, 3 diagonal BL→TR. */
  @Property(18) val gradientDirection: Int = 0,
  // Per-corner radii in dp; -1 => fall back to [cornerRadiusDp]. Use for
  // top-only rounded panels etc.
  @Property(19) val cornerTopStartDp: Int = -1,
  @Property(20) val cornerTopEndDp: Int = -1,
  @Property(21) val cornerBottomStartDp: Int = -1,
  @Property(22) val cornerBottomEndDp: Int = -1,
  // children must hold the HIGHEST tag so the generated composable keeps it as
  // the trailing slot (enables `StyledBox(...) { content }` lambda syntax).
  @Children(23) val children: () -> Unit,
)

// ---------------------------------------------------------------------------
// Batch 10: loading + animation.
// ---------------------------------------------------------------------------

/** Animated shimmer placeholder (skeleton loading). */
@Widget(55)
public data class Shimmer(
  /** 0 => fill width. */
  @Property(1) val widthDp: Int = 0,
  @Property(2) val heightDp: Int = 16,
  @Property(3) val cornerRadiusDp: Int = 8,
)

/** Animates [children] in/out (fade + expand/shrink) as [visible] toggles. */
@Widget(56)
public data class AnimatedVisibility(
  @Property(1) val visible: Boolean = true,
  @Children(2) val children: () -> Unit,
)

/**
 * Server/host-driven theme. Wraps [content] in a Material3 theme so EVERY widget
 * (Button, Card, Switch, …) and any [StyledText]/[StyledBox] using a `colorRole`
 * draws from one palette — incl. dark mode. Each color is ARGB; 0 => keep the
 * base scheme's default (so the server can override just a few roles).
 */
@Widget(57)
public data class Theme(
  /** Start from the dark base color scheme. */
  @Property(1) val dark: Boolean = false,
  @Property(2) val primaryArgb: Int = 0,
  @Property(3) val onPrimaryArgb: Int = 0,
  @Property(4) val secondaryArgb: Int = 0,
  @Property(5) val onSecondaryArgb: Int = 0,
  @Property(6) val surfaceArgb: Int = 0,
  @Property(7) val onSurfaceArgb: Int = 0,
  @Property(8) val surfaceVariantArgb: Int = 0,
  @Property(9) val onSurfaceVariantArgb: Int = 0,
  @Property(10) val backgroundArgb: Int = 0,
  @Property(11) val onBackgroundArgb: Int = 0,
  @Property(12) val errorArgb: Int = 0,
  @Property(13) val outlineArgb: Int = 0,
  @Children(14) val content: () -> Unit,
)

/**
 * Wraps [content] to make it tappable — the keliver answer to
 * `Modifier.clickable { }` (which can't be a modifier: keliver U6 — modifiers are
 * non-addressable serialized values, while events need an addressable node).
 * Lean (no styling): `Clickable(onClick = { … }) { AnyWidget() }`.
 */
@Widget(58)
public data class Clickable(
  @Property(1) val onClick: (() -> Unit)? = null,
  @Children(2) val content: () -> Unit,
)

/**
 * Inline rich text — one text block whose [spans] each carry their own weight,
 * color, size, or gradient fill (the keliver equivalent of an `AnnotatedString`
 * built from multiple `SpanStyle`s). Use for "Make your UPI ID **sound like
 * you**"-style mixed styling within a single flowing line.
 */
@Widget(59)
public data class RichText(
  @Property(1) val spans: List<TextSpan>,
  /** Base font size (sp); spans with [TextSpan.fontSize] 0 inherit it. */
  @Property(2) val fontSize: Int = 14,
  /** Base bold for spans that don't set their own. */
  @Property(3) val bold: Boolean = false,
  /** 0 start, 1 center, 2 end, 3 justify. */
  @Property(4) val align: Int = 0,
  /** Line height in sp; 0 => font default. */
  @Property(5) val lineHeightSp: Int = 0,
  /** 0 => unlimited. */
  @Property(6) val maxLines: Int = 0,
  /** Base color ARGB for spans that don't set their own; 0 => onSurface. */
  @Property(7) val colorArgb: Int = 0,
)

/**
 * Container with a family of host-side animated border/edge effects (no per-frame
 * protocol traffic); [children] render inside. The keliver answer to bespoke
 * `drawWithCache` border animations. Choose via [effect]:
 *  - 0 comet — a bright segment travels the perimeter over a faint base ring.
 *  - 1 gradientSweep — the whole ring is a rotating gradient ("AI glow").
 *  - 2 pulse — the ring breathes in width + alpha.
 *  - 3 marchingAnts — a dashed ring marches around ([segmentLenDp] = dash length).
 *  - 4 glow — a soft outer halo pulses behind a crisp ring.
 * [colorsArgb] (2+) supplies a gradient palette for comet/sweep; otherwise
 * [cometColorArgb] is used solid.
 */
@Widget(60)
public data class AnimatedBorder(
  @Property(1) val cornerRadiusDp: Int = 8,
  @Property(2) val strokeWidthDp: Int = 1,
  /** Faint always-on ring ARGB; 0 => [cometColorArgb] at 15% alpha. */
  @Property(3) val baseColorArgb: Int = 0,
  /** Bright travelling-segment / ring ARGB (used solid when [colorsArgb] empty). */
  @Property(4) val cometColorArgb: Int = 0xFFFC14AB.toInt(),
  /** Full loop duration in ms. */
  @Property(5) val durationMs: Int = 1800,
  /** comet: min visible segment length; marchingAnts: dash length (dp). */
  @Property(6) val segmentLenDp: Int = 50,
  @Property(7) val onClick: (() -> Unit)? = null,
  /** 0 comet, 1 gradientSweep, 2 pulse, 3 marchingAnts, 4 glow. */
  @Property(8) val effect: Int = 0,
  /** 2+ ARGB stops => gradient palette for comet/sweep; empty => [cometColorArgb]. */
  @Property(9) val colorsArgb: List<Int> = emptyList(),
  @Children(10) val children: () -> Unit,
)

/**
 * Material icon by NAME from the curated `Icons.Filled` set (see the host's
 * MaterialIcons.kt for supported names, e.g. "Add", "Search", "Settings",
 * "Favorite"). Unknown names render a neutral placeholder.
 */
@Widget(61)
public data class Icon(
  @Property(1) val name: String,
  @Property(2) val sizeDp: Int = 24,
  /** ARGB tint; 0 => the ambient content color. */
  @Property(3) val tintArgb: Int = 0,
  @Property(4) val contentDescription: String = "",
)

/** Material3 list row: headline + optional supporting/overline text and leading/trailing icons (by name). */
@Widget(62)
public data class ListItem(
  @Property(1) val headline: String,
  @Property(2) val supporting: String = "",
  @Property(3) val overline: String = "",
  @Property(4) val leadingIcon: String = "",
  @Property(5) val trailingIcon: String = "",
  @Property(6) val onClick: (() -> Unit)? = null,
)

@Modifier(-4_543_827) // reserved tag, inherited from ui-basic Reuse.
public object Reuse

/** Animate this widget's own size changes (`Modifier.animateContentSize`). */
@Modifier(79)
public object AnimateContentSize

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

/** Per-corner clip radius (dp), clockwise from top-start. */
@Modifier(72)
public data class CornerRadiusEach(
  val topStartDp: Int,
  val topEndDp: Int,
  val bottomEndDp: Int,
  val bottomStartDp: Int,
)

/** Directional inner padding (dp). */
@Modifier(73)
public data class PaddingEach(
  val startDp: Int,
  val topDp: Int,
  val endDp: Int,
  val bottomDp: Int,
)

/** Constrain to a width:height [ratio] (e.g. 1.78 for 16:9). */
@Modifier(74)
public data class AspectRatio(
  val ratio: Float,
)

/** Fill the parent's available height. */
@Modifier(75)
public object FillMaxHeight

/** Fill the parent's available width AND height. */
@Modifier(76)
public object FillMaxSize

/** Rotate by [degrees] clockwise. */
@Modifier(77)
public data class Rotate(
  val degrees: Int,
)

/** Uniform scale, [percent] (100 = 1.0×). */
@Modifier(78)
public data class Scale(
  val percent: Int,
)
