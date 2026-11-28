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
package dev.keliver.compose.generator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** NF5 A1: the frame-rule emitter (FrameRules.kt) on synthetic declarations and on `gatea`. */
class FrameRuleEmitterTest {
  private val unit = TypeRef("kotlin.Unit")
  private val onClick = TypeRef("kotlin.Function0", args = listOf(unit))

  private val label = DeclInfo(
    "androidx.compose.material3.Label(text,color,enabled,shape,onClick,modifier)",
    "androidx.compose.material3",
    "Label",
    null,
    null,
    listOf(
      ParamInfo("text", TypeRef("kotlin.String"), false),
      ParamInfo("color", TypeRef("androidx.compose.ui.graphics.Color"), false),
      ParamInfo("enabled", TypeRef("kotlin.Boolean"), false),
      ParamInfo("shape", TypeRef("androidx.compose.ui.graphics.Shape"), false),
      ParamInfo("onClick", onClick, false),
      ParamInfo("modifier", TypeRef(MODIFIER), true, "Modifier"),
    ),
    unit,
    false,
    isComposable = true,
  )

  private val tap = DeclInfo(
    "androidx.compose.foundation.tap(enabled,tint,onClick)",
    "androidx.compose.foundation",
    "tap",
    null,
    TypeRef(MODIFIER),
    listOf(
      ParamInfo("enabled", TypeRef("kotlin.Boolean"), false),
      ParamInfo("tint", TypeRef("androidx.compose.ui.graphics.Color"), true, "LocalTint.current"),
      ParamInfo("onClick", onClick, false),
    ),
    TypeRef(MODIFIER),
    false,
  )

  private fun config(frameRules: FrameRulesConfig?, scrollContainers: List<String> = emptyList()) = Config(
    "g", "dev.example.schema", "S", emptyList(), listOf(label.spec, tap.spec), emptyList(), emptyMap(), emptyList(), emptyList(),
    scrollContainers = scrollContainers,
    frameRules = frameRules,
  )

  private fun schemaOf(config: Config): String =
    Emitter(config, plan(config, listOf(label, tap), LockBuilder(Lock("g")))).schema().second

  private fun rules(vararg exceptions: FrameRuleException) = FrameRulesConfig(exceptions.toList())

  @Test fun aGroupWithoutFrameRulesIsUnannotated() {
    val schema = schemaOf(config(null))
    assertFalse("@Replayable" in schema, schema)
    assertFalse("@NotData" in schema)
    assertFalse("import dev.keliver.schema.NotData" in schema)
    assertTrue("  @Property(1) val text: kotlin.String,\n" in schema, schema)
    // The import block is as before the frame rules.
    assertTrue("import dev.keliver.schema.Widget\n\n" in schema)
  }

  @Test fun eachValueGetsItsCategorysAnnotation() {
    val schema = schemaOf(config(rules()))
    assertTrue("@Replayable\n@Widget(1)\npublic data class GenLabel1(\n" in schema, schema)
    assertTrue("  @Property(1) val text: kotlin.String,\n" in schema, "text: none, so Shell replaces it")
    assertTrue("  @Property(2) @NotData val color: dev.keliver.compose.ui.graphics.Color,\n" in schema)
    assertTrue("  @Property(3) val enabled: kotlin.Boolean,\n" in schema, "a primitive: left for the review list")
    assertTrue("  @Property(4) @StyleToken val shape: dev.keliver.compose.ui.graphics.Shape,\n" in schema)
    assertTrue("  @Property(5) val onClick: (() -> Unit)?,\n" in schema, "an event: no value")
    assertTrue("import dev.keliver.schema.NotData\nimport dev.keliver.schema.Replayable\nimport dev.keliver.schema.StyleToken\n\n" in schema, schema)
  }

  @Test fun aModifiersBookkeepingIsNotData() {
    val schema = schemaOf(config(rules()))
    assertTrue("@Replayable\n@Modifier(1)\npublic data class GenTap1(\n" in schema, schema)
    assertTrue("  val enabled: kotlin.Boolean,\n" in schema)
    assertTrue("  @NotData val tint: dev.keliver.compose.ui.graphics.Color,\n" in schema)
    assertTrue("  @NotData val tintIsDefault: kotlin.Boolean,\n" in schema, "a case-2 default's flag")
    assertTrue("  @NotData val onClick: kotlin.Int,\n" in schema, "an event's handle")
  }

  @Test fun exceptionsClassifyByParameterOrByDeclaration() {
    val schema = schemaOf(
      config(
        rules(
          FrameRuleException(null, "enabled", "NotData", false, "everywhere in the group"),
          FrameRuleException(label.spec, "text", "StyleToken", false, "this text is a code"),
        ),
      ),
    )
    assertTrue("  @Property(3) @NotData val enabled: kotlin.Boolean,\n" in schema, schema)
    assertTrue("  @NotData val enabled: kotlin.Boolean,\n" in schema)
    assertTrue("  @Property(1) @StyleToken val text: kotlin.String,\n" in schema)
  }

  @Test fun anExceptionCanTakeADeclarationOffReplayable() {
    val schema = schemaOf(config(rules(FrameRuleException(label.spec, null, null, true, "a dialog"))))
    assertTrue("/\n@Widget(1)\npublic data class GenLabel1(" in schema, schema)
    assertTrue("@Replayable\n@Modifier(1)" in schema, "the other declarations keep theirs")
  }

  @Test fun aScrollContainerScrolls() {
    val schema = schemaOf(config(rules(), scrollContainers = listOf(label.spec)))
    assertTrue("@Replayable\n@Scrolls\n@Widget(1)\n" in schema, schema)
  }

  @Test fun anUnclassifiedTypeFailsClosed() {
    val unknown = Classified(ParamInfo("thing", TypeRef("com.example.Unknown"), false), ParamKind.VALUE, DefaultCase.NONE)
    val p = Plan.Generated(label, LockEntry(1, "GenLabel1", mapOf("thing" to 1), 1), listOf(unknown))
    val emitter = FrameRuleEmitter(config(rules()), listOf(p))
    assertEquals("", emitter.memberAnnotations(p), "no @Replayable: nothing is replayable by default")
    assertEquals("", emitter.valueAnnotation(p, unknown))
  }

  @Test fun anExceptionThatMatchesNothingIsRefused() {
    val config = config(rules(FrameRuleException(null, "nope", "NotData", false, "stale")))
    val plans = plan(config, listOf(label, tap), LockBuilder(Lock("g"))).filterIsInstance<Plan.Generated>()
    val message = runCatching { FrameRuleEmitter(config, plans).checkExceptions() }.exceptionOrNull()?.message.orEmpty()
    assertTrue("g.json frameRules: this exception matches no generated parameter: *#nope" in message, message)
    // One that matches passes. Each generation runs this check over the whole group, so
    // checkComposeApi fails on a stale exception in gatea.json.
    FrameRuleEmitter(config(rules(FrameRuleException(label.spec, "text", "StyleToken", false, "x"))), plans).checkExceptions()
  }

  @Test fun theConfigSaysWhyForEachException() {
    val o = Json.parseToJsonElement("""{"exceptions": [{"param": "alpha", "rule": "NotData"}]}""").jsonObject
    val message = runCatching { loadFrameRules(o) }.exceptionOrNull()?.message.orEmpty()
    assertTrue("every exception says why" in message, message)
    val bad = Json.parseToJsonElement("""{"exceptions": [{"param": "alpha", "rule": "Data", "why": "x"}]}""").jsonObject
    assertNotNull(runCatching { loadFrameRules(bad) }.exceptionOrNull())
  }

  @Test fun onlyThePrototypeHasFrameRules() {
    // MS4_PLAN 2.2: A1 classifies the prototype's group; the product's groups are MS4-36's.
    assertNotNull(loadConfig(File("gatea.json")).frameRules)
    assertNull(loadConfig(File("core.json")).frameRules)
    assertNull(loadConfig(File("coil.json")).frameRules)
  }

  @Test fun everyGeneratedMemberOfThePrototypeIsReplayable() {
    // The checked-in schema (its staleness guard is checkComposeApi): every generated member is
    // replayable, so a gatea screen built from them can be recorded.
    val schema = File("../keliver-compose-gatea-schema/src/main/kotlin/dev/keliver/compose/gatea/Generated.kt").readText()
    val members = Regex("""^@(Widget|Modifier)\(""", RegexOption.MULTILINE).findAll(schema).count()
    val replayable = Regex("""^@Replayable\n(@Scrolls\n)?@(Widget|Modifier)\(""", RegexOption.MULTILINE).findAll(schema).count()
    assertEquals(members, replayable, "a generated member of gatea is not @Replayable")
    assertEquals(4, Regex("""^@Scrolls$""", RegexOption.MULTILINE).findAll(schema).count(), "the lazy lists and the scroll modifiers")
  }
}
