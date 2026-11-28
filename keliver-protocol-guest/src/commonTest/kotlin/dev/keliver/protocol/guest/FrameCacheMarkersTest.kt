/*
 * Copyright (C) 2026 Keliver contributors.
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
@file:OptIn(RedwoodCodegenApi::class, ExperimentalKeliverHostFeaturesApi::class)

package dev.keliver.protocol.guest

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.compose.CachedFrame
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.FrameCachePolicy
import dev.keliver.compose.FrameContent
import dev.keliver.compose.FrameScopeSink
import dev.keliver.compose.KeliverHostFeatures
import dev.keliver.compose.LocalFrameScopeSink
import dev.keliver.compose.LocalHostFeatures
import dev.keliver.compose.cached
import dev.keliver.compose.notCached
import dev.keliver.compose.rememberFrameScope
import dev.keliver.protocol.Change
import dev.keliver.protocol.FrameCacheTags
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.RedwoodVersion
import dev.keliver.widget.WidgetSystem
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** NF5 A2 / I8: without `keliver.cachedFrame@1` the guest's batches are what they are today. */
class FrameCacheMarkersTest {
  private object Plain : Modifier.UnscopedElement {
    override fun toString() = "Plain"
  }

  private val factory = object : ProtocolWidgetSystemFactory {
    override fun create(
      guestAdapter: GuestProtocolAdapter,
      mismatchHandler: ProtocolMismatchHandler,
    ): WidgetSystem<Unit> = object : WidgetSystem<Unit> {
      override fun apply(value: Unit, element: Modifier.UnscopedElement) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Modifier.Element> modifierTagAndSerializationStrategy(
      element: T,
    ): Pair<ModifierTag, SerializationStrategy<T>?> = when (element) {
      Plain -> ModifierTag(7) to null
      else -> throw AssertionError()
    } as Pair<ModifierTag, SerializationStrategy<T>?>
  }

  private fun compose(features: Set<String>, content: @Composable () -> Unit) {
    val recomposer = Recomposer(EmptyCoroutineContext)
    val composition = Composition(NoOpApplier, recomposer)
    composition.setContent {
      CompositionLocalProvider(LocalHostFeatures provides features) { content() }
    }
    composition.dispose()
    recomposer.close()
  }

  /** The screen of the sample: a root, a cached title and a notCached balance. */
  private fun screen(features: Set<String>, withCachedFrame: Boolean): List<String> {
    val roots = mutableListOf<Modifier>()
    compose(features) {
      if (withCachedFrame) {
        CachedFrame(
          policy = FrameCachePolicy(FrameContent.Shell, 12.hours),
          ready = true,
          paneTitle = "Orders",
        ) { root ->
          roots += Plain.then(root)
          roots += Plain.cached()
          roots += Plain.notCached()
        }
      } else {
        roots += Plain
        roots += Plain
        roots += Plain
      }
    }
    return roots.mapIndexed { index, modifier ->
      val adapter = DefaultGuestProtocolAdapter(hostVersion = RedwoodVersion("0.5.0"), widgetSystemFactory = factory)
      adapter.appendModifierChange(Id(index + 1), modifier)
      Json.encodeToString(ListSerializer(Change.serializer()), adapter.takeChanges())
    }
  }

  @Test fun withoutTheFeatureTheBatchesAreByteIdentical() {
    val without = screen(emptySet(), withCachedFrame = false)
    val with = screen(emptySet(), withCachedFrame = true)
    assertThat(with).isEqualTo(without)
    // A host that declares only the other feature gets the same.
    assertThat(screen(setOf(KeliverHostFeatures.PRESS_1), withCachedFrame = true)).isEqualTo(without)
    assertThat(screen(setOf(KeliverHostFeatures.FRAME_SCOPE_1), withCachedFrame = true)).isEqualTo(without)
  }

  @Test fun withTheFeatureTheOnlyDifferenceIsTheMarkers() {
    val without = screen(emptySet(), withCachedFrame = false)
    val with = screen(setOf(KeliverHostFeatures.CACHED_FRAME_1), withCachedFrame = true)

    // The plain element is the same in every batch; the markers are added after it.
    assertThat(with[0]).isEqualTo(
      """[{"type":"modifier","id":1,"elements":[[7],[-4600001,{"content":"shell","maxAgeMillis":43200000,"ready":true,"paneTitle":"Orders"}]]}]""",
    )
    assertThat(with[1]).isEqualTo("""[{"type":"modifier","id":2,"elements":[[7],[-4600002]]}]""")
    assertThat(with[2]).isEqualTo("""[{"type":"modifier","id":3,"elements":[[7],[-4600003]]}]""")
    // Remove the markers and the batches are today's.
    assertThat(without[0]).isEqualTo("""[{"type":"modifier","id":1,"elements":[[7]]}]""")
    for ((index, batch) in with.withIndex()) {
      assertThat(batch).contains(without[index].removeSuffix("]}]"))
    }
  }

  @Test fun theGuestsTagsAreFrameCacheTags() {
    val batches = screen(setOf(KeliverHostFeatures.CACHED_FRAME_1), withCachedFrame = true).joinToString()
    assertThat(batches).contains("[${FrameCacheTags.CACHED_FRAME},")
    assertThat(batches).contains("[${FrameCacheTags.CACHED}]")
    assertThat(batches).contains("[${FrameCacheTags.NOT_CACHED}]")
  }

  @Test fun frameScopeIsInertWithoutItsFeature() {
    val calls = mutableListOf<String>()
    val sink = object : FrameScopeSink {
      override fun sessionChanged(id: String?) {
        calls += "session:$id"
      }

      override fun variantChanged(id: String?) {
        calls += "variant:$id"
      }
    }

    for (features in listOf(emptySet(), setOf(KeliverHostFeatures.CACHED_FRAME_1))) {
      compose(features) {
        CompositionLocalProvider(LocalFrameScopeSink provides sink) {
          val scope = rememberFrameScope()
          scope.sessionChanged("a")
          scope.variantChanged("b")
        }
      }
    }
    assertThat(calls.toList()).isEqualTo(emptyList())

    compose(setOf(KeliverHostFeatures.CACHED_FRAME_1, KeliverHostFeatures.FRAME_SCOPE_1)) {
      CompositionLocalProvider(LocalFrameScopeSink provides sink) {
        val scope = rememberFrameScope()
        scope.sessionChanged("a")
        scope.variantChanged(null)
      }
    }
    assertThat(calls.toList()).isEqualTo(listOf("session:a", "variant:null"))
    assertThat(calls).doesNotContain("variant:b")
  }

  /** The composition has no widgets. */
  private object NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
  }
}
