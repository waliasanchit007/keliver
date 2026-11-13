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
package dev.keliver.compose.gatea.composeui.samples.native

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Gate B's same-source sample: a listing screen with a top bar, a lazy list of cards, buttons and
// dividers. The guest copy differs only in imports (SameSourceTest.onlyImportsDiffer).
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun SampleOrders(orders: List<Pair<String, String>>, onBack: () -> Unit, onOpen: (String) -> Unit) {
  Column(Modifier.fillMaxSize()) {
    TopAppBar(
      title = { Text("Orders", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
      },
      actions = {
        IconButton(onClick = {}) { Icon(Icons.Filled.Search, contentDescription = "Search") }
      },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    LazyColumn(
      modifier = Modifier.weight(1f),
      state = rememberLazyListState(),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item(key = "summary") {
        Surface(
          modifier = Modifier.fillParentMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
          border = BorderStroke(1.dp, Color(0xFFD0D0D0)),
        ) {
          Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
              rememberVectorPainter(Icons.Filled.Info),
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(8.dp))
            Text("${orders.size} orders need attention", style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
      items(orders, key = { it.first }) { order ->
        Card(
          onClick = { onOpen(order.first) },
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = Color.White),
          elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
          Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(Icons.Filled.Receipt, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
              Text(order.first, style = MaterialTheme.typography.titleMedium)
              Text(order.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
          }
          HorizontalDivider()
          Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onOpen(order.first) }) { Text("Details") }
            OutlinedButton(onClick = {}) { Text("Track") }
          }
        }
      }
      item {
        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
      }
    }
  }
}
