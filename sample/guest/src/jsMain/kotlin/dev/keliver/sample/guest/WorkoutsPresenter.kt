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
import androidx.compose.runtime.setValue
import dev.keliver.sample.schema.compose.Card
import dev.keliver.sample.schema.compose.Checkbox
import dev.keliver.sample.schema.compose.Column
import dev.keliver.sample.schema.compose.Row
import dev.keliver.sample.schema.compose.Spacer
import dev.keliver.sample.schema.compose.Text
import dev.keliver.sample.schema.compose.TextField
import kotlinx.coroutines.flow.Flow

/**
 * STYLE B — native-like SEPARATION.
 *
 * PRESENTER = the "ViewModel": events in, Model out. It owns the screen's
 * state and delegates the actual work to the repository (plain + unit-tested).
 * A pure `(events) -> Model` @Composable — the business logic never touches a
 * widget, so it reads like native MVVM and is testable in isolation.
 */
@Composable
public fun WorkoutsPresenter(events: Flow<WorkoutsEvent>, repo: WorkoutsRepository): WorkoutsModel {
  var query by remember { mutableStateOf("") }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  var rows by remember { mutableStateOf<List<WorkoutRow>>(emptyList()) }
  var reload by remember { mutableStateOf(0) }

  // UI events → state
  LaunchedEffect(Unit) {
    events.collect { e ->
      when (e) {
        is WorkoutsEvent.Search -> query = e.query
        is WorkoutsEvent.ToggleFavorite -> {
          repo.toggleFavorite(e.id, e.favorite)
          reload++
        }
      }
    }
  }

  // query / reload → load through the repository
  LaunchedEffect(query, reload) {
    loading = true
    error = null
    try {
      rows = repo.load(query)
    } catch (t: Throwable) {
      error = t.message
    }
    loading = false
  }

  return WorkoutsModel(query = query, loading = loading, error = error, rows = rows)
}

/**
 * STYLE B — UI: a pure render of the Model that emits Events. No state, no
 * logic, no coroutines — trivially previewable, swappable, and reviewable.
 */
@Composable
public fun WorkoutsScreen(model: WorkoutsModel, onEvent: (WorkoutsEvent) -> Unit) {
  Column {
    Text(text = "Workouts — presenter style", fontSize = 22, bold = true)
    Spacer(height = 8)
    TextField(
      value = model.query,
      placeholder = "Search workouts…",
      onValueChange = { onEvent(WorkoutsEvent.Search(it)) },
    )
    Spacer(height = 8)
    when {
      model.error != null -> Text(text = "Error: ${model.error}")
      model.loading -> Text(text = "Loading…")
      else -> Column {
        model.rows.forEach { row ->
          Card {
            Row {
              Checkbox(
                checked = row.isFavorite,
                onCheckedChange = { onEvent(WorkoutsEvent.ToggleFavorite(row.workout.id, it)) },
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
