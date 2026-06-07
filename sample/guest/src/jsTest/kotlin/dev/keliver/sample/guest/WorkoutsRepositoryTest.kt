/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.guest

import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import dev.keliver.http.KeliverHttp
import dev.keliver.sample.shared.FavoritesSnapshot
import dev.keliver.sample.shared.HostFavoritesStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The payoff of Style B's separation: the business logic lives in a plain
 * [WorkoutsRepository], so it's unit-tested here with fake host services — no
 * Compose, no widget tree, no device. The fakes stand in for the API
 * (HostHttpProvider) and the database (HostFavoritesStore).
 */
class WorkoutsRepositoryTest {

  private val fakeApi = object : HostHttpProvider {
    override suspend fun execute(request: HttpRequest): HttpResponse = HttpResponse(
      status = 200,
      body = """[{"id":"1","name":"Push Day","durationMin":45},""" +
        """{"id":"2","name":"Leg Day","durationMin":60}]""",
    )
  }

  private val fakeDb = object : HostFavoritesStore {
    val favs = mutableSetOf("2") // workout 2 starts favorited
    override suspend fun favorites(): FavoritesSnapshot = FavoritesSnapshot(favs.toList())
    override suspend fun setFavorite(id: String, favorite: Boolean) {
      if (favorite) favs.add(id) else favs.remove(id)
    }
  }

  private val repo = WorkoutsRepository(KeliverHttp(fakeApi), fakeDb)

  @Test
  fun load_mergesFavoritesFromDb() = runTest {
    val rows = repo.load(query = "")
    assertEquals(2, rows.size)
    assertEquals(false, rows.first { it.workout.id == "1" }.isFavorite)
    assertEquals(true, rows.first { it.workout.id == "2" }.isFavorite)
  }

  @Test
  fun load_filtersByQuery() = runTest {
    val rows = repo.load(query = "leg")
    assertEquals(1, rows.size)
    assertEquals("Leg Day", rows.single().workout.name)
  }

  @Test
  fun toggleFavorite_writesToDb() = runTest {
    repo.toggleFavorite(id = "1", favorite = true)
    assertTrue("1" in fakeDb.favs)
  }
}
