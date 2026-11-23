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
package dev.keliver.portal.render

import androidx.compose.runtime.Composable
import dev.keliver.portal.WidgetNode

/**
 * C1/C3: the settable hook the generated [RenderNode] `else` branch calls when
 * a node's type isn't a primitive widget — i.e. a project-component instance.
 * The editor sets this to a transparent macro-expander (substitute the
 * instance's props/events into the definition body and RenderNode that) or an
 * opaque/cycle placeholder. Null (default, e.g. tests) falls back to the
 * "unknown widget" marker. Single-project by construction: one editor page
 * previews one project, matching the editor's other module-level state.
 *
 * Devices never invoke this — they render the compiled composable directly.
 */
public var componentPreview: (@Composable (WidgetNode) -> Unit)? = null

/**
 * K1: the scope-aware form of [componentPreview], preferred when set. [scope]
 * is the content-lambda receiver of the instance's layout parent (RowScope /
 * ColumnScope / BoxScope) or null; the expansion is rendered with it so the
 * component root sits in its parent exactly as the compiled call does.
 */
public var componentPreviewInScope: (@Composable (node: WidgetNode, scope: Any?) -> Unit)? = null
