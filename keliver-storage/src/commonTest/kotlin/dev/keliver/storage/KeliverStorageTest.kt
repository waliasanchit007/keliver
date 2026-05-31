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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class KeliverStorageTest {

  @Serializable
  data class Quote(val id: Int, val text: String)

  /**
   * In-memory [HostStorage] stub. Mirrors the contract: `set(key, null)`
   * removes; `keys(prefix)` returns matching keys.
   */
  private class InMemoryStorage(
    initial: Map<String, String> = emptyMap(),
  ) : HostStorage {
    private val backing = initial.toMutableMap()

    override suspend fun get(key: String): String? = backing[key]

    override suspend fun set(key: String, value: String?) {
      if (value == null) backing.remove(key) else backing[key] = value
    }

    override suspend fun keys(prefix: String): List<String> =
      backing.keys.filter { it.startsWith(prefix) }
  }

  @Test
  fun get_returns_null_for_missing_key() = runTest {
    val storage = KeliverStorage(InMemoryStorage())
    val result: Quote? = storage.get("nope")
    assertNull(result)
  }

  @Test
  fun set_then_get_round_trips_a_typed_value() = runTest {
    val storage = KeliverStorage(InMemoryStorage())
    val quote = Quote(42, "Hello")

    storage.set("quote", quote)
    val result: Quote? = storage.get("quote")

    assertEquals(quote, result)
  }

  @Test
  fun set_null_removes_the_entry() = runTest {
    val storage = KeliverStorage(InMemoryStorage())
    storage.set("quote", Quote(1, "x"))
    storage.set<Quote?>("quote", null)

    val result: Quote? = storage.get("quote")
    assertNull(result)
  }

  @Test
  fun remove_clears_the_entry() = runTest {
    val storage = KeliverStorage(InMemoryStorage())
    storage.set("quote", Quote(1, "x"))
    storage.remove("quote")

    val result: Quote? = storage.get("quote")
    assertNull(result)
  }

  @Test
  fun keys_filters_by_prefix() = runTest {
    val storage = KeliverStorage(InMemoryStorage())
    storage.set("quote:1", Quote(1, "a"))
    storage.set("quote:2", Quote(2, "b"))
    storage.set("setting:theme", Quote(0, "dark"))

    val quoteKeys = storage.keys("quote:")

    assertEquals(2, quoteKeys.size)
    assertTrue(quoteKeys.contains("quote:1"))
    assertTrue(quoteKeys.contains("quote:2"))
  }

  @Test
  fun keys_with_empty_prefix_returns_all_keys() = runTest {
    val storage = KeliverStorage(InMemoryStorage())
    storage.set("a", Quote(1, "a"))
    storage.set("b", Quote(2, "b"))

    val all = storage.keys()

    assertEquals(setOf("a", "b"), all.toSet())
  }

  @Test
  fun malformed_payload_throws_on_typed_get() = runTest {
    // Pre-populate with a value that doesn't parse as a Quote.
    val storage = KeliverStorage(
      InMemoryStorage(mapOf("quote" to "not-json-at-all")),
    )

    assertFailsWith<Exception> {
      storage.get<Quote>("quote")
    }
  }

  @Test
  fun custom_json_config_is_used() = runTest {
    val strictJson = Json { ignoreUnknownKeys = false }
    val backing = InMemoryStorage(
      mapOf("quote" to """{"id":1,"text":"x","extra":"surprise"}"""),
    )
    val storage = KeliverStorage(backing, strictJson)

    assertFailsWith<Exception> {
      storage.get<Quote>("quote")
    }
  }
}
