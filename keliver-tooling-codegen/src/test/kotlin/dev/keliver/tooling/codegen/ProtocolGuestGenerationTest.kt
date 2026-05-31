/*
 * Copyright (C) 2021 Square, Inc.
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

import dev.keliver.schema.Modifier
import dev.keliver.schema.Property
import dev.keliver.schema.Schema
import dev.keliver.schema.Widget
import dev.keliver.tooling.schema.parseTestSchema
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.time.Duration
import org.junit.Test

class ProtocolGuestGenerationTest {
  @Schema(
    [
      IdPropertyNameCollisionNode::class,
    ],
  )
  interface IdPropertyNameCollisionSchema

  @Widget(1)
  data class IdPropertyNameCollisionNode(
    @Property(1) val label: String,
    @Property(2) val id: String,
  )

  @Test fun `id property does not collide`() {
    val schema = parseTestSchema(IdPropertyNameCollisionSchema::class).schema

    val fileSpec = generateProtocolWidget(schema, schema, schema.widgets.single())
    assertThat(fileSpec.toString()).contains(
      """
      |  override fun id(id: String) {
      |    this.guestAdapter.appendPropertyChange(this.id,
      """.trimMargin(),
    )
  }

  // --- U13: stdlib custom-type modifiers use the builtins serializer ---

  object StdlibScope

  @Schema(
    [
      StdlibModifierNode::class,
      DurationModifier::class,
    ],
  )
  interface StdlibModifierSchema

  @Widget(1)
  data class StdlibModifierNode(
    @Property(1) val label: String,
  )

  @Modifier(1, StdlibScope::class)
  data class DurationModifier(
    val duration: Duration,
  )

  /**
   * Regression for KNOWN_BUGS U13. A `kotlin.time.Duration` custom-type
   * modifier property has no compiler-generated companion `.serializer()`
   * — its serializer lives in `kotlinx.serialization.builtins`. The
   * generated modifier serializer must reference the builtins extension
   * (with its import), or the output fails to compile with
   * `Unresolved reference 'serializer'`.
   */
  @Test fun `stdlib custom-type modifier uses builtins serializer`() {
    val schema = parseTestSchema(StdlibModifierSchema::class).schema

    val fileSpec = generateProtocolModifierSerializers(schema, schema)!!
    assertThat(fileSpec.toString()).contains("import kotlinx.serialization.builtins.serializer")
    assertThat(fileSpec.toString()).contains("Duration.serializer()")
  }

  // --- U10: user @Serializable type modifiers keep the companion serializer ---

  object EnumScope

  // Intentionally NOT @Serializable here — the U10 fallback emits
  // `.serializer()` for any ClassName regardless, because the schema
  // parser can't detect cross-module @Serializable annotations. That's
  // exactly the scenario the fallback exists to cover; the generated
  // TEXT (companion `.serializer()`, no builtins import) is what we
  // assert.
  enum class CustomEnum { A, B }

  @Schema(
    [
      EnumModifierNode::class,
      EnumModifier::class,
    ],
  )
  interface EnumModifierSchema

  @Widget(1)
  data class EnumModifierNode(
    @Property(1) val label: String,
  )

  @Modifier(1, EnumScope::class)
  data class EnumModifier(
    val value: CustomEnum,
  )

  /**
   * Regression for KNOWN_BUGS U10 (preserved by the U13 fix). A user
   * `@Serializable` type uses its compiler-generated companion
   * `.serializer()` accessor — NOT the builtins extension. The U13 fix
   * (builtins routing) must apply only to stdlib `kotlin.*` types; user
   * types in non-kotlin packages keep the companion fallback that
   * prevents the U10 silent white-screen.
   */
  @Test fun `user serializable-type modifier keeps companion serializer`() {
    val schema = parseTestSchema(EnumModifierSchema::class).schema

    val fileSpec = generateProtocolModifierSerializers(schema, schema)!!
    assertThat(fileSpec.toString()).contains("CustomEnum.serializer()")
    // The user enum must NOT be routed through the builtins extension.
    assertThat(fileSpec.toString()).doesNotContain("import kotlinx.serialization.builtins.serializer")
  }
}
