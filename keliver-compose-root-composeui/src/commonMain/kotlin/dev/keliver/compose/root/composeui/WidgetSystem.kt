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
import dev.keliver.compose.core.composeui.ComposeUiKeliverComposeCoreWidgetFactory
import dev.keliver.compose.root.widget.KeliverComposeWidgetFactory
import dev.keliver.compose.root.widget.KeliverComposeWidgetSystem

/**
 * The generated root's widget system on Compose UI: the core group's generated renderers. Unlike the
 * prototype's, it takes no legacy keliver-material system: the generated root does not depend on the
 * legacy schemas (docs/MS4_PLAN.md, MS4-01 and MS4-03).
 */
public fun keliverComposeWidgetSystem(): KeliverComposeWidgetSystem<@Composable (Modifier) -> Unit> = KeliverComposeWidgetSystem(
  KeliverCompose = RootWidgetFactory,
  KeliverComposeCore = ComposeUiKeliverComposeCoreWidgetFactory(),
)

/** The root has no widgets of its own: each group is a dependency. */
private object RootWidgetFactory : KeliverComposeWidgetFactory<@Composable (Modifier) -> Unit>
