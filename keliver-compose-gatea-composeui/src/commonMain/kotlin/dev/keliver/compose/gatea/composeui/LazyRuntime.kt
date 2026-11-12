/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.compose.gatea.composeui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * The host half of a generated lazy list (docs/COMPOSE_GENERATOR_DESIGN.md §16), written by hand
 * once: the real `LazyColumn`/`LazyRow` gets the whole item count, of which the guest composed a
 * window; items outside it are placeholders until the window reaches them.
 */

/**
 * The items of a generated lazy list: [before] placeholders, the guest's window (each an
 * `ExtraLazyItem` whose children render in the real item scope, so `fillParentMaxWidth` and
 * several root composables behave as in Compose), then [after] placeholders.
 */
internal fun LazyListScope.lazyWindow(window: ScopedChildren, before: Int, after: Int) {
  val items = window.widgets
  items(
    count = before + items.size + after,
    key = { index -> (items.getOrNull(index - before) as? ComposeUiExtraLazyItem)?.key ?: "\u0000$index" },
  ) { index ->
    val item = items.getOrNull(index - before) as? ComposeUiExtraLazyItem
    if (item != null) item.content.Render(this) else LazyPlaceholder()
  }
}

/**
 * An item the guest has not composed yet: shown only while a fast scroll outruns the window. A
 * fixed height (Material's minimum list-item height) keeps the viewport's item count, and so the
 * window the guest composes next, close to what real items need.
 */
@Composable
private fun LazyPlaceholder() {
  Spacer(Modifier.height(48.dp))
}

/** Reports the host's viewport to the guest when its first or last visible item changes. */
@Composable
internal fun ReportLazyViewport(state: LazyListState, report: (firstIndex: Int, lastIndex: Int, firstOffset: Int) -> Unit) {
  val latest by rememberUpdatedState(report)
  val viewport by remember(state) {
    derivedStateOf {
      val visible = state.layoutInfo.visibleItemsInfo
      (visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: 0)
    }
  }
  LaunchedEffect(state, viewport) {
    latest(viewport.first, viewport.second, state.firstVisibleItemScrollOffset)
  }
}
