/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.guest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.keliver.sample.schema.compose.Card
import dev.keliver.sample.schema.compose.Checkbox
import dev.keliver.sample.schema.compose.Column
import dev.keliver.sample.schema.compose.Row
import dev.keliver.sample.schema.compose.Spacer
import dev.keliver.sample.schema.compose.Text
import dev.keliver.sample.schema.compose.TextField
import kotlinx.coroutines.launch

/**
 * STYLE A — Redwood-prescribed INLINE composable.
 *
 * State + business logic + UI all live in one @Composable. Fewest files,
 * least ceremony — but logic is fused to the UI, so it's only testable by
 * rendering the widget tree, and a complex screen tends to grow tangled
 * (note the coroutine scope, the reload counter, and the load/merge/filter
 * all sitting inside the view).
 */
@Composable
public fun WorkoutsScreenInline(repo: WorkoutsRepository) {
  val scope = rememberCoroutineScope()
  var query by remember { mutableStateOf("") }
  var rows by remember { mutableStateOf<List<WorkoutRow>?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var reload by remember { mutableStateOf(0) }

  LaunchedEffect(query, reload) {
    error = null
    rows = null
    rows = try {
      repo.load(query)
    } catch (t: Throwable) {
      error = t.message
      emptyList()
    }
  }

  Column {
    Text(text = "Workouts — inline style", fontSize = 22, bold = true)
    Spacer(height = 8)
    TextField(value = query, placeholder = "Search workouts…", onValueChange = { query = it })
    Spacer(height = 8)
    when {
      error != null -> Text(text = "Error: $error")
      rows == null -> Text(text = "Loading…")
      else -> Column {
        rows!!.forEach { row ->
          Card {
            Row {
              Checkbox(
                checked = row.isFavorite,
                onCheckedChange = { fav ->
                  // business action, inline: write to the DB then reload
                  scope.launch {
                    repo.toggleFavorite(row.workout.id, fav)
                    reload++
                  }
                },
              )
              Text(text = "${row.workout.name} · ${row.workout.durationMin} min")
            }
          }
          Spacer(height = 4)
        }
      }
    }
  }
}
