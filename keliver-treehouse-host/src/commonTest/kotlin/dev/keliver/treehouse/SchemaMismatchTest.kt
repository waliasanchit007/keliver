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
package dev.keliver.treehouse

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.EventTag
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.WidgetTag
import kotlin.test.Test

/**
 * MS4-04: a code session's unknown tags reach [EventListener.schemaMismatch] once per distinct
 * mismatch, while the per-occurrence callbacks keep running for each one.
 */
class SchemaMismatchTest {
  private class Recording : EventListener() {
    val mismatches = mutableListOf<String>()
    val occurrences = mutableListOf<String>()

    override fun schemaMismatch(mismatch: SchemaMismatch) {
      mismatches += mismatch.toString()
    }

    override fun unknownWidget(tag: WidgetTag) {
      occurrences += "widget ${tag.value}"
    }

    override fun unknownModifier(tag: ModifierTag) {
      occurrences += "modifier ${tag.value}"
    }
  }

  private val listener = Recording()
  private val publisher = RealEventPublisher(listener)
  private val handler = publisher.widgetProtocolMismatchHandler

  @Test
  fun twoUnknownTagsGiveTwoReports() {
    handler.onUnknownWidget(WidgetTag(41))
    handler.onUnknownWidget(WidgetTag(42))

    assertThat(listener.mismatches).containsExactly("unknown widget 41", "unknown widget 42")
  }

  @Test
  fun theSameTagTwiceGivesOneReport() {
    handler.onUnknownWidget(WidgetTag(41))
    handler.onUnknownWidget(WidgetTag(41))
    handler.onUnknownModifier(ModifierTag(7))
    handler.onUnknownModifier(ModifierTag(7))

    assertThat(listener.mismatches).containsExactly("unknown widget 41", "unknown modifier 7")
    // Every occurrence still reaches the per-call callbacks, as before.
    assertThat(listener.occurrences).containsExactly("widget 41", "widget 41", "modifier 7", "modifier 7")
  }

  /** A widget tag and a modifier tag with the same number are two mismatches. */
  @Test
  fun eachKindIsItsOwnTagSpace() {
    handler.onUnknownWidget(WidgetTag(5))
    handler.onUnknownModifier(ModifierTag(5))

    assertThat(listener.mismatches).containsExactly("unknown widget 5", "unknown modifier 5")
  }

  /** A property, children or event tag is one mismatch per widget tag it was used on. */
  @Test
  fun propertyChildrenAndEventTagsCountPerWidget() {
    handler.onUnknownProperty(WidgetTag(12), PropertyTag(3))
    handler.onUnknownProperty(WidgetTag(12), PropertyTag(3))
    handler.onUnknownProperty(WidgetTag(13), PropertyTag(3))
    handler.onUnknownChildren(WidgetTag(12), ChildrenTag(2))
    handler.onUnknownChildren(WidgetTag(12), ChildrenTag(2))
    publisher.onUnknownEvent(WidgetTag(12), EventTag(4))
    publisher.onUnknownEvent(WidgetTag(12), EventTag(4))

    assertThat(listener.mismatches).containsExactly(
      "unknown property 3 of widget 12",
      "unknown property 3 of widget 13",
      "unknown children 2 of widget 12",
      "unknown event 4 of widget 12",
    )
  }

  /** An event for a widget that is gone is the normal cost of asynchronous events, not a mismatch. */
  @Test
  fun anEventForAGoneWidgetIsNoMismatch() {
    publisher.onUnknownEventNode(Id(9), EventTag(1))

    assertThat(listener.mismatches).isEmpty()
  }

  /** Each code session (a loaded bundle) has its own publisher, and reports its mismatches afresh. */
  @Test
  fun aNewCodeSessionReportsAfresh() {
    handler.onUnknownWidget(WidgetTag(41))
    val next = Recording()
    RealEventPublisher(next).widgetProtocolMismatchHandler.onUnknownWidget(WidgetTag(41))

    assertThat(listener.mismatches).containsExactly("unknown widget 41")
    assertThat(next.mismatches).containsExactly("unknown widget 41")
  }

  @Test
  fun aMismatchIsTheSameOneByItsTags() {
    assertThat(SchemaMismatch.Property(WidgetTag(1), PropertyTag(2)))
      .isEqualTo(SchemaMismatch.Property(WidgetTag(1), PropertyTag(2)))
    assertThat(SchemaMismatch.Property(WidgetTag(1), PropertyTag(2)).hashCode())
      .isEqualTo(SchemaMismatch.Property(WidgetTag(1), PropertyTag(2)).hashCode())
    assertThat(SchemaMismatch.Property(WidgetTag(1), PropertyTag(2)) == SchemaMismatch.Property(WidgetTag(2), PropertyTag(2)))
      .isEqualTo(false)
    val seen = SchemaMismatches()
    assertThat(listOf(seen.add(SchemaMismatch.Event(WidgetTag(1), EventTag(2))), seen.add(SchemaMismatch.Event(WidgetTag(1), EventTag(2)))))
      .containsExactly(true, false)
  }
}
