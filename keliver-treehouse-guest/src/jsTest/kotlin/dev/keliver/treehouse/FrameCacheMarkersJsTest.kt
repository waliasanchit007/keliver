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

package dev.keliver.treehouse

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.FrameCachePolicy
import dev.keliver.compose.FrameContent
import dev.keliver.compose.KeliverHostFeatures
import dev.keliver.compose.LocalHostFeatures
import dev.keliver.compose.cached
import dev.keliver.compose.cachedFrame
import dev.keliver.compose.notCached
import dev.keliver.protocol.Change
import dev.keliver.protocol.FrameMarks
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierChange
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.RedwoodVersion
import dev.keliver.protocol.guest.GuestProtocolAdapter
import dev.keliver.protocol.guest.ProtocolMismatchHandler
import dev.keliver.protocol.guest.ProtocolWidgetSystemFactory
import dev.keliver.widget.WidgetSystem
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromDynamic

/**
 * H1 of review-334: on JavaScript the guest's adapter is [FastGuestProtocolAdapter], whose generated
 * modifier function throws for anything that is not a schema element. The markers must be written by
 * it, with the same JSON as `FrameCacheMarkersTest` asserts for the default adapter.
 */
class FrameCacheMarkersJsTest {
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
      // What generated code does for anything it does not know.
      else -> throw AssertionError()
    } as Pair<ModifierTag, SerializationStrategy<T>?>
  }

  private fun modifiers(features: Set<String>): List<Modifier> {
    val result = mutableListOf<Modifier>()
    val recomposer = Recomposer(EmptyCoroutineContext)
    val composition = Composition(NoOpApplier, recomposer)
    composition.setContent {
      CompositionLocalProvider(LocalHostFeatures provides features) {
        content(result)
      }
    }
    composition.dispose()
    recomposer.close()
    return result
  }

  @Composable
  private fun content(result: MutableList<Modifier>) {
    result += Plain.cachedFrame(FrameCachePolicy(FrameContent.Shell, 12.hours), ready = true, paneTitle = "Orders")
    result += Plain.cached()
    result += Plain.notCached()
  }

  /** The `elements` of each modifier change [FastGuestProtocolAdapter] sends, as JSON, and the decoded changes. */
  private fun send(modifiers: List<Modifier>): Pair<List<String>, List<Change>> {
    val json = Json { useArrayPolymorphism = true }
    val guest = FastGuestProtocolAdapter(
      hostVersion = RedwoodVersion("0.5.0"),
      widgetSystemFactory = factory,
      json = json,
      mismatchHandler = ProtocolMismatchHandler.Throwing,
    )
    val elements = mutableListOf<String>()
    val decoded = mutableListOf<Change>()
    guest.initChangesSink(
      changesSinkService = object : ChangesSinkService {
        override fun sendChanges(changes: List<Change>) = Unit
      },
      sendChanges = { _, args ->
        val batch = args.single()
        for (change in batch.unsafeCast<Array<dynamic>>()) {
          elements += JSON.stringify(change[1].elements)
        }
        decoded += json.decodeFromDynamic<List<Change>>(batch)
        Unit
      },
    )
    modifiers.forEachIndexed { index, modifier -> guest.appendModifierChange(Id(index + 1), modifier) }
    guest.emitChanges()
    return elements to decoded
  }

  @Test fun withTheFeatureTheFastAdapterWritesTheSameMarkersAsTheDefaultOne() {
    val (elements, _) = send(modifiers(setOf(KeliverHostFeatures.CACHED_FRAME_1)))

    // The strings of FrameCacheMarkersTest.withTheFeatureTheOnlyDifferenceIsTheMarkers.
    assertThat(elements[0]).isEqualTo(
      """[[7],[-4600001,{"content":"shell","maxAgeMillis":43200000,"ready":true,"paneTitle":"Orders"}]]""",
    )
    assertThat(elements[1]).isEqualTo("""[[7],[-4600002]]""")
    assertThat(elements[2]).isEqualTo("""[[7],[-4600003]]""")
  }

  @Test fun theHostReadsWhatTheFastAdapterWrote() {
    val (_, decoded) = send(modifiers(setOf(KeliverHostFeatures.CACHED_FRAME_1)))

    val frame = FrameMarks.read((decoded[0] as ModifierChange).elements)?.cachedFrame
    assertThat(frame).isNotNull()
    assertThat(frame!!.content).isEqualTo("shell")
    assertThat(frame.maxAgeMillis).isEqualTo(43_200_000L)
    assertThat(frame.ready).isEqualTo(true)
    assertThat(frame.paneTitle).isEqualTo("Orders")
    assertThat(FrameMarks.read((decoded[1] as ModifierChange).elements)?.cached).isEqualTo(true)
    assertThat(FrameMarks.read((decoded[2] as ModifierChange).elements)?.notCached).isEqualTo(true)
  }

  @Test fun withoutTheFeatureNothingIsWritten() {
    val (elements, _) = send(modifiers(emptySet()))
    assertThat(elements).isEqualTo(listOf("[[7]]", "[[7]]", "[[7]]"))
  }

  private object NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
  }
}
