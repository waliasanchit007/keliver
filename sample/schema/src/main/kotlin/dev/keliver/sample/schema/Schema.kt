/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.schema

import dev.keliver.schema.Children
import dev.keliver.schema.Property
import dev.keliver.schema.Schema
import dev.keliver.schema.Widget

/**
 * A small, Compose-like widget set for the sample app. The goal is that
 * writing a guest screen reads almost exactly like writing Compose:
 *
 * ```kotlin
 * Column {
 *   Text(text = "Hello, Keliver!")
 *   Spacer(height = 16)
 *   Row {
 *     Text(text = "Left"); Text(text = "Right")
 *   }
 *   Button(text = "Tap", onClick = { count++ })
 * }
 * ```
 *
 * Widgets:
 *
 *  - `Box`    : single-slot container (children stack on the z-axis).
 *  - `Column` : vertical layout (children top-to-bottom).
 *  - `Row`    : horizontal layout (children left-to-right).
 *  - `Text`   : a leaf with a `text` property.
 *  - `Button` : a clickable leaf with a `text` label + an `onClick`
 *               event routed back to the guest over Zipline.
 *  - `Spacer` : a fixed vertical gap of `height` dp.
 *
 * The schema codegen plugins consume this declaration and produce:
 *
 *  - `:shared-widget`        → host- and guest-side widget interfaces
 *  - `:shared-modifier`      → layout modifier types (empty here)
 *  - `:shared-protocol-host` → host-side protocol adapters
 *  - `:shared-protocol-guest`→ guest-side widget system factory
 *
 * For each widget, the guest gets a generated `@Composable fun Foo(...)`
 * and the host implements `Foo<R>` in `CmpWidgetFactory`.
 *
 * Widget IDs (the `@Widget(n)` tag) are wire-stable — once shipped,
 * never renumber. To add a widget, append `Foo::class` to `members`
 * AND give it the next free tag. To remove one, retire the tag (never
 * reuse it) and bump the major version.
 */
@Schema(
  members = [
    Box::class,
    Text::class,
    Column::class,
    Row::class,
    Button::class,
    Spacer::class,
    Card::class,
    TextField::class,
    Checkbox::class,
  ],
)
public interface SampleSchema

@Widget(1)
public data class Box(
  @Children(1) val children: () -> Unit,
)

@Widget(2)
public data class Text(
  @Property(1) val text: String,
  /** Font size in sp (Compose `fontSize`). */
  @Property(2) val fontSize: Int = 14,
  /** Bold weight when true (Compose `fontWeight = Bold`). */
  @Property(3) val bold: Boolean = false,
)

/** Vertical layout — children stack top-to-bottom (Compose `Column`). */
@Widget(3)
public data class Column(
  @Children(1) val children: () -> Unit,
)

/** Horizontal layout — children sit left-to-right (Compose `Row`). */
@Widget(4)
public data class Row(
  @Children(1) val children: () -> Unit,
)

/**
 * A clickable button with a text label. [onClick] is an event: the host
 * invokes it on tap and Keliver routes the call back over the Zipline
 * bridge to the guest's lambda. Nullable so the guest may omit it.
 */
@Widget(5)
public data class Button(
  @Property(1) val text: String,
  @Property(2) val onClick: (() -> Unit)?,
)

/** A fixed vertical gap of [height] dp (Compose `Spacer`). */
@Widget(6)
public data class Spacer(
  @Property(1) val height: Int,
)

/** A Material card container — children render inside, with elevation. */
@Widget(7)
public data class Card(
  @Children(1) val children: () -> Unit,
)

/**
 * Single-line text input. [value] is host-owned editing state; each
 * keystroke fires [onValueChange] so the guest can react and write back
 * the new [value] — exactly the Compose `TextField(value, onValueChange)`
 * contract.
 */
@Widget(8)
public data class TextField(
  @Property(1) val value: String,
  @Property(2) val placeholder: String = "",
  @Property(3) val onValueChange: ((String) -> Unit)? = null,
)

/** Two-state checkbox; [onCheckedChange] fires on toggle. */
@Widget(9)
public data class Checkbox(
  @Property(1) val checked: Boolean,
  @Property(2) val onCheckedChange: ((Boolean) -> Unit)? = null,
)
