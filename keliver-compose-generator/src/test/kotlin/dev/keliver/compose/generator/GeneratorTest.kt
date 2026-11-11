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

  private fun widgetDecl(name: String, vararg params: Pair<String, Boolean>) = DeclInfo(
    "x",
    "androidx.compose.material3",
    name,
    null,
    null,
    params.map { (n, d) -> ParamInfo(n, TypeRef("kotlin.Int"), hasDefault = d, defaultText = if (d) "1" else null) },
    TypeRef("kotlin.Unit"),
    false,
    isComposable = true,
  )

  @Test fun aParameterThatStartsTravellingIsAnAppend() {
    // Gate A wrote entries without wire shapes: the next generation fills them at the entry's level.
    val decl = widgetDecl("Thing", "a" to false, "b" to true)
    val gateA = LockBuilder(Lock("g")).apply { widget(decl) }.build().let { l ->
      l.copy(widgets = l.widgets.mapValues { it.value.copy(wire = null) })
    }
    val filled = LockBuilder(gateA).apply { widget(decl, wire = listOf("a")) }.build()
    assertEquals(gateA.level, filled.level, "filling a wire shape appends nothing")
    assertEquals(mapOf("a" to 1), filled.widgets.values.single().wire)
    assertTrue(checkAppendOnly(gateA, filled).isEmpty())
    // `b`'s type gains a codec: it now travels, at a new level, with the tag it always had.
    val grown = LockBuilder(filled).apply { widget(decl, wire = listOf("a", "b")) }.build()
    assertEquals(filled.level + 1, grown.level)
    assertEquals(mapOf("a" to 1, "b" to 2), grown.widgets.values.single().wire)
    assertEquals(filled.widgets.values.single().params, grown.widgets.values.single().params)
    assertTrue(checkAppendOnly(filled, grown).isEmpty())
    // Stopping is not an append.
    val shrunk = LockBuilder(grown).apply { widget(decl, wire = listOf("a")) }.build()
    assertTrue(checkAppendOnly(grown, grown.copy(widgets = shrunk.widgets.mapValues { it.value.copy(wire = mapOf("a" to 1)) })).isNotEmpty())
  }

  @Test fun syntheticParametersTakeTheNextFreeTags() {
    val decl = widgetDecl("LazyThing", "modifier" to true, "state" to true, "content" to false)
    val lock = LockBuilder(Lock("g")).apply { widget(decl, wire = listOf("state", "content"), synthetic = listOf("content.before", "content.after")) }.build()
    val params = lock.widgets.values.single().params
    assertEquals(mapOf("modifier" to 1, "state" to 2, "content" to 3, "content.before" to 4, "content.after" to 5), params)
    // A later synthetic parameter is appended after them, never renumbering.
    val more = LockBuilder(lock).apply { widget(decl, wire = listOf("state", "content"), synthetic = listOf("content.before", "content.after", "content.viewport")) }.build()
    assertEquals(6, more.widgets.values.single().params["content.viewport"])
    assertEquals(lock.level + 1, more.level)
    assertTrue(checkAppendOnly(lock, more).isEmpty())
  }

  @Test fun factoriesHaveTheirOwnTagsFromOne() {
    val f1 = DeclInfo("x", "androidx.compose.material3", "buttonColors", "ButtonDefaults", null, emptyList(), TypeRef("androidx.compose.material3.ButtonColors"), false)
    val f2 = f1.copy(params = listOf(ParamInfo("containerColor", TypeRef("androidx.compose.ui.graphics.Color"), true)))
    val lock = LockBuilder(Lock("g")).apply {
      factory(f1, emptyList())
      factory(f2, listOf("containerColor"))
    }.build()
    assertEquals(listOf(1, 2), lock.factories.values.map { it.tag }.sorted())
    assertEquals(setOf("ButtonDefaults.buttonColors1", "ButtonDefaults.buttonColors2"), lock.factories.values.map { it.name }.toSet())
    assertTrue(checkAppendOnly(lock, lock.copy(factories = emptyMap())).isNotEmpty(), "a removed factory is refused")
  }

  @Test fun aHiddenOverloadKeepsItsEntryAndCallsItsSuccessor() {
    val old = widgetDecl("Text", "text" to false, "maxLines" to true)
    val lock = LockBuilder(Lock("g")).apply { widget(old) }.build()
    val hidden = old.copy().apply { deprecation = "HIDDEN" }
    val current = widgetDecl("Text", "text" to false, "maxLines" to true, "autoSize" to true)
    val builder = LockBuilder(lock)
    val config = Config("g", "p", "S", emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())
    val plans = plan(config, listOf(hidden, current), builder).filterIsInstance<Plan.Generated>()
    val next = builder.build()
    val h = plans.single { it.hidden }
    assertEquals(lock.widgets.values.single().tag, h.entry.tag, "the hidden overload keeps its tag")
    assertEquals(current, h.successor)
    assertEquals(listOf("text", "maxLines"), h.called.map { it.param.name })
    assertEquals(lock.level + 1, next.level, "the visible overload is an append")
    assertTrue(checkAppendOnly(lock, next).isEmpty())
    // Without its successor configured, a hidden overload is refused.
    assertTrue(runCatching { plan(config, listOf(hidden), LockBuilder(lock)) }.isFailure)
  }

  @Test fun deprecationIsReadFromTheSources() {
    assertEquals("Use the other one", stringLiteral("\"Use the \" + \"other one\""))
  }

  @Test fun checkedInLockMatchesItsConfig() {
    // The lock lives beside the config; every configured declaration that is generated has an entry.
    val lock = lockJson.decodeFromString(Lock.serializer(), File("gatea.lock.json").readText())
    assertEquals("gatea", lock.group)
    assertTrue(lock.widgets.keys.any { it.startsWith("androidx.compose.material3/Text(") })
    assertTrue(lock.modifiers.values.all { it.since <= lock.level })
  }

  @Test fun theLockCarriesGateAsWithAppendsOnly() {
    // Gate A's lock (level 2, written from Compose Multiplatform 1.8.2), kept as it was merged. Every
    // regeneration since (the stack's Compose, then Gate B's wider set) may only append to it.
    val gateA = lockJson.decodeFromString(Lock.serializer(), File("src/test/resources/gate-a.lock.json").readText())
    val now = lockJson.decodeFromString(Lock.serializer(), File("gatea.lock.json").readText())
    assertEquals(2, gateA.level)
    assertEquals(emptyList(), checkAppendOnly(gateA, now))
    for ((key, entry) in gateA.widgets + gateA.modifiers) {
      val kept = now.widgets[key] ?: now.modifiers.getValue(key)
      assertEquals(entry.tag to entry.params, kept.tag to kept.params, key)
      // Gate A's entries all travelled from their own level: filling `wire` appended nothing.
      assertTrue(kept.wire.orEmpty().values.all { it >= entry.since }, key)
    }
  }
}
