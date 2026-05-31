/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Guest-side typed wrapper over [HostStorage]. Encodes / decodes values
 * via kotlinx-serialization so adopters can persist any `@Serializable`
 * Kotlin type without writing per-type accessor services.
 *
 * Usage:
 *
 * ```
 * val storage = KonduitStorage(HostStorageBridge.instance!!)
 * val savedQuotes: List<Quote>? = storage.get("savedQuotes")
 * storage.set("savedQuotes", listOf(Quote(1, "hello")))
 * storage.remove("savedQuotes")
 * ```
 *
 * Threading: every method is `suspend` and delegates to the host's
 * [HostStorage], which itself routes through Zipline. Cancellation and
 * exception propagation match the rest of the host-service surface.
 */
public class KonduitStorage(
  public val provider: HostStorage,
  public val json: Json = Json { ignoreUnknownKeys = true },
) {
  /**
   * Returns the stored value for [key] deserialized as type [T], or null
   * if the key isn't present. Throws if the stored payload exists but
   * doesn't parse as [T] — call the raw [HostStorage.get] directly if
   * you need to handle malformed payloads gracefully.
   */
  public suspend inline fun <reified T> get(key: String): T? {
    val raw = provider.get(key) ?: return null
    return json.decodeFromString(raw)
  }

  /**
   * Stores [value] under [key]. Passing `null` removes the entry — the
   * round-trip with [get] then returns null.
   */
  public suspend inline fun <reified T> set(key: String, value: T?) {
    provider.set(key, value?.let { json.encodeToString(it) })
  }

  /**
   * Removes the entry under [key]. Equivalent to `set<Any?>(key, null)`
   * but reads more naturally at the call site.
   */
  public suspend fun remove(key: String) {
    provider.set(key, null)
  }

  /**
   * Returns every key with the given [prefix]. Pass the empty string
   * (the default) to enumerate the entire keyspace.
   */
  public suspend fun keys(prefix: String = ""): List<String> =
    provider.keys(prefix)
}
