/*
 * Copyright (C) 2026 Keliver contributors.
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
package dev.keliver.compose.root.composeui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.compose.coil.widget.KeliverComposeCoilWidgetFactory
import dev.keliver.compose.core.composeui.ComposeUiKeliverComposeCoreWidgetFactory
import dev.keliver.compose.root.widget.KeliverComposeWidgetFactory
import dev.keliver.compose.root.widget.KeliverComposeWidgetSystem
import dev.keliver.widget.Widget

/**
 * The generated root's widget system on Compose UI: the core group's generated renderers, and each
 * optional group a host ships (docs/MS4_PLAN.md, MS4-03; Q1).
 *
 * [coil] is the coil group's renderers, `ComposeUiKeliverComposeCoilWidgetFactory()` from
 * keliver-compose-coil-composeui, or null for a host built without Coil. A group left out is
 * absent: a guest's widget from it is reported as unknown once per tag (the host protocol's
 * [ProtocolMismatchHandler][dev.keliver.protocol.host.ProtocolMismatchHandler], which a Treehouse
 * app passes to its EventListener), draws nothing, and the rest of the tree renders.
 *
 * Unlike the prototype's, it takes no legacy keliver-material system: the generated root does not
 * depend on the legacy schemas (MS4-01).
 */
public fun keliverComposeWidgetSystem(
  coil: KeliverComposeCoilWidgetFactory<@Composable (Modifier) -> Unit>? = null,
): KeliverComposeWidgetSystem<@Composable (Modifier) -> Unit> = KeliverComposeWidgetSystem(
  KeliverCompose = RootWidgetFactory,
  KeliverComposeCore = ComposeUiKeliverComposeCoreWidgetFactory(),
  KeliverComposeCoil = coil,
  absentWidget = ::AbsentWidget,
)

/** The root has no widgets of its own: each group is a dependency. */
private object RootWidgetFactory : KeliverComposeWidgetFactory<@Composable (Modifier) -> Unit>

/** Stands in for a widget of an absent group: it draws nothing. */
private class AbsentWidget : Widget<@Composable (Modifier) -> Unit> {
  override val value: @Composable (Modifier) -> Unit = {}
  override var modifier: KeliverModifier = KeliverModifier
}
