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
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.foundation.layout.height
import dev.keliver.compose.foundation.lazy.LazyColumn
import dev.keliver.compose.foundation.lazy.LazyListState
import dev.keliver.compose.material3.Text
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.unit.dp

/** A long guest list for the windowing test: [count] fixed-height rows. */
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun LongList(state: LazyListState, count: Int) {
  LazyColumn(Modifier.fillMaxSize(), state = state) {
    items(count, key = { it }) { index ->
      Text("Row $index", Modifier.height(40.dp))
    }
  }
}
