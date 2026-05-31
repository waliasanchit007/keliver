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
package dev.keliver.storage

import app.cash.zipline.ZiplineService

/**
 * Generic string key/value storage exposed by the host to the guest.
 * The host implements this once with its preferred backend
 * (`DataStore<Preferences>` on Android, `NSUserDefaults` on iOS, a
 * file-backed JSON blob, an SQLite key/value table, etc.) and binds a
 * single `"storage"` service. Guests then persist any JSON-serializable
 * value through [KeliverStorage].
 *
 * Reference adapter (Android `DataStore<Preferences>`) — copy / paste:
 *
 * ```
 * private class DataStoreHostStorage(
 *     private val store: DataStore<Preferences>,
 * ) : HostStorage {
 *     override suspend fun get(key: String): String? =
 *         store.data.first()[stringPreferencesKey(key)]
 *
 *     override suspend fun set(key: String, value: String?) {
 *         store.edit { prefs ->
 *             val pk = stringPreferencesKey(key)
 *             if (value == null) prefs.remove(pk) else prefs[pk] = value
 *         }
 *     }
 *
 *     override suspend fun keys(prefix: String): List<String> =
 *         store.data.first().asMap().keys
 *             .map { it.name }
 *             .filter { it.startsWith(prefix) }
 * }
 * ```
 *
 * Bind it the standard way:
 *
 * ```
 * zipline.bind<HostStorage>("storage", retain(DataStoreHostStorage(dataStore)))
 * ```
 *
 * Encryption, migrations, and key-namespacing all stay in the host
 * adapter; the wire surface is deliberately minimal.
 */
public interface HostStorage : ZiplineService {
  /** Returns the stored value for [key], or null if not present. */
  public suspend fun get(key: String): String?

  /**
   * Stores [value] under [key]. Passing `null` removes the entry, matching
   * the [KeliverStorage.remove] convention.
   */
  public suspend fun set(key: String, value: String?)

  /**
   * Returns all keys with the given [prefix]. Default is the empty prefix
   * (every key). Useful for scanning a namespaced section like
   * `"savedQuote:"`.
   */
  public suspend fun keys(prefix: String = ""): List<String>
}
