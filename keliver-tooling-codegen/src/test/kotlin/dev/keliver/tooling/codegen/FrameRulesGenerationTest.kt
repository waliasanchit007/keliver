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

  @Test fun theReviewListHasALinePerReplayableMemberAndPerUnclassifiedNonStringValue() {
    val schemaSet = parseTestSchema(Frames::class)
    val section = "FrameRulesGenerationTestFrames"
    // Every replayable member (even Pad and ScrollY, which have nothing to review) is a line: adding
    // one is a reviewed choice. Popup is not replayable. No wire tag appears.
    assertThat(frameRulesReview(schemaSet).filter { it.startsWith("$section:") }).containsExactly(
      "$section: widget FrameRulesGenerationTestFeed",
      "$section: widget FrameRulesGenerationTestFeed.position: kotlin.collections.Map<kotlin.Int, kotlin.Int>",
      "$section: widget FrameRulesGenerationTestLabel",
      "$section: widget FrameRulesGenerationTestLabel.count: kotlin.Int",
      "$section: widget FrameRulesGenerationTestLabel.tone: dev.keliver.tooling.codegen.FrameRulesGenerationTest.Tone",
      "$section: modifier FrameRulesGenerationTestPad",
      "$section: modifier FrameRulesGenerationTestScrollY",
      "$section: modifier FrameRulesGenerationTestTap",
      "$section: modifier FrameRulesGenerationTestTap.enabled: kotlin.Boolean",
    )
  }

  @Test fun theListHasASectionPerSchemaOfTheSet() {
    val lines = frameRulesReview(parseTestSchema(Frames::class))
    // The layout dependency declares no replayable member, so it has no line; a dependency that does
    // has its own lines, each starting with its own schema's name.
    assertThat(lines.map { it.substringBefore(':') }.toSet()).isEqualTo(setOf("FrameRulesGenerationTestFrames"))
  }

  @Test fun aLockAssignedNumberAndNameDoNotReachTheList() {
    assertThat(reviewName("GenSurface2")).isEqualTo("Surface")
    assertThat(reviewName("GenColumnScopeAnimatedVisibility1")).isEqualTo("ColumnScopeAnimatedVisibility")
    assertThat(reviewName("Heading1")).isEqualTo("Heading1")
    assertThat(reviewName("Generic2")).isEqualTo("Generic2")
  }

  @Test fun theListNamesNoTag() {
    assertThat(frameRulesReview(parseTestSchema(Frames::class)).none { it.contains(Regex("\\(\\d+\\)")) }).isTrue()
  }

  @Test fun theReviewFileRoundTrips() {
    val schemaSet = parseTestSchema(Frames::class)
    val text = frameRulesReviewText(schemaSet)
    assertThat(text).contains("# FrameRulesGenerationTestFramesFrameRules review list")
    assertThat(text).contains("@BlocksFrameWhenTrue")
    assertThat(parseFrameRulesReview(text)).isEqualTo(frameRulesReview(schemaSet))
  }

  @Test fun strictModeFailsOnAnUnclassifiedValue() {
    val schemaSet = parseTestSchema(Frames::class)
    val reviewed = frameRulesReview(schemaSet)
    // The checked-in list lacks `Tap.enabled`: a value added since the last review.
    val enabled = "FrameRulesGenerationTestFrames: modifier FrameRulesGenerationTestTap.enabled: kotlin.Boolean"
    assertThat(reviewed).contains(enabled)
    val report = checkFrameRulesReview(schemaSet, reviewed - enabled, strict = true, "Frames.review.txt", ":app:fix")
    assertThat(report.failed).isTrue()
    assertThat(report.message).all {
      contains("e: FrameRulesGenerationTestFramesFrameRules (strict): the review list Frames.review.txt is out of date.")
      contains("    $enabled")
      contains("@NotData")
      contains("@NeverCached")
      contains("with :app:fix.")
      doesNotContain("Label.count")
    }
  }

  @Test fun strictModeFailsOnANewReplayableMember() {
    val schemaSet = parseTestSchema(Frames::class)
    val member = "FrameRulesGenerationTestFrames: modifier FrameRulesGenerationTestPad"
    val reviewed = frameRulesReview(schemaSet)
    assertThat(reviewed).contains(member)
    val report = checkFrameRulesReview(schemaSet, reviewed - member, strict = true, "f", "fix")
    assertThat(report.failed).isTrue()
    assertThat(report.message).contains("    $member")
  }

  @Test fun strictModeFailsWithoutAReviewFileAndOnAStaleEntry() {
    val schemaSet = parseTestSchema(Frames::class)
    val reviewed = frameRulesReview(schemaSet)
    val missing = checkFrameRulesReview(schemaSet, null, strict = true, "Frames.review.txt", "fix")
    assertThat(missing.failed).isTrue()
    assertThat(missing.message).contains("does not exist")
    val gone = "FrameRulesGenerationTestFrames: widget Gone.value: kotlin.Int"
    val stale = checkFrameRulesReview(schemaSet, reviewed + gone, strict = true, "f", "fix")
    assertThat(stale.failed).isTrue()
    assertThat(stale.message).all {
      contains("No longer on the list (run fix):")
      contains("    $gone")
    }
  }

  @Test fun strictModePassesOnceEveryValueIsReviewed() {
    val schemaSet = parseTestSchema(Frames::class)
    val report = checkFrameRulesReview(schemaSet, frameRulesReview(schemaSet), strict = true, "f", "fix")
    assertThat(report.failed).isFalse()
    assertThat(report.message).isEqualTo("")
  }

  @Test fun withoutStrictModeTheListIsAWarning() {
    val report = checkFrameRulesReview(parseTestSchema(Frames::class), null, strict = false, "Frames.review.txt", "fix")
    assertThat(report.failed).isFalse()
    assertThat(report.message).all {
      contains("w: FrameRulesGenerationTestFramesFrameRules: 4 value(s) of replayable widgets and modifiers")
      contains("    FrameRulesGenerationTestFrames: widget FrameRulesGenerationTestLabel.count: kotlin.Int")
      contains("the review list Frames.review.txt is missing (run fix)")
    }
  }

  @Test fun aSchemaWithoutFrameRulesHasNoReviewAndNoWarning() {
    val schemaSet = parseTestSchema(Unannotated::class)
    assertThat(schemaSet.declaresFrameRules()).isFalse()
    assertThat(checkFrameRulesReview(schemaSet, null, strict = false, "f", "fix").message).isEqualTo("")
    assertThat(checkFrameRulesReview(schemaSet, null, strict = true, "f", "fix").failed).isFalse()
  }
}
