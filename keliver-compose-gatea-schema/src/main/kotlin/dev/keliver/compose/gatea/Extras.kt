/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.gatea

import dev.keliver.schema.Property
import dev.keliver.schema.Widget

/*
 * Keliver extras (docs/COMPOSE_GENERATOR_DESIGN.md §14): hand-written widgets that are not Compose
 * API, imported from dev.keliver.compose.extras so a reader sees they are not Compose. Tags from 500
 * up; the generated group's lock assigns from 1.
 */

/** An animated skeleton placeholder, drawn by the host (its renderer is the one hand-written one). */
@Widget(500)
public data class ExtraShimmer(
  @Property(1) val shape: dev.keliver.compose.ui.graphics.Shape,
  @Property(1000) val modifierEvent: ((kotlin.Int) -> Unit)? = null,
)
