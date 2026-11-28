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
@file:OptIn(KeliverFrameFoldApi::class, KeliverFrameWidgetApi::class, KeliverFrameMarksApi::class)

package dev.keliver.treehouse

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.keliver.Modifier
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChildrenChange
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Event
import dev.keliver.protocol.EventTag
import dev.keliver.protocol.Id
import dev.keliver.protocol.KeliverFrameMarksApi
import dev.keliver.protocol.PropertyChange
import dev.keliver.protocol.host.UiEvent
import dev.keliver.protocol.host.UiEventSink
import dev.keliver.widget.KeliverFrameWidgetApi
import dev.keliver.widget.ScrollOrigin
import dev.keliver.widget.TextLine
import dev.keliver.widget.TextLines
import dev.keliver.widget.Widget
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem

private class FakeScroller(override var atOrigin: Boolean) : Widget<Any>, ScrollOrigin {
  override val value: Any get() = this
  override var modifier: Modifier = Modifier
}

private class FakeText(val lines: Map<Int, List<TextLine>>) : Widget<Any>, TextLines {
  override val value: Any get() = this
  override var modifier: Modifier = Modifier

  override fun lines(propertyTag: Int): List<TextLine>? = lines[propertyTag]
}

private class PlainWidget : Widget<Any> {
  override val value: Any get() = this
  override var modifier: Modifier = Modifier
}

private class FakeEvent(val id: Int, val tag: Int) : UiEvent {
  override fun toProtocol() = Event(Id(id), EventTag(tag), emptyList())
}

/** The string property 1 the frame holds for [nodeId]. */
private fun textOf(frame: StoredFrame, nodeId: Int): String =
  frame.snapshot.changes.filterIsInstance<PropertyChange>()
    .single { it.id.value == nodeId && it.propertyTag.value == 1 }
    .let { (it.value as JsonPrimitive).content }

private class Reasons : FrameRecorderListener {
  val skipped = mutableListOf<String>()
  val written = mutableListOf<Int>()

  override fun writeSkipped(reason: String) {
    skipped += reason
  }

  override fun written(bytes: Int) {
    written += bytes
  }
}

/**
 * NF5 A3c: the recording rule of docs/SHOW_BEFORE_COMPUTE.md 2.3. Every test runs the real
 * `HostProtocolAdapter` over the fold harness's schema (widget 1 Box, 2 Text, 3 not replayable;
 * modifier 8 = a scroller, 7 = an object modifier) beside the recorder, so the batches are the ones a
 * host applies.
 */
class FrameRecorderTest {
  private val parent = scratchDirectory()
  private val dir = parent / "frames"
  private val clock = FakeFrameClock()
  private val random = SequentialRandom()
  private val reasons = Reasons()
  private val host = TestHost()
  private val hostWidgets = mutableMapOf<Int, Widget<*>>()
  private var visible = true
  private var navigated: (FoldNode) -> Boolean = { false }

  @AfterTest fun cleanUp() {
    FileSystem.SYSTEM.deleteRecursively(parent, mustExist = false)
  }

  private val rules = RecordingRules(
    replayableWidgets = setOf(TestSchema.BOX, TestSchema.TEXT),
    replayableModifiers = setOf(7, 8),
    scrollingModifiers = setOf(8),
    blockingWidgetProperties = mapOf(TestSchema.TEXT to setOf(7)),
    textWidgetProperties = mapOf(TestSchema.TEXT to setOf(1)),
  )

  private fun openStore(limits: FrameStoreLimits = FrameStoreLimits(), session: Boolean = true) =
    FrameStore(FileSystem.SYSTEM, dir, "build-1", clock, random.bytes, limits).also {
      if (session) it.session("user-1")
    }

  private fun recorder(
    store: FrameStore = openStore(),
    rules: RecordingRules = this.rules,
    policy: HiddenEntryPolicy = HiddenEntryPolicy.NothingHidden,
    reports: Map<Int, Set<Int>> = emptyMap(),
  ) = FrameRecorder(
    rules = rules,
    store = store,
    cacheName = CACHE,
    key = { baseKey },
    widgets = { hostWidgets.toMap() },
    entryPolicy = policy,
    navigation = { node, _ -> navigated(node) },
    contentVisible = { visible },
    reportEvents = reports,
    listener = reasons,
  )

  private fun FrameRecorder.feed(batch: List<Change>) = onBatch(batch) { host.apply(batch) }

  private fun FrameRecorder.skippedFor(): String =
    (recording as? FrameRecording.Skipped)?.reason ?: error("expected a skip, got $recording")

  /** Box#1 (marked), a Text#2 child, and the marker's `ready`. */
  private fun screen(ready: Boolean, content: String = "whole"): List<Change> = listOf(
    create(1, TestSchema.BOX),
    modifier(1, cachedFrameElement(content, ready)),
    create(2, TestSchema.TEXT),
    prop(2, TestSchema.TEXT, 1, str("hello")),
    add(1, 1, 2, 0),
    addRoot(1),
  )

  private fun ready(value: Boolean = true, content: String = "whole"): List<Change> =
    listOf(modifier(1, cachedFrameElement(content, value)))

  private fun frameFiles() = FileSystem.SYSTEM.listRecursively(parent).filter { it.name.endsWith(".klvf") }.toList()

  // ---- the rule

  @Test fun writesOnceAtTheFirstReadyEdge() {
    val store = openStore()
    val recorder = recorder(store)
    recorder.feed(screen(ready = false))
    assertThat(recorder.recording).isNull()
    recorder.feed(ready(true))

    assertThat(recorder.recording is FrameRecording.Written).isTrue()
    assertThat(reasons.written).hasSize(1)
    val hit = store.read(CACHE, baseKey) as FrameRead.Hit
    assertThat(textOf(hit.frame, nodeId = 2)).isEqualTo("hello")
    assertThat(reasons.skipped).hasSize(0)
  }

  @Test fun aLaterEdgeDoesNotWrite() {
    val store = openStore()
    val recorder = recorder(store)
    recorder.feed(screen(ready = false))
    recorder.feed(ready(true))
    val before = frameFiles().map { FileSystem.SYSTEM.read(it) { readByteString() } }

    recorder.feed(ready(false)) // a refresh
    recorder.feed(listOf(prop(2, TestSchema.TEXT, 1, str("changed"))))
    recorder.feed(ready(true))

    assertThat(reasons.written).hasSize(1)
    assertThat(reasons.skipped).hasSize(0)
    assertThat(frameFiles().map { FileSystem.SYSTEM.read(it) { readByteString() } }).isEqualTo(before)
    assertThat(recorder.finished).isTrue()
  }

  @Test fun noEdgeNoDecision() {
    val recorder = recorder()
    recorder.feed(screen(ready = false))
    recorder.feed(listOf(prop(2, TestSchema.TEXT, 1, str("more"))))
    assertThat(recorder.recording).isNull()
    assertThat(reasons.skipped).hasSize(0)
  }

  @Test fun markedAndReadyInTheFirstBatchWrites() {
    val recorder = recorder()
    recorder.feed(screen(ready = true))
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
  }

  // ---- the first-batch and structure rules

  @Test fun noMarkInTheFirstBatchIsSkipped() {
    val recorder = recorder()
    recorder.feed(listOf(create(1, TestSchema.BOX), addRoot(1)))
    assertThat(recorder.skippedFor()).isEqualTo("noMark")
    assertThat(reasons.skipped).containsExactly("noMark")
    assertThat(frameFiles()).hasSize(0)
  }

  @Test fun twoMarksAreSkipped() {
    val recorder = recorder()
    recorder.feed(
      screen(ready = false) + listOf(
        create(3, TestSchema.BOX),
        modifier(3, cachedFrameElement("whole", false)),
        addRoot(3, index = 1),
      ),
    )
    recorder.feed(ready(true))
    assertThat(recorder.skippedFor()).isEqualTo("multipleMarks")
  }

  @Test fun aMarkThatIsNotTheRootsChildIsSkipped() {
    val recorder = recorder()
    recorder.feed(
      listOf(
        create(1, TestSchema.BOX),
        create(2, TestSchema.BOX),
        modifier(2, cachedFrameElement("whole", true)),
        add(1, 1, 2, 0),
        addRoot(1),
      ),
    )
    assertThat(recorder.skippedFor()).isEqualTo("notRootChild")
    assertThat(frameFiles()).hasSize(0)
  }

  @Test fun anInconsistentBatchIsSkipped() {
    val recorder = recorder()
    recorder.onBatch(listOf(prop(99, TestSchema.TEXT, 1, str("x")))) {}
    assertThat(recorder.skippedFor()).isEqualTo("inconsistent")
  }

  // ---- one blocked write per condition (2.3), each with its reason

  @Test fun condition2ContentHiddenIsNotVisible() {
    val recorder = recorder()
    visible = false
    recorder.feed(screen(ready = true))
    assertThat(recorder.skippedFor()).isEqualTo("notVisible")
  }

  @Test fun condition2MarkInAHiddenEntryIsNotVisible() {
    val recorder = recorder(policy = TestSchema.hiddenWhenFlagged)
    recorder.feed(screen(ready = true) + listOf(prop(1, TestSchema.BOX, 2, JsonPrimitive(true))))
    assertThat(recorder.skippedFor()).isEqualTo("notVisible")
  }

  @Test fun condition3NavigatedInsideARetainedContainer() {
    val recorder = recorder()
    navigated = { it.id.value == 1 }
    recorder.feed(screen(ready = true))
    assertThat(recorder.skippedFor()).isEqualTo("navigatedInside")
  }

  @Test fun condition4InputSeenBeforeTheEdge() {
    val recorder = recorder()
    recorder.feed(screen(ready = false))
    recorder.inputSeen()
    recorder.feed(ready(true))
    assertThat(recorder.skippedFor()).isEqualTo("input")
  }

  @Test fun condition4AnEventBeforeTheEdgeIsInput() {
    val recorder = recorder()
    var delivered = 0
    val sink = recorder.eventSink(UiEventSink { delivered++ })
    recorder.feed(screen(ready = false))
    sink.sendEvent(FakeEvent(2, 5)) // a semantics click: an event, no touch
    recorder.feed(ready(true))
    assertThat(delivered).isEqualTo(1)
    assertThat(recorder.skippedFor()).isEqualTo("input")
  }

  @Test fun condition4AReportEventIsNotInput() {
    val recorder = recorder(reports = mapOf(TestSchema.TEXT to setOf(9)))
    val sink = recorder.eventSink(UiEventSink { })
    recorder.feed(screen(ready = false))
    sink.sendEvent(FakeEvent(2, 9)) // a viewport report
    recorder.feed(ready(true))
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
  }

  @Test fun condition4AnEventOfAnotherTagOnAReportingWidgetIsInput() {
    val recorder = recorder(reports = mapOf(TestSchema.TEXT to setOf(9)))
    val sink = recorder.eventSink(UiEventSink { })
    recorder.feed(screen(ready = false))
    sink.sendEvent(FakeEvent(2, 1))
    recorder.feed(ready(true))
    assertThat(recorder.skippedFor()).isEqualTo("input")
  }

  @Test fun inputAfterTheDecisionChangesNothing() {
    val recorder = recorder()
    recorder.feed(screen(ready = true))
    recorder.inputSeen()
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
    assertThat(reasons.skipped).hasSize(0)
  }

  private fun scrollerBatch(ready: Boolean = true): List<Change> = screen(ready = ready) + listOf(
    create(3, TestSchema.BOX),
    modifier(3, marker(8)),
    add(1, 1, 3, 1),
  )

  @Test fun condition5AScrollerAtItsOriginIsRecorded() {
    val recorder = recorder()
    hostWidgets[3] = FakeScroller(atOrigin = true)
    recorder.feed(scrollerBatch())
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
  }

  @Test fun condition5AScrolledScrollerBlocks() {
    val recorder = recorder()
    hostWidgets[3] = FakeScroller(atOrigin = false)
    recorder.feed(scrollerBatch())
    assertThat(recorder.skippedFor()).isEqualTo("notAtOrigin")
  }

  @Test fun condition5AScrollerTagWithoutScrollOriginBlocks() {
    val recorder = recorder()
    hostWidgets[3] = PlainWidget()
    recorder.feed(scrollerBatch())
    assertThat(recorder.skippedFor()).isEqualTo("noScrollOrigin:${TestSchema.BOX}")
  }

  @Test fun condition5AScrollerWithNoWidgetBlocks() {
    val recorder = recorder()
    recorder.feed(scrollerBatch())
    assertThat(recorder.skippedFor()).isEqualTo("noScrollOrigin:${TestSchema.BOX}")
  }

  @Test fun condition5AWidgetTagThatScrolls() {
    val recorder = recorder(rules = RecordingRules(replayableWidgets = setOf(1, 2), scrollingWidgets = setOf(TestSchema.TEXT)))
    recorder.feed(screen(ready = true))
    assertThat(recorder.skippedFor()).isEqualTo("noScrollOrigin:${TestSchema.TEXT}")
  }

  @Test fun aScrollerInANotCachedSubtreeIsNotAsked() {
    val recorder = recorder()
    hostWidgets[3] = FakeScroller(atOrigin = false)
    recorder.feed(
      scrollerBatch() + listOf(
        modifier(3, marker(8), marker(dev.keliver.protocol.FrameCacheTags.NOT_CACHED)),
      ),
    )
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
  }

  @Test fun condition6ANotReplayableWidgetBlocks() {
    val recorder = recorder()
    recorder.feed(
      screen(ready = true) + listOf(create(3, TestSchema.OPAQUE), add(1, 1, 3, 1)),
    )
    assertThat(recorder.skippedFor()).isEqualTo("notReplayable:widget:${TestSchema.OPAQUE}")
  }

  @Test fun condition6ANotReplayableModifierBlocks() {
    val recorder = recorder()
    recorder.feed(screen(ready = true) + listOf(modifier(2, marker(9))))
    assertThat(recorder.skippedFor()).isEqualTo("notReplayable:modifier:9")
  }

  @Test fun condition7ABlockingPropertyThatIsTrueBlocks() {
    val recorder = recorder()
    recorder.feed(screen(ready = false))
    recorder.feed(listOf(prop(2, TestSchema.TEXT, 7, JsonPrimitive(true))))
    recorder.feed(ready(true))
    assertThat(recorder.skippedFor()).isEqualTo("refreshing")
  }

  @Test fun condition7AFalseBlockingPropertyDoesNot() {
    val recorder = recorder()
    recorder.feed(screen(ready = false))
    recorder.feed(listOf(prop(2, TestSchema.TEXT, 7, JsonPrimitive(false))))
    recorder.feed(ready(true))
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
  }

  @Test fun theFirstFailedConditionIsTheReason() {
    val recorder = recorder()
    hostWidgets[3] = FakeScroller(atOrigin = false)
    recorder.inputSeen()
    recorder.feed(scrollerBatch() + listOf(create(4, TestSchema.OPAQUE), add(1, 1, 4, 2)))
    assertThat(recorder.skippedFor()).isEqualTo("input") // before notAtOrigin and notReplayable
  }

  // ---- the store's refusals

  @Test fun noSessionIsSkipped() {
    val recorder = recorder(openStore(session = false))
    recorder.feed(screen(ready = true))
    assertThat(recorder.skippedFor()).isEqualTo("noSession")
  }

  @Test fun aFrameOverTheCapIsSkipped() {
    val recorder = recorder(openStore(FrameStoreLimits(maxFrameBytes = 16, maxCacheNameBytes = 64)))
    recorder.feed(screen(ready = true))
    assertThat(recorder.skippedFor()).isEqualTo("sizeCap")
  }

  // ---- Shell and TextLines

  @Test fun shellWithATextLinesWidgetUsesItsLines() {
    val store = openStore()
    val recorder = recorder(store)
    hostWidgets[2] = FakeText(mapOf(1 to listOf(TextLine(10f, 2), TextLine(12f, 3))))
    recorder.feed(screen(ready = true, content = "shell"))
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
    val hit = store.read(CACHE, baseKey) as FrameRead.Hit
    val text = textOf(hit.frame, nodeId = 2)
    assertThat(text.split('\n').map { it.length }).isEqualTo(listOf(2, 3))
    assertThat(text.contains("hello")).isEqualTo(false)
  }

  @Test fun shellWithATextWidgetThatIsNotTextLinesBlocks() {
    val recorder = recorder()
    hostWidgets[2] = PlainWidget()
    recorder.feed(screen(ready = true, content = "shell"))
    assertThat(recorder.skippedFor()).isEqualTo("notMeasured:${TestSchema.TEXT}")
    assertThat(frameFiles()).hasSize(0)
  }

  @Test fun shellWithNoWidgetForTheTextBlocks() {
    val recorder = recorder()
    recorder.feed(screen(ready = true, content = "shell"))
    assertThat(recorder.skippedFor()).isEqualTo("notMeasured:${TestSchema.TEXT}")
  }

  @Test fun wholeNeedsNoTextLines() {
    val recorder = recorder()
    recorder.feed(screen(ready = true, content = "whole"))
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
  }

  // ---- widgetsUnder (the real adapter)

  @Test fun widgetsUnderListsTheNodeAndItsDescendants() {
    host.apply(screen(ready = false) + listOf(create(5, TestSchema.BOX), add(1, 1, 5, 1), create(6, TestSchema.TEXT), add(5, 1, 6, 0)))
    assertThat(host.widgetsUnder(Id(1)).map { it.id.value }).containsExactly(1, 2, 5, 6)
    assertThat(host.widgetsUnder(Id(5)).map { it.id.value }).containsExactly(5, 6)
    assertThat(host.widgetsUnder(Id(5)).map { it.widgetTag.value }).containsExactly(TestSchema.BOX, TestSchema.TEXT)
    assertThat(host.widgetsUnder(Id(99))).hasSize(0)
  }

  @Test fun widgetsUnderSeesWhatTheRecorderNeeds() {
    val recorder = FrameRecorder(
      rules = rules,
      store = openStore(),
      cacheName = CACHE,
      key = { baseKey },
      widgets = { id ->
        host.widgetsUnder(id).associate { it.id.value to (if (it.id.value == 3) FakeScroller(true) else it.widget) }
      },
      listener = reasons,
    )
    recorder.feed(scrollerBatch())
    assertThat(recorder.recording is FrameRecording.Written).isTrue()
  }

  // ---- I11's navigation test

  @Test fun navigationAfterTheFirstWriteChangesNothing() {
    val store = openStore()
    val recorder = recorder(store)
    // A list screen, marked, with two rows; ready arrives with the data.
    recorder.feed(
      listOf(
        create(1, TestSchema.BOX),
        modifier(1, cachedFrameElement("whole", false)),
        create(2, TestSchema.TEXT),
        prop(2, TestSchema.TEXT, 1, str("row one")),
        create(3, TestSchema.TEXT),
        prop(3, TestSchema.TEXT, 1, str("row two")),
        add(1, 1, 2, 0),
        add(1, 1, 3, 1),
        addRoot(1),
      ),
    )
    recorder.feed(ready(true)) // write 1
    assertThat(reasons.written).hasSize(1)
    val files = frameFiles()
    assertThat(files).hasSize(1)
    val write1 = FileSystem.SYSTEM.read(files.single()) { readByteString() }

    // Navigate to a detail: the list goes, a detail comes. Then the app goes to the background:
    // the recorder has no lifecycle hook, and nothing else may write.
    recorder.feed(
      listOf(
        create(10, TestSchema.BOX),
        create(11, TestSchema.TEXT),
        prop(11, TestSchema.TEXT, 1, str("DETAIL-secret")),
        add(10, 1, 11, 0),
        ChildrenChange.Remove(Id.Root, ChildrenTag.Root, 0, false),
        addRoot(10),
      ),
    )
    recorder.feed(listOf(PropertyChange(Id(11), dev.keliver.protocol.WidgetTag(2), dev.keliver.protocol.PropertyTag(1), str("DETAIL-2"))))
    recorder.inputSeen()

    assertThat(reasons.written).hasSize(1)
    assertThat(reasons.skipped).hasSize(0)
    assertThat(frameFiles()).hasSize(1)
    assertThat(FileSystem.SYSTEM.read(frameFiles().single()) { readByteString() }).isEqualTo(write1)
    val hit = store.read(CACHE, baseKey) as FrameRead.Hit
    val ids = hit.frame.snapshot.changes.filterIsInstance<dev.keliver.protocol.Create>().map { it.id.value }
    assertThat(ids).containsExactly(1, 2, 3)
    assertThat(textOf(hit.frame, nodeId = 2)).isEqualTo("row one")
    assertThat(
      frameFiles().none { FileSystem.SYSTEM.read(it) { readUtf8() }.contains("DETAIL") },
    ).isTrue()
  }
}
