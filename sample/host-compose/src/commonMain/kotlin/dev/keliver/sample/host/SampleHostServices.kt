/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.host

import app.cash.zipline.Zipline
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import dev.keliver.sample.shared.FavoritesSnapshot
import dev.keliver.sample.shared.HostFavoritesStore

/**
 * The sample's host-side services, shared by the Android **and** iOS hosts.
 * Both call [bind] from their `TreehouseApp.Spec.bindServices`, so the guest
 * Workouts screen works identically on each platform.
 *
 * Production note: a real app backs these with its own stack — Ktor/OkHttp for
 * [HostHttpProvider] (base URL + auth) and SQLDelight/Room/DataStore for the
 * favorites store. Here they're in-memory stand-ins so the example runs with no
 * backend. Held in a singleton object so the bound services aren't GC'd.
 */
public object SampleHostServices {

  /** Generic HTTP transport — the guest owns endpoints; this serves canned JSON. */
  private val http: HostHttpProvider = object : HostHttpProvider {
    override suspend fun execute(request: HttpRequest): HttpResponse {
      println("SDUI: host keliver-http execute ${request.method} ${request.path}")
      val body = when (request.path) {
        "/workouts" ->
          """[{"id":"1","name":"Push Day","durationMin":45},""" +
            """{"id":"2","name":"Leg Day","durationMin":60},""" +
            """{"id":"3","name":"Pull Day","durationMin":50},""" +
            """{"id":"4","name":"Core Blast","durationMin":20}]"""
        else ->
          """{"message":"Hello from the host via keliver-http — you called ${request.method} ${request.path}"}"""
      }
      return HttpResponse(status = 200, body = body)
    }
  }

  /** In-memory "database" — favorite workout ids (real impl: SQLDelight/Room). */
  private val favorites: HostFavoritesStore = object : HostFavoritesStore {
    private val favs = linkedSetOf<String>()
    override suspend fun favorites(): FavoritesSnapshot = FavoritesSnapshot(favs.toList())
    override suspend fun setFavorite(id: String, favorite: Boolean) {
      if (favorite) favs.add(id) else favs.remove(id)
      println("SDUI: host DB setFavorite $id=$favorite -> $favs")
    }
  }

  /** Bind both services so the guest can `take()` them. */
  public fun bind(zipline: Zipline) {
    zipline.bind<HostHttpProvider>("http", http)
    zipline.bind<HostFavoritesStore>("favorites", favorites)
  }
}
