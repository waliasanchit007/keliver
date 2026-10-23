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

/**
 * G1 (feedback round 2): the order of this session's edits across the documents it edited — a
 * screen and the components opened from it. Undo steps the newest edit wherever it was made
 * (the relay keeps one stack per document); before, ⌘Z back on a screen undid an older edit of
 * the screen and left the component edit just made in place.
 */
internal class SessionHistory(private val limit: Int = 200) {
  private val undos = ArrayDeque<EditTarget>()
  private val redos = ArrayDeque<EditTarget>()

  /** An edit of [target] was accepted: it is the newest to undo, and nothing is left to redo. */
  fun edited(target: EditTarget) {
    undos.addLast(target)
    while (undos.size > limit) undos.removeFirst()
    redos.clear()
  }

  /** The document the next undo steps: the newest edit's, else (a history from before a reload) [current]. */
  fun undoTarget(current: EditTarget): EditTarget = undos.lastOrNull() ?: current

  fun redoTarget(current: EditTarget): EditTarget = redos.lastOrNull() ?: current

  /** [target]'s newest edit was undone (or could not be: either way it is no longer next). */
  fun undone(target: EditTarget) {
    if (undos.lastOrNull() == target) undos.removeLast()
    redos.addLast(target)
  }

  fun redone(target: EditTarget) {
    if (redos.lastOrNull() == target) redos.removeLast()
    undos.addLast(target)
  }

  /** Whether the next undo (or redo) is of a document other than [current]. */
  fun undoElsewhere(current: EditTarget): Boolean = undos.lastOrNull()?.let { it != current } == true
  fun redoElsewhere(current: EditTarget): Boolean = redos.lastOrNull()?.let { it != current } == true
}
