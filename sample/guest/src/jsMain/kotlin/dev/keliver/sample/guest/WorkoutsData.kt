/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.guest

import dev.keliver.http.KeliverHttp
import dev.keliver.sample.shared.HostFavoritesStore
import kotlinx.serialization.Serializable

/** Remote workout, decoded from the API response (guest owns this type). */
@Serializable
public data class Workout(
  val id: String,
  val name: String,
  val durationMin: Int,
)

/** A row of UI state = the remote workout + its local favorite flag. */
public data class WorkoutRow(val workout: Workout, val isFavorite: Boolean)

/**
 * The single place that talks to BOTH data sources and holds the business
 * logic. It is a **plain class** (no Compose) — so the logic is unit-testable
 * without any UI, which is the whole point of the separated architecture.
 *
 *  - API source:  keliver-http  →  GET /workouts
 *  - DB source:   HostFavoritesStore  →  host-owned persistence over Zipline
 *  - logic:       fetch, merge favorites, apply the search filter
 */
public class WorkoutsRepository(
  private val http: KeliverHttp,
  private val favorites: HostFavoritesStore,
) {
  /** Fetch from the API, merge the DB favorites, filter by [query]. */
  public suspend fun load(query: String): List<WorkoutRow> {
    val all = http.get<List<Workout>>("/workouts")        // API call
    val favIds = favorites.favorites().ids.toSet()         // DB read
    return all
      .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
      .map { WorkoutRow(it, isFavorite = it.id in favIds) }
  }

  public suspend fun toggleFavorite(id: String, favorite: Boolean) {
    favorites.setFavorite(id, favorite)                    // DB write
  }
}

/** The screen's UI state — the "Model" the presenter produces. */
public data class WorkoutsModel(
  val query: String,
  val loading: Boolean,
  val error: String?,
  val rows: List<WorkoutRow>,
)

/** UI → presenter events. */
public sealed interface WorkoutsEvent {
  public data class Search(val query: String) : WorkoutsEvent
  public data class ToggleFavorite(val id: String, val favorite: Boolean) : WorkoutsEvent
}
