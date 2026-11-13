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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Gate B-d: an "Item detail" screen written from its spec. The guest copy differs only in imports
// (SameSourceTest.onlyImportsDiffer).
private val history = listOf(
  "Nov 3" to 20,
  "Nov 1" to -5,
  "Oct 29" to -8,
  "Oct 27" to 30,
  "Oct 24" to -5,
)

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun ItemDetail(onBack: () -> Unit, onAdjust: () -> Unit, onReorder: () -> Unit) {
  Column(Modifier.fillMaxSize()) {
    TopAppBar(
      title = { Text("Item") },
      navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
      },
    )
    LazyColumn(
      modifier = Modifier.weight(1f),
      contentPadding = PaddingValues(16.dp),
    ) {
      item(key = "header") {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
          Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
              contentAlignment = Alignment.Center,
            ) {
              Icon(Icons.Filled.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.padding(start = 16.dp)) {
              Text("Steel bolts M6", style = MaterialTheme.typography.titleMedium)
              Text("SKU 10-4471", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
      item(key = "stats") {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          StatCard("In stock", "42", Modifier.weight(1f))
          StatCard("Reorder at", "10", Modifier.weight(1f))
        }
      }
      item(key = "historyTitle") {
        Text(
          "History",
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
      }
      itemsIndexed(history, key = { _, entry -> entry.first }) { index, (date, change) ->
        Column {
          if (index > 0) HorizontalDivider()
          Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(date, style = MaterialTheme.typography.bodyMedium)
            Text(
              if (change > 0) "+$change" else "$change",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = if (change > 0) Color(0xFF2E7D32) else Color(0xFFC62828),
            )
          }
        }
      }
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedButton(onClick = onAdjust, modifier = Modifier.weight(1f)) { Text("Adjust") }
      Button(onClick = onReorder, modifier = Modifier.weight(1f)) { Text("Reorder") }
    }
  }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
  Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
    Column(Modifier.padding(16.dp)) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(value, style = MaterialTheme.typography.headlineSmall)
    }
  }
}
