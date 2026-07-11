package dev.keliver.portalpublished.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portal.sql.PortalSqlDriver
import dev.keliver.portalpublished.screens.DetailScreenBindings
import dev.keliver.portalpublished.screens.Note

/** HAND-OWNED: loads one note by rowid for the detail screen (P2 list->detail). */
@Composable
fun DetailPresenter(sql: HostSqlDriver?, noteId: String, onBack: () -> Unit): DetailScreenBindings {
  var note by remember { mutableStateOf<Note?>(null) }
  val store = remember { sql?.let { NotesStore(PortalSqlDriver(it)) } }

  LaunchedEffect(noteId) {
    note = store?.byId(noteId)
  }

  return object : DetailScreenBindings {
    override val title: String = note?.title ?: "loading…"
    override val body: String = note?.body ?: ""
    override val time: String = note?.time ?: ""
    override fun back() = onBack()
  }
}
