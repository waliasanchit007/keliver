/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
