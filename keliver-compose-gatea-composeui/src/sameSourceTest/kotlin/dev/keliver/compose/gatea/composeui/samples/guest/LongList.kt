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
package dev.keliver.compose.gatea.composeui.samples.guest

import androidx.compose.runtime.Composable
import dev.keliver.compose.foundation.layout.PaddingValues
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.foundation.layout.height
import dev.keliver.compose.foundation.layout.width
import dev.keliver.compose.foundation.lazy.LazyColumn
import dev.keliver.compose.foundation.lazy.LazyListState
import dev.keliver.compose.foundation.lazy.LazyRow
import dev.keliver.compose.material3.Text
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.unit.dp

/**
 * A long guest list for the windowing tests: [count] rows of 40 dp, or 120 dp for the first when
 * [tallFirstRow]; laid out from the bottom when [reverseLayout]; with [padding] dp of content
 * padding above and below.
 */
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun LongList(
  state: LazyListState,
  count: Int,
  reverseLayout: Boolean = false,
  padding: Int = 0,
  tallFirstRow: Boolean = false,
) {
  LazyColumn(
    Modifier.fillMaxSize(),
    state = state,
    contentPadding = PaddingValues(vertical = padding.dp),
    reverseLayout = reverseLayout,
  ) {
    items(count, key = { it }) { index ->
      Text("Row $index", Modifier.height(if (tallFirstRow && index == 0) 120.dp else 40.dp))
    }
  }
}

/** A long horizontal guest list: [count] cards 40 dp wide and 80 dp high; the row takes their height. */
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun LongRow(state: LazyListState, count: Int) {
  LazyRow(state = state) {
    items(count, key = { it }) { index ->
      Text("Card $index", Modifier.width(40.dp).height(80.dp))
    }
  }
}
