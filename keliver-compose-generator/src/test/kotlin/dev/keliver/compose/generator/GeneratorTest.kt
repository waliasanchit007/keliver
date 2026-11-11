/*
 * Copyright (C) 2026 Square, Inc.
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
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GeneratorTest {
  @Test fun packageRule() {
    assertEquals("dev.keliver.compose.material3", mirrorPackage("androidx.compose.material3"))
    assertEquals("dev.keliver.coil3.compose", mirrorPackage("coil3.compose"))
    assertEquals("dev.keliver.compose.ui.Alignment.Vertical", mirrorFq("androidx.compose.ui.Alignment.Vertical"))
    assertEquals("kotlin.String", mirrorFq("kotlin.String"))
  }

  @Test fun literalDefaults() {
    listOf("null", "true", "1", "1.0f", "Int.MAX_VALUE", "Color.Unspecified", "TextOverflow.Clip", "Alignment.TopStart", "0.dp", "RectangleShape")
      .forEach { assertTrue(isLiteralDefault(it), it) }
    listOf("LocalTextStyle.current", "LocalContentColor.current", "DefaultAlpha", "ButtonDefaults.shape", "elevation > 0.dp", "error")
      .forEach { assertFalse(isLiteralDefault(it), it) }
  }

  @Test fun composableSlotTypes() {
    val slot = TypeRef("kotlin.Function0", args = listOf(TypeRef("kotlin.Unit")), composable = true, receiver = TypeRef("androidx.compose.foundation.layout.RowScope"))
    assertEquals("@Composable androidx.compose.foundation.layout.RowScope.()->kotlin.Unit", slot.canonical())
    assertEquals(
      "@androidx.compose.runtime.Composable dev.keliver.compose.foundation.layout.RowScope.() -> kotlin.Unit",
      guestType(slot),
    )
  }

  @Test fun lockIsAppendOnly() {
    val decl = DeclInfo(
      "x",
      "androidx.compose.foundation.layout",
      "padding",
      null,
      TypeRef(MODIFIER),
      listOf(ParamInfo("all", TypeRef("androidx.compose.ui.unit.Dp"), false)),
      TypeRef(MODIFIER),
      false,
    )
    val first = LockBuilder(Lock("g")).apply { modifier(decl) }.build()
    assertEquals(1, first.level)
    assertEquals("GenPadding1", first.modifiers.values.single().name)
    // Regenerating the same API appends nothing: the level stays.
    val again = LockBuilder(first).apply { modifier(decl) }.build()
    assertEquals(first, again)
    // A removed key or a changed tag is refused.
    assertTrue(checkAppendOnly(first, first.copy(modifiers = emptyMap())).isNotEmpty())
    val changed = first.copy(modifiers = first.modifiers.mapValues { it.value.copy(tag = 9) })
    assertTrue(checkAppendOnly(first, changed).isNotEmpty())
    // A new overload is appended with the next tag and a new level.
    val other = decl.copy(name = "padding", params = listOf(ParamInfo("horizontal", TypeRef("androidx.compose.ui.unit.Dp"), true)))
    val next = LockBuilder(first).apply { modifier(other) }.build()
    assertEquals(2, next.level)
    assertEquals(listOf(1, 2), next.modifiers.values.map { it.tag }.sorted())
    assertTrue(checkAppendOnly(first, next).isEmpty())
  }

  @Test fun codecNames() {
    assertEquals("dev.keliver.compose.ui.graphics.ColorCodec", codecFq("dev.keliver.compose.ui.graphics.Color"))
    assertEquals("dev.keliver.compose.ui.HorizontalAlignmentCodec", codecFq("dev.keliver.compose.ui.Alignment.Horizontal"))
    assertEquals("dev.keliver.compose.foundation.layout.VerticalArrangementCodec", codecFq("dev.keliver.compose.foundation.layout.Arrangement.Vertical"))
  }

  @Test fun editorMetadataHasWhatED0Reads() {
    // ED0 (docs/ED0_SPIKE.md): every parameter in Compose's order (modifier too), guest names, types and scopes.
    val o = kotlinx.serialization.json.Json.parseToJsonElement(File("../keliver-compose-gatea-schema/gatea.editor.json").readText()).jsonObject
    fun entries(k: String) = o.getValue(k).jsonArray.map { it.jsonObject }
    fun s(e: kotlinx.serialization.json.JsonObject, k: String) = e[k]?.jsonPrimitive?.content
    val text = entries("widgets").first { s(it, "name") == "Text" }
    assertEquals("dev.keliver.compose.material3.Text", s(text, "guest"))
    val params = text.getValue("params").jsonArray.map { it.jsonObject }
    assertEquals(listOf("text", "modifier", "color"), params.take(3).map { s(it, "name") })
    assertEquals("dev.keliver.compose.ui.graphics.Color", s(params[2], "type"))
    val icon = entries("widgets").first { s(it, "name") == "Icon" }
    assertEquals("true", icon.getValue("params").jsonArray.map { it.jsonObject }.first { s(it, "name") == "contentDescription" }["nullable"]?.jsonPrimitive?.content)
    val row = entries("widgets").first { s(it, "name") == "Row" }
    assertEquals("dev.keliver.compose.foundation.layout.RowScope", row.getValue("params").jsonArray.map { it.jsonObject }.first { s(it, "name") == "content" }.let { s(it, "scope") })
    val weight = entries("modifiers").first { s(it, "compose") == "androidx.compose.foundation.layout.RowScope.weight" }
    assertEquals("dev.keliver.compose.foundation.layout.RowScope", s(weight, "scope"))
  }

  @Test fun checkedInLockMatchesItsConfig() {
    // The lock lives beside the config; every configured declaration that is generated has an entry.
    val lock = lockJson.decodeFromString(Lock.serializer(), File("gatea.lock.json").readText())
    assertEquals("gatea", lock.group)
    assertTrue(lock.widgets.keys.any { it.startsWith("androidx.compose.material3/Text(") })
    assertTrue(lock.modifiers.values.all { it.since <= lock.level })
  }
}
