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
 * Minimal schema for the sample app — exactly two widgets:
 *
 *  - `Box`: a single-child container. Children slot 1 receives one
 *    child widget (the guest composes one `Text` into it).
 *  - `Text`: a leaf widget with a single `text` property.
 *
 * The schema codegen plugins consume this declaration and produce:
 *
 *  - `:shared-widget`  → host- and guest-side widget interfaces
 *  - `:shared-modifier` → layout modifier types (empty here — we
 *    declare no `@Modifier` types)
 *  - `:shared-protocol-host` → host-side protocol adapters that
 *    decode Redwood protocol messages into widget mutations
 *  - `:shared-protocol-guest` → guest-side widget system factory
 *    that the guest uses to compose the tree
 *
 * Widget IDs are wire-stable — once assigned and shipped, never
 * renumbered. Tag 1 / tag 2 are forever Box / Text in this sample.
 *
 * To add a new widget, append `Foo::class` to `members` AND assign
 * the next free tag in `@Widget(...)`. To remove one, leave the slot
 * empty (don't reuse the tag) and bump the major version.
 */
@Schema(
  members = [
    Box::class,
    Text::class,
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
)
