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
package dev.keliver.compose.core.composeui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.keliver.compose.root.widget.KeliverComposeWidgetFactory
import dev.keliver.compose.root.widget.KeliverComposeWidgetSystem

/**
 * The generated root's widget system with only this group's renderers: the tests' own, so core's
 * tests never depend on keliver-compose-root-composeui, which depends on core (MS4-03).
 * keliverComposeWidgetSystem() there is the one a host uses.
 */
internal fun coreWidgetSystem(): KeliverComposeWidgetSystem<@Composable (Modifier) -> Unit> = KeliverComposeWidgetSystem(
  KeliverCompose = TestRootWidgetFactory,
  KeliverComposeCore = ComposeUiKeliverComposeCoreWidgetFactory(),
)

/** The root has no widgets of its own: each group is a dependency. */
private object TestRootWidgetFactory : KeliverComposeWidgetFactory<@Composable (Modifier) -> Unit>
