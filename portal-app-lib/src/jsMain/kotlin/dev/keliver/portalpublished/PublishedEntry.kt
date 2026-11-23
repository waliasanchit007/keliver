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
  // P3-12 capability purity: presenters speak sqldelight's SqlDriver only —
  // the Zipline adapter (PortalSqlDriver over HostSqlDriver) is DEVICE wiring,
  // applied here at the edge. Same driver, same queries: behavior unchanged.
  val driver = remember(sql) { sql?.let { dev.keliver.portal.sql.PortalSqlDriver(it) } }
  var openNoteId by remember { mutableStateOf<String?>(null) }
  val id = openNoteId
  if (id == null) {
    FeedScreen(FeedPresenter(driver, onOpenNote = { openNoteId = it }))
  } else {
    DetailScreen(DetailPresenter(driver, id, onBack = { openNoteId = null }))
  }
}
