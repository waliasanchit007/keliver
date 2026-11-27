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
import assertk.assertions.doesNotContain
import dev.keliver.layout.RedwoodLayout
import dev.keliver.schema.Property
import dev.keliver.schema.Schema
import dev.keliver.schema.Schema.Dependency
import dev.keliver.schema.Widget
import dev.keliver.tooling.schema.parseTestSchema
import org.junit.Test

/**
 * MS4-03 (#244 decision 3): a root with no members of its own (Keliver's generated root) lets a
 * host leave a group out. Every other schema's generated code is unchanged.
 */
class AbsentGroupGenerationTest {
  @Schema(
    members = [],
    dependencies = [
      Dependency(1, RedwoodLayout::class),
    ],
  )
  interface GroupsRoot

  @Schema(
    members = [Card::class],
    dependencies = [
      Dependency(1, RedwoodLayout::class),
    ],
  )
  interface SchemaWithMembers

  @Widget(1)
  data class Card(@Property(1) val title: String)

  @Test fun aRootsWidgetSystemTakesEachGroupAsNullable() {
    val schemaSet = parseTestSchema(GroupsRoot::class)
    assertThat(generateWidgetSystem(schemaSet).toString()).all {
      contains("RedwoodLayout: RedwoodLayoutWidgetFactory<W>?,")
      contains("public val absentWidget: () -> Widget<W>,")
      contains("public val RedwoodLayoutOrNull: RedwoodLayoutWidgetFactory<W>? = RedwoodLayout")
      // A guest's composables still find the group through its owner interface.
      contains("RedwoodLayoutWidgetFactoryOwner<W>")
      contains("get() = RedwoodLayoutOrNull ?: throw IllegalStateException(\"group RedwoodLayout is absent from this host\")")
      contains("RedwoodLayoutOrNull?.let { RedwoodLayoutWidgetFactoryOwner.apply(it, value, element) }")
    }
  }

  @Test fun aSchemaWithMembersKeepsItsRequiredDependencies() {
    val schemaSet = parseTestSchema(SchemaWithMembers::class)
    assertThat(generateWidgetSystem(schemaSet).toString()).all {
      contains("override val RedwoodLayout: RedwoodLayoutWidgetFactory<W>,")
      doesNotContain("OrNull")
      doesNotContain("absentWidget")
    }
  }

  @Test fun anAbsentGroupsWidgetIsReportedOnceAndStandsInAsAnAbsentNode() {
    val schemaSet = parseTestSchema(GroupsRoot::class)
    val layout = schemaSet.dependencies.values.single()
    val box = layout.widgets.first { it.type.flatName == "Box" }
    assertThat(generateProtocolNode(schemaSet.schema, layout, box).toString()).all {
      contains("private var absentReported: Boolean = false")
      contains("val factory = schemaWidgetSystem.RedwoodLayoutOrNull")
      contains(
        """
        |    if (factory == null) {
        |      if (!absentReported) {
        |        absentReported = true
        |        mismatchHandler.onUnknownWidget(WidgetTag(${box.tag.withUnderscores()}))
        |      }
        |      return AbsentProtocolNode(id, WidgetTag(${box.tag.withUnderscores()}), "Box", schemaWidgetSystem.absentWidget(), childrenTags)
        |    }
        |    val widget = factory.Box()
        """.trimMargin(),
      )
    }
  }

  @Test fun aSchemaWithMembersCreatesItsDependencysWidgetsDirectly() {
    val schemaSet = parseTestSchema(SchemaWithMembers::class)
    val layout = schemaSet.dependencies.values.single()
    val box = layout.widgets.first { it.type.flatName == "Box" }
    assertThat(generateProtocolNode(schemaSet.schema, layout, box).toString()).all {
      contains("val widget = schemaWidgetSystem.RedwoodLayout.Box()")
      doesNotContain("absentReported")
      doesNotContain("AbsentProtocolNode")
    }
  }

  @Test fun aGuestBuildsTheRootWithEveryGroup() {
    val schemaSet = parseTestSchema(GroupsRoot::class)
    assertThat(generateProtocolWidgetSystemFactory(schemaSet).toString()).all {
      contains("RedwoodLayout = ")
      contains("absentWidget = { throw AssertionError(\"a guest has every group\") }")
    }
  }

  /** KotlinPoet writes large integer literals with underscores (`1_000_001`). */
  private fun Int.withUnderscores(): String = toString().reversed().chunked(3).joinToString("_").reversed()
}
