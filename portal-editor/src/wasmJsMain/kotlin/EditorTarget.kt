/*
 * E14: opening a component to edit its insides. The editor edits one document
 * at a time — a screen, or a component opened from it (and a component opened
 * from that one) — and every document request names which. The path back is
 * the breadcrumb: the screen, then each component opened in turn.
 */
import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.WidgetNode
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 * G1 (feedback round 2): the order of this session's edits across the documents it edited on one
 * screen — the screen and the components opened from it. Undo steps the newest edit wherever it
 * was made (the relay keeps one stack per document); before, ⌘Z back on a screen undid an older
 * edit of the screen and left the component edit just made in place. Documents are told apart by
 * their [EditTarget.key] (a component is the same document however it was opened). Each screen
 * has its own history, kept with the relay's stacks across a reload (H2: [encode], [load]).
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

  /** [target]'s newest edit was undone: it can be redone. */
  fun undone(target: EditTarget) {
    if (undos.lastOrNull()?.key == target.key) undos.removeLast()
    redos.addLast(target)
  }

  fun redone(target: EditTarget) {
    if (redos.lastOrNull()?.key == target.key) redos.removeLast()
    undos.addLast(target)
  }

  /** Review 3: the relay had nothing to step in [target] (re-read, restarted): that entry is gone, nothing moves. */
  fun skipped(target: EditTarget, undo: Boolean) {
    val from = if (undo) undos else redos
    if (from.lastOrNull()?.key == target.key) from.removeLast()
  }

  /** Another screen (or project) was opened: its edits start a history of their own. */
  fun clear() { undos.clear(); redos.clear() }

  /** H2: this history as text, to keep across a reload: `undo` and `redo`, each a list of documents. */
  fun encode(): String = kotlinx.serialization.json.buildJsonObject {
    fun list(q: ArrayDeque<EditTarget>) = kotlinx.serialization.json.JsonArray(
      q.map { t -> kotlinx.serialization.json.JsonArray((listOf(t.project, t.screen) + t.components).map { kotlinx.serialization.json.JsonPrimitive(it) }) },
    )
    put("undo", list(undos))
    put("redo", list(redos))
  }.toString()

  /** H2: the history [text] ([encode]) held, replacing this one; an unreadable text is an empty history. */
  fun load(text: String?) {
    clear()
    val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text ?: return).jsonObject }.getOrNull() ?: return
    fun read(key: String, into: ArrayDeque<EditTarget>) {
      (o[key] as? kotlinx.serialization.json.JsonArray)?.forEach { e ->
        val parts = (e as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content } ?: return@forEach
        if (parts.size >= 2) into.addLast(EditTarget(parts[0], parts[1], parts.drop(2)))
      }
    }
    read("undo", undos)
    read("redo", redos)
  }

  /** Whether the next undo (or redo) is of a document other than [current]. */
  fun undoElsewhere(current: EditTarget): Boolean = undos.lastOrNull()?.let { it.key != current.key } == true
  fun redoElsewhere(current: EditTarget): Boolean = redos.lastOrNull()?.let { it.key != current.key } == true
}
