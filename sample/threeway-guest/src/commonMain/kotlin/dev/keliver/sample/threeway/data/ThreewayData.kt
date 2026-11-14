/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.threeway.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The three-way benchmark's data (docs/THREE_WAY_BENCHMARK.md): one JSON shape served by the
 * fixture API to all three forms. The native form and the guest parse it with this same code
 * (kotlinx.serialization: on ART for native, in QuickJS for the guest); the web form parses the
 * same bytes with JSON.parse.
 */

@Serializable
public data class Item(
  val id: Int,
  val title: String,
  val subtitle: String,
  val amount: String,
  val status: String,
  val address: String = "",
  val note: String = "",
  val reference: String = "",
)

@Serializable
public data class ItemsPage(
  /** The request's sequence number, echoed by the server: each response is distinguishable on screen. */
  val seq: Int,
  val count: Int,
  val items: List<Item>,
)

private val json = Json { ignoreUnknownKeys = true }

public fun parseItems(body: String): ItemsPage = json.decodeFromString(ItemsPage.serializer(), body)

/**
 * Where the screens' data comes from. Each form implements it as an app would: native with OkHttp
 * on an IO thread, the guest through the host's `HostHttp`. Both parse with [parseItems] and record
 * the `response` and `parsed` marks.
 */
public interface ThreewayApi {
  public suspend fun items(size: String, seq: Int): ItemsPage
}

/**
 * Timing marks, in epoch milliseconds on the caller's clock (`System.currentTimeMillis()` on the
 * host, `Date.now()` in the guest and the web page: all read the phone's one wall clock).
 */
public interface ThreewayMarks {
  public fun now(): Long

  /** Records that [name] happened for request or navigation [seq] at [atMillis]. */
  public fun mark(seq: Int, name: String, atMillis: Long = now())

  /**
   * The screen's composition has applied [screen] (`list` or `detail`) with [seq]. Called from a
   * `DisposableEffect` (the apply phase), so the native host sees it before the frame draws and the
   * guest before its change batch is sent.
   */
  public fun composed(screen: String, seq: Int)
}
