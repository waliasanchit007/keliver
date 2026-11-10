/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.material.icons

/**
 * The guest mirror of `androidx.compose.material.icons.Icons`. Each icon is an extension property
 * in its theme's package (`dev.keliver.compose.material.icons.filled.ChevronRight`), generated
 * from the icon group's allowlist.
 */
public object Icons {
  public object Filled
  public object Outlined
  public object Rounded
  public object AutoMirrored {
    public object Filled
    public object Outlined
  }
  public val Default: Filled = Filled
}
