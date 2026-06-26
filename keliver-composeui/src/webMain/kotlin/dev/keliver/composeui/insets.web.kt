/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.composeui

import androidx.compose.runtime.Composable
import dev.keliver.ui.Margin

// The browser canvas has no platform safe-area insets.
@Composable
public actual fun safeAreaInsets(): Margin = Margin.Zero
