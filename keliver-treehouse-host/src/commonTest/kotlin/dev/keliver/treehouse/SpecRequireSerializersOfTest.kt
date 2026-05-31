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
package dev.keliver.treehouse

import app.cash.zipline.Zipline
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Tests for [TreehouseApp.Spec.requireSerializersOf] (issue #30) — the
 * bulk variant of the existing `requireSerializerOf<T>` helper.
 */
class SpecRequireSerializersOfTest {

  @Serializable
  data class Quote(val text: String)

  @Serializable
  data class Wallpaper(val url: String)

  /** Not annotated `@Serializable` — exercises the failure path. */
  data class NotSerializable(val value: String)

  /** Minimal [TreehouseApp.Spec] subclass. */
  private class TestSpec(
    override val serializersModule: SerializersModule = EmptySerializersModule(),
  ) : TreehouseApp.Spec<FakeAppService>() {
    override val name: String = "test"
    override val manifestUrl =
      MutableStateFlow("https://example/manifest").asStateFlow()

    override suspend fun bindServices(
      treehouseApp: TreehouseApp<FakeAppService>,
      zipline: Zipline,
    ) = Unit

    override fun create(zipline: Zipline): FakeAppService =
      throw UnsupportedOperationException("not used in these tests")
  }

  private inline fun expectMissingSerializer(block: () -> Unit): MissingSerializerException {
    try {
      block()
    } catch (e: MissingSerializerException) {
      return e
    }
    fail("expected MissingSerializerException, got no exception")
  }

  @Test
  fun returns_empty_list_for_no_types() {
    assertThat(TestSpec().requireSerializersOf()).isEmpty()
  }

  @Test
  fun returns_serializers_for_serializable_types() {
    val serializers = TestSpec().requireSerializersOf(
      typeOf<Quote>(),
      typeOf<Wallpaper>(),
    )
    assertThat(serializers).hasSize(2)
  }

  @Test
  fun walks_into_generics_when_inner_is_serializable() {
    val serializers = TestSpec().requireSerializersOf(
      typeOf<List<Quote>>(),
      typeOf<Map<String, Quote>>(),
    )
    assertThat(serializers).hasSize(2)
  }

  @Test
  fun throws_for_unserializable_type() {
    val ex = expectMissingSerializer {
      TestSpec().requireSerializersOf(typeOf<NotSerializable>())
    }
    assertThat(ex.message ?: "").contains("NotSerializable")
  }

  @Test
  fun mixed_valid_and_invalid_throws_with_invalid_type_named() {
    val ex = expectMissingSerializer {
      TestSpec().requireSerializersOf(
        typeOf<Quote>(),
        typeOf<NotSerializable>(),
        typeOf<Wallpaper>(),
      )
    }
    assertThat(ex.message ?: "").contains("NotSerializable")
  }

  @Test
  fun error_message_references_canonical_causes_and_known_bugs() {
    val ex = expectMissingSerializer {
      TestSpec().requireSerializersOf(typeOf<NotSerializable>())
    }
    val message = ex.message ?: ""
    assertThat(message).contains("@kotlinx.serialization.Serializable")
    // The message references the serialization Gradle plugin and the
    // KNOWN_BUGS U3 entry — both are stable bits of the diagnostic.
    assertThat(message).contains("kotlin.plugin.serialization")
    assertThat(message).contains("KNOWN_BUGS.md U3")
  }

  @Test
  fun custom_serializersModule_resolves_contextual_types() {
    val customSerializer = Quote.serializer()
    val spec = TestSpec(
      serializersModule = SerializersModule {
        contextual(Quote::class, customSerializer)
      },
    )
    val serializers = spec.requireSerializersOf(typeOf<Quote>())
    assertThat(serializers).hasSize(1)
  }

  @Test
  fun preserves_input_order_in_returned_list() {
    val spec = TestSpec()
    val serializers = spec.requireSerializersOf(
      typeOf<Quote>(),
      typeOf<Wallpaper>(),
    )
    assertThat(serializers).hasSize(2)
    // Compare descriptor.serialName — equality on serializer instances
    // is fragile across kotlinx-serialization versions.
    assertThat(serializers[0].descriptor.serialName)
      .isEqualTo(spec.requireSerializerOf<Quote>().descriptor.serialName)
    assertThat(serializers[1].descriptor.serialName)
      .isEqualTo(spec.requireSerializerOf<Wallpaper>().descriptor.serialName)
  }
}
