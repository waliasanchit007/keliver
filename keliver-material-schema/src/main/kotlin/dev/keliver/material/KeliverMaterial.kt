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
    Reuse::class,
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

@Modifier(-4_543_827) // reserved tag, inherited from ui-basic Reuse.
public object Reuse
