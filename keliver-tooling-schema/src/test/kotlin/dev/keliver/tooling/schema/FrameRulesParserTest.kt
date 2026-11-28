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
package dev.keliver.tooling.schema

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.message
import dev.keliver.schema.BlocksFrameWhenTrue
import dev.keliver.schema.Children
import dev.keliver.schema.Modifier
import dev.keliver.schema.NeverCached
import dev.keliver.schema.NotData
import dev.keliver.schema.Property
import dev.keliver.schema.Replayable
import dev.keliver.schema.Schema
import dev.keliver.schema.Scrolls
import dev.keliver.schema.StyleToken
import dev.keliver.schema.Widget
import dev.keliver.tooling.schema.ProtocolWidget.ProtocolProperty
import kotlin.jvm.JvmInline
import kotlinx.serialization.json.JsonElement
import org.junit.Test

/** NF5 A1: the frame-rule annotations as the parser reads and checks them. */
class FrameRulesParserTest {
  @JvmInline value class Money(val cents: Long)

  @JvmInline value class Name(val value: String)

  @JvmInline value class Tint(val argb: Long) {
    val referenceName: String get() = "primary" // computed from the Long: not counted
  }

  class Avatar(val size: Int) {
    private val initials: String = ""
  }

  class Point(val x: Int, val y: Int) {
    val label: String get() = "$x,$y" // computed: counts (the walk cannot tell it from a field)
  }

  open class Base(val note: String)
  class Derived(val n: Int) : Base("")

  open class Holder<T>(val value: T)
  class StringHolder : Holder<String>("")
  class IntHolder : Holder<Int>(0)

  /** A type the parser cannot see into: its subclasses are unknown (a `Painter`, a `Shape`). */
  abstract class Opaque

  @Schema(members = [Card::class, Gap::class])
  interface Classified

  @Replayable
  @Scrolls
  @Widget(1)
  data class Card(
    @Property(1) val title: FrameRulesTitle,
    @Property(2) val money: Money,
    @Property(3) val name: Name,
    @Property(4) val avatar: Avatar,
    @Property(5) val point: Point,
    @Property(6) val derived: Derived,
    @Property(7) val json: JsonElement,
    @Property(8) val ids: IntArray,
    @Property(9) val chars: List<Char>,
    @Property(10) @NotData val size: Int,
    @Property(11) @StyleToken val icon: String,
    @Property(12) @BlocksFrameWhenTrue val busy: Boolean,
    @Property(13) @NeverCached val pin: String,
    @Property(14) val onClick: () -> Unit,
    @Property(15) val tint: Tint,
    @Property(16) val stringHolder: StringHolder,
    @Property(17) val intHolder: IntHolder,
    @Property(18) @StyleToken val opaque: Opaque,
    @Children(1) val content: () -> Unit,
  )

  @Replayable
  @Modifier(1)
  data class Gap(@NotData val all: Int, val label: String)

  @Test fun annotationsAndStringsAreRead() {
    val schema = parseTestSchema(Classified::class).schema
    val card = schema.widgets.single()
    assertThat(card.replayable).isTrue()
    assertThat(card.scrolls).isTrue()
    val properties = card.traits.filterIsInstance<ProtocolProperty>().associateBy { it.name }
    fun strings(name: String) = properties.getValue(name).containsString
    assertThat(strings("title")).isTrue() // a type alias of String
    assertThat(strings("money")).isFalse() // a value class over a Long
    assertThat(strings("name")).isTrue() // a value class over a String
    assertThat(strings("avatar")).isTrue() // a private String field
    assertThat(strings("point")).isTrue()
    assertThat(strings("derived")).isTrue() // a superclass's String
    assertThat(strings("json")).isTrue()
    assertThat(strings("ids")).isFalse()
    assertThat(strings("chars")).isTrue()
    assertThat(strings("size")).isFalse()
    assertThat(strings("tint")).isFalse() // a value class: only its constructor property counts
    assertThat(strings("stringHolder")).isTrue() // a String through a superclass's type argument
    assertThat(strings("intHolder")).isFalse()
    // L2, pinned: @StyleToken passes on a type the parser cannot see into (unknown counts as a String),
    // so Shell keeps it. The writer of such a mirror owns its codec's being style, not text.
    assertThat(properties.getValue("opaque").frameRule).isEqualTo(FramePropertyRule.StyleToken)
    assertThat(properties.getValue("size").frameRule).isEqualTo(FramePropertyRule.NotData)
    assertThat(properties.getValue("icon").frameRule).isEqualTo(FramePropertyRule.StyleToken)
    assertThat(properties.getValue("busy").frameRule).isEqualTo(FramePropertyRule.BlocksFrameWhenTrue)
    assertThat(properties.getValue("pin").frameRule).isEqualTo(FramePropertyRule.NeverCached)
    assertThat(properties.getValue("title").frameRule).isNull()

    val gap = schema.modifiers.single()
    assertThat(gap.replayable).isTrue()
    assertThat(gap.scrolls).isFalse()
    assertThat(gap.properties.single { it.name == "all" }.frameRule).isEqualTo(FramePropertyRule.NotData)
    assertThat(gap.properties.single { it.name == "label" }.containsString).isTrue()
  }

  @Test fun theRulesSurviveTheEmbeddedJson() {
    val schema = parseTestSchema(Classified::class).schema
    val json = schema.toEmbeddedSchema().json
    val parsed = ParsedProtocolSchema.parseEmbeddedJson(json)
    assertThat(parsed).isEqualTo(schema)
  }

  @Schema(members = [PlainWidget::class])
  interface Plain

  @Widget(1)
  data class PlainWidget(@Property(1) val text: String)

  @Test fun anUnannotatedSchemasJsonIsUnchanged() {
    val json = parseTestSchema(Plain::class).schema.toEmbeddedSchema().json
    assertThat(json.contains("replayable") || json.contains("containsString") || json.contains("frameRule")).isFalse()
  }

  @Schema(members = [TwoRules::class])
  interface TwoRulesSchema

  @Widget(1)
  data class TwoRules(@Property(1) @NotData @NeverCached val size: Int)

  @Test fun aPropertyCarriesAtMostOneRule() {
    assertFailure { parseTestSchema(TwoRulesSchema::class) }
      .isInstanceOf<IllegalArgumentException>()
      .message().isEqualTo(
        "dev.keliver.tooling.schema.FrameRulesParserTest.TwoRules#size has @NeverCached and @NotData: a property " +
          "carries at most one of @NeverCached, @BlocksFrameWhenTrue, @StyleToken and @NotData",
      )
  }

  @Schema(members = [BlocksInt::class])
  interface BlocksIntSchema

  @Widget(1)
  data class BlocksInt(@Property(1) @BlocksFrameWhenTrue val count: Int)

  @Test fun blocksFrameWhenTrueNeedsABoolean() {
    assertFailure { parseTestSchema(BlocksIntSchema::class) }
      .isInstanceOf<IllegalArgumentException>()
      .message().isEqualTo(
        "@BlocksFrameWhenTrue dev.keliver.tooling.schema.FrameRulesParserTest.BlocksInt#count must be a non-null Boolean. Found: kotlin.Int",
      )
  }

  @Schema(members = [TokenInt::class])
  interface TokenIntSchema

  @Widget(1)
  data class TokenInt(@Property(1) @StyleToken val size: Int)

  @Test fun styleTokenNeedsAString() {
    assertFailure { parseTestSchema(TokenIntSchema::class) }
      .isInstanceOf<IllegalArgumentException>()
      .message().isNotNull().contains("@StyleToken dev.keliver.tooling.schema.FrameRulesParserTest.TokenInt#size: kotlin.Int contains no String")
  }

  @Schema(members = [NotDataText::class])
  interface NotDataTextSchema

  @Replayable
  @Modifier(1)
  data class NotDataText(@NotData val avatar: Avatar)

  @Test fun notDataRefusesAStringInside() {
    assertFailure { parseTestSchema(NotDataTextSchema::class) }
      .isInstanceOf<IllegalArgumentException>()
      .message().isNotNull().contains("@NotData dev.keliver.tooling.schema.FrameRulesParserTest.NotDataText#avatar: ")
  }

  @Schema(members = [RuleOnEvent::class])
  interface RuleOnEventSchema

  @Widget(1)
  data class RuleOnEvent(@Property(1) @NotData val onClick: () -> Unit)

  @Test fun rulesGoOnValuesOnly() {
    assertFailure { parseTestSchema(RuleOnEventSchema::class) }
      .isInstanceOf<IllegalArgumentException>()
      .message().isNotNull().contains("frame-rule annotations go on a value @Property only")
  }
}

typealias FrameRulesTitle = String
