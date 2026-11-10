/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.extras

import androidx.compose.runtime.Composable
import dev.keliver.compose.gatea.compose.ExtraShimmer
import dev.keliver.compose.internal.splitModifierEvents
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.graphics.RectangleShape
import dev.keliver.compose.ui.graphics.Shape

/**
 * A Keliver extra, not Compose API: an animated skeleton placeholder the host draws with an
 * infinite transition. Size it with modifiers, as a Box.
 */
@Composable
public fun Shimmer(modifier: Modifier = Modifier, shape: Shape = RectangleShape) {
  val split = splitModifierEvents(modifier)
  ExtraShimmer(shape = shape, modifierEvent = split.events, modifier = split.modifier)
}
