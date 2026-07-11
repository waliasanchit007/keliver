package dev.keliver.portalpublished

import androidx.compose.runtime.Composable
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portalpublished.logic.FeedPresenter
import dev.keliver.portalpublished.screens.FeedScreen

/**
 * HAND-OWNED (M6): wires each screen to its presenter. Publish compiles this
 * project AS-IS — the canonical screens/ files are the source, no export step.
 * The published app is the Field Notes dogfood (screens/feed.kt).
 */
@Composable
fun PublishedEntry(sql: HostSqlDriver?) {
  FeedScreen(FeedPresenter(sql))
}
