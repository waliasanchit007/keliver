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
@file:OptIn(RedwoodCodegenApi::class, RedwoodLeakApi::class)

package dev.keliver.protocol.host

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.leaks.LeakDetector
import dev.keliver.leaks.RedwoodLeakApi
import dev.keliver.protocol.ChildrenChange
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Create
import dev.keliver.protocol.FrameCacheTags
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierChange
import dev.keliver.protocol.ModifierElement
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.RedwoodVersion
import dev.keliver.protocol.WidgetTag
import dev.keliver.widget.Widget
import dev.keliver.widget.WidgetSystem
import kotlin.test.Test
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** NF5 A2: the reserved marker tags are consumed by the host before any widget or mismatch handler. */
class FrameMarksTest {
  private val mismatches = RecordingProtocolMismatchHandler()
  private val appliedToWidget = mutableListOf<String>()
  private val protocol = FakeProtocol(mismatches)
  private val container = FakeChildren()
  private val adapter = HostProtocolAdapter(
    guestVersion = RedwoodVersion("test"),
    container = container,
    protocol = protocol,
    widgetSystem = object : WidgetSystem<String> {
      override fun apply(value: String, element: Modifier.UnscopedElement) {
        appliedToWidget += element.toString()
      }
    },
    eventSink = UiEventSink { },
    leakDetector = LeakDetector.none(),
  )

  private fun send(vararg changes: dev.keliver.protocol.Change) {
    adapter.sendChanges(changes.map { UiChange.fromProtocol(protocol, it)!! })
  }

  private fun createRootChild(id: Int) {
    send(
      Create(Id(id), WidgetTag(1)),
      ChildrenChange.Add(Id.Root, ChildrenTag.Root, Id(id), 0),
    )
  }

  private val cachedFrame = ModifierElement(
    ModifierTag(FrameCacheTags.CACHED_FRAME),
    buildJsonObject {
      put("content", "shell")
      put("maxAgeMillis", 43_200_000L)
      put("ready", true)
      put("paneTitle", "Orders")
    },
  )

  @Test fun markersNeverReachAWidgetOrTheMismatchHandler() {
    createRootChild(1)
    send(
      ModifierChange(
        Id(1),
        listOf(
          ModifierElement(ModifierTag(7)),
          cachedFrame,
          ModifierElement(ModifierTag(FrameCacheTags.CACHED)),
          ModifierElement(ModifierTag(FrameCacheTags.NOT_CACHED)),
        ),
      ),
    )

    assertThat(mismatches.events).isEmpty()
    assertThat(appliedToWidget).containsExactly("Known7")
    val marks = adapter.frameMarks(Id(1))
    assertThat(marks).isNotNull()
    assertThat(marks!!.cached).isEqualTo(true)
    assertThat(marks.notCached).isEqualTo(true)
    val frame = marks.cachedFrame!!
    assertThat(frame.content).isEqualTo("shell")
    assertThat(frame.maxAgeMillis).isEqualTo(43_200_000L)
    assertThat(frame.ready).isEqualTo(true)
    assertThat(frame.paneTitle).isEqualTo("Orders")
  }

  @Test fun anUnassignedTagInTheRangeIsConsumedToo() {
    createRootChild(1)
    send(ModifierChange(Id(1), listOf(ModifierElement(ModifierTag(-4_600_050), JsonPrimitive(1)))))

    assertThat(mismatches.events).isEmpty()
    assertThat(appliedToWidget).isEmpty()
    assertThat(adapter.frameMarks(Id(1))).isNull()
  }

  @Test fun anUnknownTagOutsideTheRangeStillReachesTheMismatchHandler() {
    createRootChild(1)
    send(ModifierChange(Id(1), listOf(ModifierElement(ModifierTag(99)))))

    assertThat(mismatches.events).containsExactly("Unknown layout modifier 99")
  }

  @Test fun aMalformedMarkerIsDroppedWithoutAReport() {
    createRootChild(1)
    send(
      ModifierChange(
        Id(1),
        listOf(ModifierElement(ModifierTag(FrameCacheTags.CACHED_FRAME), JsonPrimitive("nope"))),
      ),
    )

    assertThat(mismatches.events).isEmpty()
    assertThat(adapter.frameMarks(Id(1))).isNull()
  }

  @Test fun theNextModifierChangeReplacesTheMarks() {
    createRootChild(1)
    send(ModifierChange(Id(1), listOf(cachedFrame)))
    assertThat(adapter.frameMarks(Id(1))).isNotNull()

    send(ModifierChange(Id(1), listOf(ModifierElement(ModifierTag(7)))))
    assertThat(adapter.frameMarks(Id(1))).isNull()
  }

  @Test fun marksGoWithTheirNode() {
    createRootChild(1)
    send(ModifierChange(Id(1), listOf(cachedFrame)))
    send(ChildrenChange.Remove(Id.Root, ChildrenTag.Root, 0, false))

    assertThat(adapter.frameMarks(Id(1))).isNull()
  }

  @Test fun withoutMarkersNothingChanges() {
    createRootChild(1)
    send(ModifierChange(Id(1), listOf(ModifierElement(ModifierTag(7)))))

    assertThat(appliedToWidget).containsExactly("Known7")
    assertThat(adapter.frameMarks(Id(1))).isNull()
    assertThat(mismatches.events).isEmpty()
  }
}

private object Known7 : Modifier.UnscopedElement {
  override fun toString() = "Known7"
}

private class FakeProtocol(
  override val mismatchHandler: ProtocolMismatchHandler,
) : GeneratedHostProtocol {
  override val json: Json = Json.Default

  override fun widget(tag: WidgetTag): WidgetHostProtocol? = if (tag.value == 1) FakeWidgetProtocol else null

  override fun createModifier(element: ModifierElement): Modifier = when (element.tag.value) {
    7 -> Known7
    else -> {
      mismatchHandler.onUnknownModifier(element.tag)
      Modifier
    }
  }
}

private object FakeWidgetProtocol : WidgetHostProtocol {
  override fun <W : Any> createNode(id: Id, widgetSystem: WidgetSystem<W>): ProtocolNode<W> {
    @Suppress("UNCHECKED_CAST")
    return FakeNode(id) as ProtocolNode<W>
  }

  override fun propertyDeserializer(tag: PropertyTag): DeserializationStrategy<Any?>? = null

  override val childrenTags: IntArray? = null
}

private class FakeNode(id: Id) : ProtocolNode<String>(id), Widget<String> {
  override val widgetTag get() = WidgetTag(1)
  override val widgetName get() = "Fake"
  override val widget: Widget<String> get() = this
  override val value: String get() = "v"
  override var modifier: Modifier = Modifier
  override fun apply(change: UiPropertyChange, eventSink: UiEventSink) = Unit
  override fun children(tag: ChildrenTag): ProtocolChildren<String>? = null
  override fun detach() = Unit
}

private class FakeChildren : Widget.Children<String> {
  override val widgets = mutableListOf<Widget<String>>()
  override fun insert(index: Int, widget: Widget<String>) {
    widgets.add(index, widget)
  }

  override fun move(fromIndex: Int, toIndex: Int, count: Int) = Unit
  override fun remove(index: Int, count: Int) {
    repeat(count) { widgets.removeAt(index) }
  }

  override fun onModifierUpdated(index: Int, widget: Widget<String>) = Unit
  override fun detach() = Unit
}
