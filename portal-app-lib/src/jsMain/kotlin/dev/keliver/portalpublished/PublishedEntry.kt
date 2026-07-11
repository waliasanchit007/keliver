package dev.keliver.portalpublished

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portalpublished.logic.DetailPresenter
import dev.keliver.portalpublished.logic.FeedPresenter
import dev.keliver.portalpublished.screens.DetailScreen
import dev.keliver.portalpublished.screens.FeedScreen

/**
 * HAND-OWNED (M6): wires each screen to its presenter. Publish compiles this
 * project AS-IS — the canonical screens/ files are the source, no export step.
 * The published app is the Field Notes dogfood (screens/feed.kt + detail.kt);
 * navigation is a hand-owned route state — the portal authors screens, the
 * app owns how they connect (P2: item-carrying openNote(id) drives it).
 */
@Composable
fun PublishedEntry(sql: HostSqlDriver?) {
  var openNoteId by remember { mutableStateOf<String?>(null) }
  val id = openNoteId
  if (id == null) {
    FeedScreen(FeedPresenter(sql, onOpenNote = { openNoteId = it }))
  } else {
    DetailScreen(DetailPresenter(sql, id, onBack = { openNoteId = null }))
  }
}
