/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
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
    val decl = DeclInfo("x", "androidx.compose.foundation.layout", "padding", null, TypeRef(MODIFIER),
      listOf(ParamInfo("all", TypeRef("androidx.compose.ui.unit.Dp"), false)), TypeRef(MODIFIER), false)
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

  @Test fun checkedInLockMatchesItsConfig() {
    // The lock lives beside the config; every configured declaration that is generated has an entry.
    val lock = lockJson.decodeFromString(Lock.serializer(), File("gatea.lock.json").readText())
    assertEquals("gatea", lock.group)
    assertTrue(lock.widgets.keys.any { it.startsWith("androidx.compose.material3/Text(") })
    assertTrue(lock.modifiers.values.all { it.since <= lock.level })
  }
}
