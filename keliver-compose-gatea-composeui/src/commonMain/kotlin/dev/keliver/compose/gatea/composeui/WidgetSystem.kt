/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.gatea.composeui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.keliver.compose.gatea.widget.KeliverComposeGateAWidgetSystem
import dev.keliver.material.widget.KeliverMaterialWidgetSystem

/**
 * The Gate A widget system: the generated group's renderers plus a host's existing
 * keliver-material system (legacy widgets keep rendering, with any host overrides it has).
 */
public fun keliverComposeGateAWidgetSystem(
  material: KeliverMaterialWidgetSystem<@Composable (Modifier) -> Unit>,
): KeliverComposeGateAWidgetSystem<@Composable (Modifier) -> Unit> = KeliverComposeGateAWidgetSystem(
  KeliverComposeGateA = ComposeUiKeliverComposeGateAWidgetFactory(),
  RedwoodLayout = material.RedwoodLayout,
  RedwoodLazyLayout = material.RedwoodLazyLayout,
  KeliverMaterial = material.KeliverMaterial,
)
