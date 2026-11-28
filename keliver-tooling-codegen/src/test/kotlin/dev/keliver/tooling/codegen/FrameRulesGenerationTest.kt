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
package dev.keliver.tooling.codegen

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.keliver.layout.RedwoodLayout
import dev.keliver.schema.BlocksFrameWhenTrue
import dev.keliver.schema.Children
import dev.keliver.schema.Modifier
import dev.keliver.schema.NeverCached
import dev.keliver.schema.NotData
import dev.keliver.schema.Property
import dev.keliver.schema.Replayable
import dev.keliver.schema.Schema
import dev.keliver.schema.Schema.Dependency
import dev.keliver.schema.Scrolls
import dev.keliver.schema.StyleToken
import dev.keliver.schema.Widget
import dev.keliver.tooling.schema.parseTestSchema
import org.junit.Test

/** NF5 A1: `<Schema>FrameRules` and its review list (docs/SHOW_BEFORE_COMPUTE.md 2.4). */
class FrameRulesGenerationTest {
  @Schema(
    members = [Label::class, Feed::class, Popup::class, Pad::class, Tap::class, ScrollY::class],
    dependencies = [Dependency(1, RedwoodLayout::class)],
  )
  interface Frames

  data class Span(val text: String, val weight: Int)
  enum class Tone { Plain, Loud }
  sealed interface Badge {
    data class Count(val n: Int) : Badge
    data class Named(val name: String) : Badge
  }
  sealed interface Shade {
    data class Grey(val level: Int) : Shade
    data object None : Shade
  }
  abstract class Outline

  @Replayable
  @Widget(1)
  data class Label(
    @Property(1) val text: String,
    @Property(2) val spans: List<Span>,
    @Property(3) @StyleToken val icon: String,
    @Property(4) @NotData val size: Int,
    @Property(5) val count: Int,
    @Property(6) val tone: Tone,
    @Property(7) val badge: Badge,
    @Property(8) @NotData val shade: Shade,
    @Property(9) @NeverCached val secret: Long,
    @Property(10) val onClick: () -> Unit,
    @Property(11) val outline: Outline,
    @Property(12) val url: String?,
  )

  @Replayable
  @Scrolls
  @Widget(2)
  data class Feed(
    @Property(1) @BlocksFrameWhenTrue val refreshing: Boolean,
    @Property(2) val tags: Map<String, Int>,
    @Property(3) val position: Map<Int, Int>,
    @Children(1) val children: () -> Unit,
  )

  /** Not replayable: on no list, and not reviewed. */
  @Widget(3)
  data class Popup(
    @Property(1) val title: String,
    @Property(2) val open: Boolean,
  )

  @Replayable
  @Modifier(1)
  data class Pad(@NotData val all: Int)

  @Replayable
  @Modifier(2)
  data class Tap(val label: String, val enabled: Boolean)

  @Replayable
  @Scrolls
  @Modifier(3)
  data class ScrollY(@NotData val offset: Int)

  @Schema(members = [Plain::class])
  interface Unannotated

  @Widget(1)
  data class Plain(@Property(1) val text: String)

  @Test fun theListsFollowTheAnnotations() {
    val rules = FrameRules(parseTestSchema(Frames::class))
    assertThat(rules.replayableWidgets).containsExactly(1, 2)
    assertThat(rules.replayableModifiers).containsExactly(1, 2, 3)
    assertThat(rules.scrollingWidgets).containsExactly(2)
    assertThat(rules.scrollingModifiers).containsExactly(3)
    assertThat(rules.blockingWidgetProperties).isEqualTo(mapOf(2 to listOf(1)))
    assertThat(rules.blockingModifierProperties).isEqualTo(emptyMap())
    // A String, a structure with a String inside (a list of spans, a sealed type with a String in a
    // subclass, a map keyed by String), an abstract type (its subclasses are unknown) and a URL; not
    // the style token, the never-cached value, the enum or the event.
    assertThat(rules.textWidgetProperties).isEqualTo(mapOf(1 to listOf(1, 2, 7, 11, 12), 2 to listOf(2)))
    assertThat(rules.textModifierProperties).isEqualTo(mapOf(2 to listOf("label")))
    assertThat(rules.neverCachedWidgetProperties).isEqualTo(mapOf(1 to listOf(9)))
    assertThat(rules.neverCachedModifierProperties).isEqualTo(emptyMap())
  }

  @Test fun aDependencyWithoutAnnotationsReplaysNothing() {
    val schemaSet = parseTestSchema(Frames::class)
    val layoutTags = schemaSet.dependencies.values.single().widgets.map { it.tag }
    val rules = FrameRules(schemaSet)
    assertThat(layoutTags.isNotEmpty()).isTrue()
    assertThat(rules.replayableWidgets.intersect(layoutTags.toSet())).isEmpty()
    assertThat(rules.textWidgetProperties.keys.intersect(layoutTags.toSet())).isEmpty()
  }

  @Test fun theGeneratedObject() {
    val schemaSet = parseTestSchema(Frames::class)
    assertThat(generateFrameRules(schemaSet).toString()).all {
      contains("public object FrameRulesGenerationTestFramesFrameRules {")
      contains("public val replayableWidgets: Set<Int> = setOf(1, 2)")
      contains("public val scrollingModifiers: Set<Int> = setOf(3)")
      contains("public val blockingModifierProperties: Map<Int, Set<String>> = emptyMap()")
      contains("public val textWidgetProperties: Map<Int, Set<Int>> =")
      contains("mapOf(1 to setOf(1, 2, 7, 11, 12), 2 to setOf(2))")
      contains("public val textModifierProperties: Map<Int, Set<String>> = mapOf(2 to setOf(\"label\"))")
    }
  }

  @Test fun onlyASchemaWithFrameRulesGetsTheObject() {
    val annotated = parseTestSchema(Frames::class).generateFileSpecs(ProtocolCodegenType.Host).map { it.name }
    assertThat(annotated).contains("FrameRulesGenerationTestFramesFrameRules")
    val plain = parseTestSchema(Unannotated::class)
    assertThat(plain.declaresFrameRules()).isFalse()
    assertThat(plain.generateFileSpecs(ProtocolCodegenType.Host).map { it.name }.filter { it.endsWith("FrameRules") }).isEmpty()
  }

  @Test fun theReviewListNamesExactlyTheUnclassifiedNonStringValues() {
    val schema = parseTestSchema(Frames::class).schema
    assertThat(frameRulesReview(schema).map { it.toString() }).containsExactly(
      "widget FrameRulesGenerationTestLabel (1) count (5): kotlin.Int",
      "widget FrameRulesGenerationTestLabel (1) tone (6): dev.keliver.tooling.codegen.FrameRulesGenerationTest.Tone",
      "widget FrameRulesGenerationTestFeed (2) position (3): kotlin.collections.Map<kotlin.Int, kotlin.Int>",
      "modifier FrameRulesGenerationTestTap (2) enabled: kotlin.Boolean",
    )
  }

  @Test fun theReviewFileRoundTrips() {
    val schema = parseTestSchema(Frames::class).schema
    val text = frameRulesReviewText(schema)
    assertThat(text).contains("# FrameRulesGenerationTestFramesFrameRules review list")
    assertThat(parseFrameRulesReview(text)).isEqualTo(frameRulesReview(schema).map { it.toString() })
  }

  @Test fun strictModeFailsOnAnUnclassifiedValue() {
    val schema = parseTestSchema(Frames::class).schema
    val reviewed = frameRulesReview(schema).map { it.toString() }
    // The checked-in list lacks `Tap.enabled`: a value added since the last review.
    val enabled = "modifier FrameRulesGenerationTestTap (2) enabled: kotlin.Boolean"
    assertThat(reviewed).contains(enabled)
    val report = checkFrameRulesReview(schema, reviewed - enabled, strict = true, "Frames.review.txt", "fix")
    assertThat(report.failed).isTrue()
    assertThat(report.message).all {
      contains("e: FrameRulesGenerationTestFramesFrameRules (strict): the review list Frames.review.txt is out of date.")
      contains("    $enabled")
      contains("@NotData")
      contains("@NeverCached")
      doesNotContain("count (5)")
    }
  }

  @Test fun strictModeFailsWithoutAReviewFileAndOnAStaleEntry() {
    val schema = parseTestSchema(Frames::class).schema
    val reviewed = frameRulesReview(schema).map { it.toString() }
    val missing = checkFrameRulesReview(schema, null, strict = true, "Frames.review.txt", "fix")
    assertThat(missing.failed).isTrue()
    assertThat(missing.message).contains("does not exist")
    val stale = checkFrameRulesReview(schema, reviewed + "widget Gone (9) value (1): kotlin.Int", strict = true, "f", "fix")
    assertThat(stale.failed).isTrue()
    assertThat(stale.message).all {
      contains("No longer on the list (run fix):")
      contains("    widget Gone (9) value (1): kotlin.Int")
    }
  }

  @Test fun strictModePassesOnceEveryValueIsReviewed() {
    val schema = parseTestSchema(Frames::class).schema
    val report = checkFrameRulesReview(schema, frameRulesReview(schema).map { it.toString() }, strict = true, "f", "fix")
    assertThat(report.failed).isFalse()
    assertThat(report.message).isEqualTo("")
  }

  @Test fun withoutStrictModeTheListIsAWarning() {
    val schema = parseTestSchema(Frames::class).schema
    val report = checkFrameRulesReview(schema, null, strict = false, "Frames.review.txt", "fix")
    assertThat(report.failed).isFalse()
    assertThat(report.message).all {
      contains("w: FrameRulesGenerationTestFramesFrameRules: 4 value(s) of replayable widgets and modifiers")
      contains("    widget FrameRulesGenerationTestLabel (1) count (5): kotlin.Int")
      contains("the review list Frames.review.txt is missing (run fix)")
    }
  }

  @Test fun aSchemaWithNothingReplayableHasAnEmptyReviewAndNoWarning() {
    val schema = parseTestSchema(Unannotated::class).schema
    assertThat(frameRulesReview(schema)).isEmpty()
    assertThat(checkFrameRulesReview(schema, null, strict = false, "f", "fix").message).isEqualTo("")
  }
}
