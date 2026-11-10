/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.ui.graphics.drawscope

import dev.keliver.compose.ui.graphics.FilterQuality

/**
 * The guest's `DrawScope`: only its constants for now. Drawing (`Canvas`, `drawBehind`) is
 * recorded in the guest and replayed by the host in Gate B (docs/COMPOSE_GENERATOR_DESIGN.md §10).
 */
public interface DrawScope {
  public companion object {
    public val DefaultFilterQuality: FilterQuality = FilterQuality.Low
  }
}
