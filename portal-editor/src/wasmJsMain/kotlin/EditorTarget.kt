/*
 * E14: opening a component to edit its insides. The editor edits one document
 * at a time — a screen, or a component opened from it (and a component opened
 * from that one) — and every document request names which. The path back is
 * the breadcrumb: the screen, then each component opened in turn.
 */
import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.WidgetNode

/** The document the editor has open: [screen]'s, or the last of [components] opened from it. */
internal data class EditTarget(val project: String, val screen: String, val components: List<String> = emptyList()) {
  val component: String? get() = components.lastOrNull()

  /** The relay's query for this document (`/doc`, `/ops`, `/undo`, `/redo`, `/doc-events`). */
  val query: String get() = "project=$project&" + (component?.let { "component=$it" } ?: "screen=$screen")

  /** This document's identity: its write-back status and its State panel values are its own. */
  val key: String get() = "$project/" + (component?.let { "component:$it" } ?: screen)

  /** [name] opened from here; opening one already on the path goes back to it (no cycles). */
  fun open(name: String): EditTarget {
    val at = components.indexOf(name)
    return copy(components = if (at >= 0) components.take(at + 1) else components + name)
  }

  /** Back to the crumb at [depth] (0 = the screen). */
  fun backTo(depth: Int): EditTarget = copy(components = components.take(depth.coerceIn(0, components.size)))

  /** The breadcrumb's labels: the screen, then each component opened in turn. */
  val crumbs: List<String> get() = listOf(screen) + components
}

/** Whether [node] is an instance of a project component (double-click and Enter open it). */
internal fun isComponentInstance(node: WidgetNode?, components: ComponentRegistry): Boolean =
  node != null && components.spec(node.type) != null
