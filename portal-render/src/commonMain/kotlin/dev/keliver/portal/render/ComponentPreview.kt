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
var componentPreview: (@Composable (WidgetNode) -> Unit)? = null
