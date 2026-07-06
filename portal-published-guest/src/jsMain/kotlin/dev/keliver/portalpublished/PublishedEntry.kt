package dev.keliver.portalpublished

import androidx.compose.runtime.Composable
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portalpublished.logic.MainPresenter
import dev.keliver.portalpublished.screens.MainScreen

/**
 * HAND-OWNED (M6): wires each screen to its presenter. Publish compiles this
 * project AS-IS — the canonical screens/ files are the source, no export step.
 */
@Composable
fun PublishedEntry(sql: HostSqlDriver?) {
  MainScreen(MainPresenter(sql))
}
