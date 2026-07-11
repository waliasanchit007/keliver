package dev.keliver.portalpublished.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portal.sql.PortalSqlDriver
import dev.keliver.portalpublished.screens.FeedScreenBindings
import dev.keliver.portalpublished.screens.Note
import kotlinx.coroutines.launch

/**
 * HAND-OWNED (M6/M7): produces the Field Notes feed's Bindings (Style B).
 * v2 (P2): the TextField's typed text lands here via onDraftChange(value),
 * and openNote(value) hands the tapped row's id to the hand-owned nav.
 */
@Composable
fun FeedPresenter(sql: HostSqlDriver?, onOpenNote: (String) -> Unit): FeedScreenBindings {
  val scope = rememberCoroutineScope()
  var notesList by remember { mutableStateOf<List<Note>>(emptyList()) }
  var draftText by remember { mutableStateOf("") }
  var loaded by remember { mutableStateOf(false) }
  val store = remember { sql?.let { NotesStore(PortalSqlDriver(it)) } }

  suspend fun refresh() {
    notesList = store?.all() ?: emptyList()
    loaded = true
  }

  LaunchedEffect(Unit) {
    if (store != null) {
      store.createTable()
      refresh()
    } else {
      loaded = true
    }
  }

  return object : FeedScreenBindings {
    override val subtitle: String = when {
      !loaded -> "loading notes…"
      notesList.isEmpty() -> "Persisted in SQLite, shipped OTA"
      else -> "${notesList.size} note${if (notesList.size == 1) "" else "s"} · persisted in SQLite via OTA data layer"
    }

    override val draft: String = draftText

    override val isEmpty: Boolean = loaded && notesList.isEmpty()

    override val notes: List<Note> = notesList

    override fun onDraftChange(value: String) {
      draftText = value
    }

    override fun addNote() {
      scope.launch {
        val n = (store?.count() ?: 0L) + 1L
        val typed = draftText.trim()
        store?.insert(
          title = if (typed.isEmpty()) "Note #$n" else typed,
          body = if (typed.isEmpty()) "Captured from the portal-built feed." else "Typed in the portal-authored TextField.",
          time = "entry $n",
        )
        draftText = ""
        refresh()
      }
    }

    override fun clearAll() {
      scope.launch {
        store?.clear()
        refresh()
      }
    }

    override fun openNote(value: String) = onOpenNote(value)
  }
}
