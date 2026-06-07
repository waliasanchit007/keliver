/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.shared

import app.cash.zipline.ZiplineService
import kotlinx.serialization.Serializable

/**
 * Host-owned "database" for favorite workout ids.
 *
 * The guest runs in QuickJS and can't open a SQLite file, so **persistence
 * lives on the host** (in the sample, an in-memory store; in production back
 * it with SQLDelight / Room / DataStore) and is exposed to the guest as a
 * Zipline service. The guest's repository treats it as a **local data source**
 * alongside the remote API — that's how a server-driven screen combines an
 * API call with a database source.
 *
 * Reads return a single [FavoritesSnapshot] rather than a `List<@Serializable>`
 * to stay clear of the suspend-bind hang (KNOWN_BUGS U1) — the same
 * single-object shape trick keliver-http uses with `HttpResponse`.
 */
public interface HostFavoritesStore : ZiplineService {
  public suspend fun favorites(): FavoritesSnapshot
  public suspend fun setFavorite(id: String, favorite: Boolean)
}

@Serializable
public data class FavoritesSnapshot(val ids: List<String>)
