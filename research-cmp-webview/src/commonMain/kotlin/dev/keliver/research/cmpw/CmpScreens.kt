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
package dev.keliver.research.cmpw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The screens' first navigation number minus one (the host's driver accepts only newer ones). */
const val NAV_BASE = 100_000

class Item(val id: Int, val title: String, val subtitle: String, val amount: String)

/** The list's rows: 50 by default (the cold start and navigation); the scrolling series uses more. */
fun orderItems(count: Int): List<Item> = List(count) { i ->
  Item(
    id = i,
    title = "Order #${1_000 + i}",
    subtitle = "Customer ${(i * 37) % 97} · ${1 + i % 7} items",
    amount = "₹${(i * 7_919) % 10_000}.${(i * 13) % 100}",
  )
}

/**
 * The research screens: a Material 3 list of [count] rows (an icon, two lines of text, a button) and
 * a detail. [mark] is called in the apply phase of each screen's composition with the screen's name
 * and navigation number; the host (native) or the page (web) turns it into "drawn".
 */
@Composable
fun CmpApp(count: Int = 50, mark: (String, Int) -> Unit) {
  MaterialTheme(colorScheme = lightColorScheme()) {
    val rows = remember(count) { orderItems(count) }
    var selected by remember { mutableStateOf<Item?>(null) }
    var nav by remember { mutableIntStateOf(NAV_BASE) }
    val s = selected
    if (s == null) {
      ListScreen(rows, nav, mark) {
        nav++
        selected = it
      }
    } else {
      DetailScreen(s, nav, mark) {
        nav++
        selected = null
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreen(rows: List<Item>, seq: Int, mark: (String, Int) -> Unit, onOpen: (Item) -> Unit) {
  DisposableEffect(seq) {
    mark("list", seq)
    onDispose {}
  }
  Scaffold(topBar = { TopAppBar(title = { Text("Orders") }) }) { padding ->
    LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
      items(rows, key = { it.id }) { item ->
        ListItem(
          modifier = Modifier.clickable { onOpen(item) },
          leadingContent = {
            Icon(if (item.id % 2 == 0) Icons.Filled.ShoppingCart else Icons.Filled.AccountCircle, contentDescription = null)
          },
          headlineContent = { Text(item.title) },
          supportingContent = { Text(item.subtitle) },
          trailingContent = {
            Button(onClick = { onOpen(item) }) { Text(item.amount) }
          },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(item: Item, seq: Int, mark: (String, Int) -> Unit, onBack: () -> Unit) {
  DisposableEffect(seq) {
    mark("detail", seq)
    onDispose {}
  }
  var note by remember { mutableStateOf("") }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(item.title) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Text(item.title, style = MaterialTheme.typography.headlineMedium)
          Text(item.subtitle, style = MaterialTheme.typography.bodyLarge)
          Text(item.amount, style = MaterialTheme.typography.displaySmall)
        }
      }
      for (line in 1..5) {
        ListItem(
          leadingContent = { Icon(Icons.Filled.Favorite, contentDescription = null) },
          headlineContent = { Text("Line $line of ${item.title}") },
          supportingContent = { Text("Quantity ${line + item.id % 3}") },
        )
        HorizontalDivider()
      }
      OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {}) { Text("Pay") }
        OutlinedButton(onClick = onBack) { Text("Cancel") }
      }
    }
  }
}
