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
@file:OptIn(KeliverFrameFoldApi::class, KeliverFrameWidgetApi::class)

package dev.keliver.treehouse

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import dev.keliver.Modifier
import dev.keliver.compose.gatea.protocol.host.KeliverComposeGateAFrameRules as Generated
import dev.keliver.protocol.Change
import dev.keliver.widget.KeliverFrameWidgetApi
import dev.keliver.widget.ScrollOrigin
import dev.keliver.widget.Widget
import kotlin.test.AfterTest
import kotlin.test.Test
import okio.FileSystem

private class OriginWidget(override var atOrigin: Boolean) : Widget<Any>, ScrollOrigin {
  override val value: Any get() = this
  override var modifier: Modifier = Modifier
}

/**
 * NF5 A3c: the recorder builds its rules from the generated `<Schema>FrameRules` of the prototype's
 * group (`gatea`), with no hand-copied list. The frame is the group's own tags, so a tag the schema
 * calls replayable is recorded and a tag it does not is refused, whatever number it has.
 */
class FrameRecorderGeneratedRulesTest {
  private val parent = scratchDirectory()
  private val dir = parent / "frames"

  @AfterTest fun cleanUp() {
    FileSystem.SYSTEM.deleteRecursively(parent, mustExist = false)
  }

  private val rules = RecordingRules(
    replayableWidgets = Generated.replayableWidgets,
    replayableModifiers = Generated.replayableModifiers,
    scrollingWidgets = Generated.scrollingWidgets,
    scrollingModifiers = Generated.scrollingModifiers,
    blockingWidgetProperties = Generated.blockingWidgetProperties,
    blockingModifierProperties = Generated.blockingModifierProperties,
    textWidgetProperties = Generated.textWidgetProperties,
    textModifierProperties = Generated.textModifierProperties,
    neverCachedWidgetProperties = Generated.neverCachedWidgetProperties,
    neverCachedModifierProperties = Generated.neverCachedModifierProperties,
  )

  private fun recorder(hosted: Map<Int, Widget<*>>): FrameRecorder {
    val store = FrameStore(FileSystem.SYSTEM, dir, "build-1", FakeFrameClock(), SequentialRandom().bytes)
    store.session("user-1")
    return FrameRecorder(
      rules = rules,
      store = store,
      cacheName = CACHE,
      key = { baseKey },
      widgets = { hosted },
    )
  }

  private fun feed(recorder: FrameRecorder, vararg changes: Change) = recorder.onBatch(changes.toList()) {}

  private fun markedRoot(tag: Int) = arrayOf(
    create(1, tag),
    modifier(1, cachedFrameElement("whole", true)),
    addRoot(1),
  )

  @Test fun theGeneratedListsReachTheFoldRules() {
    assertThat(Generated.replayableWidgets).isNotEmpty()
    assertThat(Generated.scrollingWidgets).isNotEmpty()
    val fold = rules.foldRules()
    assertThat(fold.replayableWidgets).isEqualTo(Generated.replayableWidgets)
    assertThat(fold.replayableModifiers).isEqualTo(Generated.replayableModifiers)
    assertThat(fold.textWidgetProperties).isEqualTo(Generated.textWidgetProperties)
    assertThat(fold.neverCachedWidgetProperties).isEqualTo(Generated.neverCachedWidgetProperties)
  }

  @Test fun aReplayableTagOfTheSchemaIsRecorded() {
    val tag = Generated.replayableWidgets.first { it !in Generated.scrollingWidgets }
    val recorder = recorder(emptyMap())
    feed(recorder, *markedRoot(tag))
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
  }

  @Test fun aTagTheSchemaDoesNotListIsRefused() {
    val tag = (Generated.replayableWidgets.max() + 1000)
    val recorder = recorder(emptyMap())
    feed(recorder, *markedRoot(tag))
    assertThat((recorder.recording as FrameRecording.Skipped).reason).isEqualTo("notReplayable:widget:$tag")
  }

  @Test fun aScrollingTagOfTheSchemaNeedsScrollOrigin() {
    val tag = Generated.scrollingWidgets.first { it in Generated.replayableWidgets }

    val without = recorder(emptyMap())
    feed(without, *markedRoot(tag))
    assertThat((without.recording as FrameRecording.Skipped).reason).isEqualTo("noScrollOrigin:$tag")

    val scrolled = recorder(mapOf(1 to OriginWidget(atOrigin = false)))
    feed(scrolled, *markedRoot(tag))
    assertThat((scrolled.recording as FrameRecording.Skipped).reason).isEqualTo("notAtOrigin")

    val atOrigin = recorder(mapOf(1 to OriginWidget(atOrigin = true)))
    feed(atOrigin, *markedRoot(tag))
    assertThat(atOrigin.recording is FrameRecording.Written).isTrue()
  }
}
