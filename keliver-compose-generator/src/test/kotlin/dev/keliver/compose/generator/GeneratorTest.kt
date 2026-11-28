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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

  @Test fun aGroupBesideTheValuesOwnerReadsItsFactoryTags() {
    // MS4-01: core writes keliver-compose-values, so the `*Defaults` stubs send core's factory tags;
    // the prototype, generated beside it, decodes with those tags and appends no factory of its own.
    val f1 = DeclInfo("x", "androidx.compose.material3", "buttonColors", "ButtonDefaults", null, emptyList(), TypeRef("androidx.compose.material3.ButtonColors"), false)
    val f2 = f1.copy(params = listOf(ParamInfo("containerColor", TypeRef("androidx.compose.ui.graphics.Color"), true)))
    val owner = LockBuilder(Lock("core")).apply {
      factory(f2, listOf("containerColor"))
      factory(f1, emptyList())
    }.build()
    val own = Lock("gatea", level = 3)
    val beside = LockBuilder(own, factoryOwner = owner)
    assertEquals(owner.factories.getValue(f1.key), beside.factory(f1, emptyList()))
    assertEquals(2, beside.factory(f1, emptyList()).tag, "the owner's tag, not the next free one of this group")
    val built = beside.build()
    assertEquals(own, built, "nothing appended to this group's own lock")
    // A factory the owner has not generated is an error, not a fresh tag that the values do not send.
    val unknown = f1.copy(name = "textButtonColors")
    val message = runCatching { LockBuilder(own, factoryOwner = owner).factory(unknown, emptyList()) }.exceptionOrNull()?.message.orEmpty()
    assertTrue("generate group core first" in message, message)
    // So is a different wire shape: the guest's call record and the host's decoding would disagree.
    val reshaped = runCatching { LockBuilder(own, factoryOwner = owner).factory(f2, emptyList()) }.exceptionOrNull()?.message.orEmpty()
    assertTrue("the core lock sends" in reshaped, reshaped)
  }

  @Test fun eachGroupsFilesNameItsOwnConfig() {
    assertTrue("keliver-compose-generator/core.json" in header("core"))
    assertTrue("keliver-compose-generator/gatea.json" in header("gatea"))
    // keliver-compose-values is written by core, the group that owns its factory tags.
    val values = File("../keliver-compose-values/src/commonMain/kotlin/dev/keliver/compose/material3/GeneratedDefaults.kt").readText()
    assertTrue(values.startsWith(header("core")), values.lineSequence().take(2).joinToString("\n"))
  }

  @Test fun theProductCoreGroupIsGeneratedBesideThePrototype() {
    // MS4-01: every prototype declaration is in the product's groups (core, and coil since MS4-03), so
    // the prototype keeps building on core's values. MS4-06: except the overloads Compose now hides,
    // which the prototype keeps because Gate A's guests send their tags; the product's fresh locks
    // have no older guest, and a hidden overload needs an entry it already had.
    val core = loadConfig(File("core.json"))
    val coil = loadConfig(File("coil.json"))
    val gatea = loadConfig(File("gatea.json"))
    assertEquals("core", core.group)
    assertEquals("dev.keliver.compose.core", core.schemaPackage)
    assertEquals(
      listOf(
        "androidx.compose.foundation.clickable(enabled,onClickLabel,role,onClick)",
        "androidx.compose.material3.Text(text,modifier,color,fontSize,fontStyle,fontWeight,fontFamily,letterSpacing,textDecoration,textAlign,lineHeight,overflow,softWrap,maxLines,minLines,onTextLayout,style)",
      ),
      gatea.declarations - (core.declarations + coil.declarations).toSet(),
      "prototype declarations missing from the product",
    )
    assertEquals(emptyList(), gatea.errors.map { it.first } - core.errors.map { it.first }.toSet())
    assertEquals(gatea.extras, core.extras)
    assertEquals(gatea.extraModifiers, core.extraModifiers)
    // The product's group has no legacy schema dependency (the prototype has layout, lazy layout and material).
    assertEquals(emptyList(), core.dependencies, "core depends on no legacy schema")
    assertTrue(gatea.dependencies.any { it.second == "dev.keliver.material.KeliverMaterial" })
  }

  @Test fun theProductConfigsKeepNoHiddenOverload() {
    // A hidden overload keeps only an entry its lock already has (an older guest sends its tag). The
    // product's locks started fresh (MS4-06), so its configs list only what Compose shows.
    val classpath = System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")
    val sources = System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")
    val api = ApiReader(classpath.split(File.pathSeparator).map(::File))
    val src = SourceReader(sources.split(File.pathSeparator).map(::File))
    fun hidden(config: Config) = config.declarations.filter { api.find(it).also(src::complete).isHidden }
    assertEquals(emptyList(), hidden(loadConfig(File("core.json"))) + hidden(loadConfig(File("coil.json"))))
    // The prototype keeps Gate A's two.
    assertEquals(2, hidden(loadConfig(File("gatea.json"))).size)
  }

  private fun lockOf(path: String) = lockJson.decodeFromString(Lock.serializer(), File(path).readText())
  private fun rootLockOf(path: String) = lockJson.decodeFromString(RootLock.serializer(), File(path).readText())

  @Test fun theProductLocksStartedFreshAtLevelOne() {
    // Q2 (MS4-06): the product's locks start at level 1, with none of the prototype's history: every
    // tag from 1 with no gap (no reserved or retired tag), every first entry at level 1.
    for (group in listOf("core", "coil")) {
      val lock = lockOf("src/test/resources/product-level-1/$group.lock.json")
      assertEquals(group, lock.group)
      assertEquals(1, lock.level, group)
      for ((kind, entries, next) in listOf(
        Triple("widget", lock.widgets, lock.nextWidgetTag),
        Triple("modifier", lock.modifiers, lock.nextModifierTag),
        Triple("factory", lock.factories, lock.nextFactoryTag),
      )) {
        assertEquals((1 until next).toList(), entries.values.map { it.tag }.sorted(), "$group's $kind tags")
        for ((key, entry) in entries) {
          assertEquals(1, entry.since, key)
          assertTrue(entry.wire.orEmpty().values.all { it == 1 }, key)
        }
      }
    }
    // Core keeps no Coil entry (coil is its own group, MS4-03), and no hidden overload's entry.
    val core = lockOf("src/test/resources/product-level-1/core.lock.json")
    assertTrue(core.widgets.keys.none { it.startsWith("coil3.") }, "core has no AsyncImage")
    assertEquals(1, core.widgets.keys.count { it.startsWith("androidx.compose.material3/Text(") })
    assertEquals(1, core.modifiers.keys.count { it.startsWith("androidx.compose.foundation/clickable(") })
    val root = rootLockOf("src/test/resources/product-level-1/root.lock.json")
    assertEquals(1, root.level)
    assertEquals(
      mapOf("dev.keliver.compose.coil.KeliverComposeCoil" to RootLockEntry(17, 1), "dev.keliver.compose.core.KeliverComposeCore" to RootLockEntry(10, 1)),
      root.dependencies,
    )
  }

  @Test fun theProductLocksOnlyAppendToLevelOne() {
    // From MS4-06 on, the product's locks only append (Q2: frozen at the v0.5.0 tag). The level-1
    // locks are kept as fixtures, as Gate A's is for the prototype: a generation that changes one of
    // their entries fails here even if the checked-in lock was deleted and regenerated. Resetting a
    // product lock before the freeze means replacing these fixtures, in the open.
    for (group in listOf("core", "coil")) {
      val levelOne = lockOf("src/test/resources/product-level-1/$group.lock.json")
      val now = lockOf("$group.lock.json")
      assertEquals(emptyList(), checkAppendOnly(levelOne, now), group)
    }
    assertEquals(emptyList(), checkRootAppendOnly(rootLockOf("src/test/resources/product-level-1/root.lock.json"), rootLockOf("root.lock.json")))
  }

  @Test fun theRootListsTheProductGroupsAndNoLegacySchema() {
    val root = loadRootConfig(File("root.json"))
    val (path, text) = rootSchema(root)
    assertEquals("dev/keliver/compose/root/Generated.kt", path)
    assertTrue("  members = [],\n" in text, text)
    assertTrue("    Dependency(10, dev.keliver.compose.core.KeliverComposeCore::class),\n" in text, text)
    // MS4-03: coil at design §14's tag 17 (Q1: optional; a host may leave it out).
    assertTrue("    Dependency(17, dev.keliver.compose.coil.KeliverComposeCoil::class),\n" in text, text)
    assertTrue("public interface KeliverCompose\n" in text, text)
    for (legacy in listOf("dev.keliver.material.", "dev.keliver.layout.", "dev.keliver.lazylayout.")) {
      assertFalse(legacy in text, "the generated root depends on $legacy")
    }
    assertEquals(text, File("../keliver-compose-root-schema/src/main/kotlin/$path").readText(), "the checked-in root is stale")
  }

  @Test fun aRootRefusesADependencyTagUsedTwice() {
    val file = File.createTempFile("root", ".json").apply {
      deleteOnExit()
      writeText(
        """{"group": "root", "schemaPackage": "p", "schemaName": "R", "dependencies": [""" +
          """{"tag": 10, "type": "a.A"}, {"tag": 10, "type": "b.B"}]}""",
      )
    }
    val message = runCatching { loadRootConfig(file) }.exceptionOrNull()?.message.orEmpty()
    assertTrue("used twice" in message, message)
  }

  @Test fun theCheckedInRootAndItsLockAreUpToDate() {
    // What :keliver-compose-generator:checkComposeApiRoot runs.
    val run = runRoot(File("root.json"), File("root.lock.json"), File("../keliver-compose-root-schema/src/main/kotlin"), check = true)
    assertEquals(emptyList(), run.problems)
    assertTrue("level 1, KeliverComposeCore@10, KeliverComposeCoil@17" in run.summary, run.summary)
  }

  private val scratch = mutableListOf<File>()

  @AfterTest fun deleteScratch() {
    scratch.forEach { it.deleteRecursively() }
  }

  /** A scratch root: its config, lock and schema output, in a fresh directory. */
  private inner class ScratchRoot {
    val dir: File = kotlin.io.path.createTempDirectory("root").toFile().also { scratch += it }
    val config = File(dir, "root.json")
    val lock = File(dir, "root.lock.json")
    val out = File(dir, "out")
    val schema = File(out, "p/Generated.kt")

    fun config(vararg groups: Pair<Int, String>) = config.writeText(
      """{"group": "root", "schemaPackage": "p", "schemaName": "R", "dependencies": [""" +
        groups.joinToString { (tag, type) -> """{"tag": $tag, "type": "$type"}""" } + "]}",
    )

    fun run(check: Boolean) = runRoot(config, lock, out, check)
  }

  @Test fun aChangedRootDependencyTagFailsTheGuard() {
    // Review of #313, L1: the root's keliver-api.xml holds only the schema type, so Redwood's API
    // check cannot see a group's dependency tag. The root's lock records it, append-only.
    val root = ScratchRoot()
    root.config(10 to "a.Core", 17 to "b.Coil")
    assertEquals(emptyList(), root.run(check = false).problems)
    assertEquals(emptyList(), root.run(check = true).problems)
    val lockBefore = root.lock.readText()
    val schemaBefore = root.schema.readText()
    assertTrue("    Dependency(10, a.Core::class),\n" in schemaBefore, schemaBefore)

    root.config(11 to "a.Core", 17 to "b.Coil")
    val checked = root.run(check = true).problems
    assertTrue(checked.any { "group a.Core changed its dependency tag from 10 to 11" in it }, "$checked")
    val generated = root.run(check = false).problems
    assertTrue(generated.any { "changed its dependency tag" in it }, "$generated")
    assertEquals(lockBefore, root.lock.readText(), "a refused generation writes nothing")
    assertEquals(schemaBefore, root.schema.readText(), "a refused generation writes nothing")
  }

  @Test fun aRemovedGroupFailsTheGuardAndANewGroupIsAppended() {
    val root = ScratchRoot()
    root.config(10 to "a.Core")
    root.run(check = false)
    // A new group is appended at the next level; the old one keeps its own.
    root.config(10 to "a.Core", 12 to "c.Icons")
    assertEquals(emptyList(), root.run(check = false).problems)
    val lock = lockJson.decodeFromString(RootLock.serializer(), root.lock.readText())
    assertEquals(2, lock.level)
    assertEquals(mapOf("a.Core" to RootLockEntry(10, 1), "c.Icons" to RootLockEntry(12, 2)), lock.dependencies)
    // Removing one is refused, as is giving a new group a tag that another had.
    root.config(12 to "c.Icons")
    assertTrue(root.run(check = true).problems.any { "group a.Core (dependency tag 10) was removed" in it })
    assertTrue(root.run(check = false).problems.isNotEmpty())
    val reused = checkRootAppendOnly(lock, RootLock("root", 3, mapOf("c.Icons" to RootLockEntry(12, 2), "d.Other" to RootLockEntry(10, 3))))
    assertTrue(reused.any { "takes dependency tag 10, which another group had" in it }, "$reused")
  }

  @Test fun theRootCheckFindsAStaleGeneratedFile() {
    // Review of #313: the root's check skipped the stale-file scan the groups' check has.
    val root = ScratchRoot()
    root.config(10 to "a.Core")
    root.run(check = false)
    val stale = File(root.out, "old/Generated.kt").apply {
      parentFile.mkdirs()
      writeText("// a root schema the root no longer writes\n")
    }
    val keep = File(root.out, "p/Hand.kt").apply { writeText("// not generated\n") }
    assertTrue(root.run(check = true).problems.any { "$stale is stale" in it })
    assertEquals(emptyList(), root.run(check = false).problems)
    assertFalse(stale.exists(), "a generation deletes it")
    assertTrue(keep.exists() && root.schema.exists())
    assertEquals(emptyList(), root.run(check = true).problems)
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

  @Test fun aSuccessorWhoseNewParameterDoesNotTravelSendsTheOldTag() {
    val old = widgetDecl("Text", "text" to false, "maxLines" to true)
    val lock = LockBuilder(Lock("g")).apply { widget(old, wire = listOf("text", "maxLines")) }.build()
    val hidden = old.copy().apply { deprecation = "HIDDEN" }
    val config = Config("g", "p", "S", emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())
    // Compose adds `autoSize`, a type with no codec: it does not travel, so the wire is the old one.
    val autoSize = ParamInfo("autoSize", TypeRef("androidx.compose.foundation.text.TextAutoSize", nullable = true), hasDefault = true, defaultText = "null")
    val current = old.copy(params = old.params + autoSize)
    val plans = plan(config, listOf(hidden, current), LockBuilder(lock)).filterIsInstance<Plan.Generated>()
    val visible = plans.single { !it.hidden }
    val predecessor = plans.single { it.hidden }
    assertTrue(visible.entry.tag != predecessor.entry.tag, "the new overload keeps its own lock entry")
    assertEquals(predecessor, sentAs(plans)[visible], "a guest still sends the old tag, which an old host knows")
    // Once a new parameter travels, the stub sends the new entry.
    val travelling = old.copy(params = old.params + ParamInfo("minLines", TypeRef("kotlin.Int"), hasDefault = true, defaultText = "1"))
    val later = plan(config, listOf(hidden, travelling), LockBuilder(lock)).filterIsInstance<Plan.Generated>()
    assertEquals(emptyMap(), sentAs(later))
  }

  // Gate B-e: receiver specs, generic wire types, animated parameters, extra modifiers.

  @Test fun aScopedWidgetIsNamedAndKeyedForItsReceiver() {
    val plain = widgetDecl("AnimatedVisibility", "visible" to false)
    val column = plain.copy(receiver = TypeRef("androidx.compose.foundation.layout.ColumnScope"))
    val row = plain.copy(receiver = TypeRef("androidx.compose.foundation.layout.RowScope"))
    val lock = LockBuilder(Lock("g")).apply {
      widget(plain)
      widget(column)
      widget(row)
    }.build()
    assertEquals(
      setOf("GenAnimatedVisibility1", "GenColumnScopeAnimatedVisibility1", "GenRowScopeAnimatedVisibility1"),
      lock.widgets.values.map { it.name }.toSet(),
    )
    assertTrue(lock.widgets.keys.any { "/ColumnScope.AnimatedVisibility(" in it }, "the receiver is part of the key")
    assertEquals("ColumnScope", column.widgetReceiver?.simpleName)
    assertNull(plain.widgetReceiver)
    // A modifier's Modifier receiver is not a widget receiver: modifiers keep their names.
    val padding = DeclInfo("x", "androidx.compose.foundation.layout", "padding", null, TypeRef(MODIFIER), emptyList(), TypeRef(MODIFIER), false)
    assertNull(padding.widgetReceiver)
    assertEquals("GenPadding1", LockBuilder(Lock("g")).apply { modifier(padding) }.build().modifiers.values.single().name)
  }

  @Test fun genericTypesTravelAsOneWireClass() {
    val spec = TypeRef("androidx.compose.animation.core.FiniteAnimationSpec", args = listOf(TypeRef("androidx.compose.ui.unit.IntSize")))
    assertEquals("dev.keliver.compose.animation.core.FiniteAnimationSpec<dev.keliver.compose.ui.unit.IntSize>", guestType(spec))
    assertEquals("animationSpec.wire", TypeMap.sendExpr(spec, "animationSpec"))
    assertEquals("dev.keliver.compose.animation.core.AnimationSpecWire", TypeMap.wireType(spec, false))
    assertTrue(TypeMap.isMapped(spec))
    assertFalse(TypeMap.isMapped(spec.copy(args = listOf(TypeRef("com.example.Unknown")))), "an argument that is not sent")
    assertEquals("size", TypeMap.sendExpr(TypeRef("androidx.compose.ui.unit.Dp"), "size"), "a plain value is sent as it is")
  }

  private val alphaDecl = DeclInfo(
    "androidx.compose.ui.draw.alpha(alpha)",
    "androidx.compose.ui.draw",
    "alpha",
    null,
    TypeRef(MODIFIER),
    listOf(ParamInfo("alpha", TypeRef("kotlin.Float"), false)),
    TypeRef(MODIFIER),
    false,
  )

  private fun animationConfig(animated: List<String>, extraModifiers: List<String>) =
    Config("g", "dev.example.schema", "S", emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), animated, extraModifiers)

  @Test fun anAnimatedParameterGetsAPrologueAndItsRendererWrapsIt() {
    val config = animationConfig(listOf(alphaDecl.spec), listOf("Animated"))
    val e = Emitter(config, plan(config, listOf(alphaDecl), LockBuilder(Lock("g"))))
    val guest = e.guest().values.joinToString("\n")
    assertTrue("  val animated_alpha = dev.keliver.compose.internal.animated(\"alpha\", alpha)\n" in guest, guest)
    assertTrue("val alpha = dev.keliver.compose.internal.untag(alpha)" in guest)
    assertTrue("return then(animated_alpha).then(GenAlpha1Impl(alpha = alpha))" in guest, "ExtraAnimated goes just before its element")
    val host = e.host().second
    assertTrue("e.alpha.animated(pending, \"alpha\")" in host)
    assertTrue("m = androidx.compose.runtime.key(pending.map { it.ref.id }) {" in host, "one movable group per element")
    assertTrue("for ((e, pending) in paired)" in host)
    // extraModifiers: a schema member and a no-op Redwood factory hook.
    assertTrue("ExtraAnimated::class" in e.schema().second)
    assertTrue("override fun ExtraAnimated(value: @Composable (Modifier) -> Unit, modifier: ExtraAnimated) {}" in host)
  }

  @Test fun theLockRecordsWhichEntriesAreAnimated() {
    val plain = LockBuilder(Lock("g")).apply { modifier(alphaDecl) }.build()
    assertFalse(plain.modifiers.values.single().animated)
    assertFalse("animated" in lockJson.encodeToString(Lock.serializer(), plain), "written only when true")
    // Becoming animated is recorded without raising the level (it is not a wire change)…
    val animated = LockBuilder(plain).apply { modifier(alphaDecl, animated = true) }.build()
    assertTrue(animated.modifiers.values.single().animated)
    assertEquals(plain.level, animated.level)
    assertTrue("\"animated\": true" in lockJson.encodeToString(Lock.serializer(), animated))
    assertTrue(checkAppendOnly(plain, animated).isEmpty())
    // …and is kept by a generation that no longer lists it, while dropping it is refused.
    assertTrue(LockBuilder(animated).apply { modifier(alphaDecl) }.build().modifiers.values.single().animated)
    assertTrue(checkAppendOnly(animated, plain).isNotEmpty())
    // The checked-in lock marks the configured entries.
    val lock = lockJson.decodeFromString(Lock.serializer(), File("gatea.lock.json").readText())
    assertTrue(lock.modifiers.filterKeys { it.startsWith("androidx.compose.ui.draw/alpha(") }.values.single().animated)
    assertFalse(lock.modifiers.filterKeys { it.startsWith("androidx.compose.ui.draw/clip(") }.values.single().animated)
  }

  @Test fun withoutTheAnimatedListNothingIsAnimated() {
    val config = animationConfig(emptyList(), emptyList())
    val e = Emitter(config, plan(config, listOf(alphaDecl), LockBuilder(Lock("g"))))
    val guest = e.guest().values.joinToString("\n")
    assertFalse("animated_" in guest)
    assertTrue("return then(GenAlpha1Impl(alpha = alpha))" in guest)
    val host = e.host().second
    assertTrue("for (e in elements) {\n    m = when (e) {" in host)
    assertFalse("pending" in host)
    assertFalse("ExtraAnimated" in e.schema().second)
  }

  @Test fun findPicksAnOverloadByItsReceiver() {
    val classpath = System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")
    val api = ApiReader(classpath.split(File.pathSeparator).map(::File))
    val params = "(visible,modifier,enter,exit,label,content)"
    // Without `@`, of the three AnimatedVisibility(visible, …) overloads the one with no receiver.
    assertNull(api.find("androidx.compose.animation.AnimatedVisibility$params").receiver)
    assertEquals("ColumnScope", api.find("androidx.compose.animation.AnimatedVisibility@ColumnScope$params").receiver?.simpleName)
    assertEquals("RowScope", api.find("androidx.compose.animation.AnimatedVisibility@RowScope$params").receiver?.simpleName)
    // A modifier's Modifier receiver is kept by the default preference.
    assertEquals(MODIFIER, api.find("androidx.compose.ui.draw.alpha(alpha)").receiver?.fq)
    assertTrue(runCatching { api.find("androidx.compose.animation.AnimatedVisibility@BoxScope$params") }.isFailure)
  }

  @Test fun aLazyRowsHostStateIsHorizontalAndALazyColumnsIsNot() {
    // NF1: the host's lazy state sizes placeholders and probes flings along the list's own axis.
    val classpath = System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")
    val sources = System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")
    val api = ApiReader(classpath.split(File.pathSeparator).map(::File))
    val src = SourceReader(sources.split(File.pathSeparator).map(::File))
    val config = loadConfig(File("gatea.json"))
    fun hostOf(name: String): String {
      val spec = config.declarations.single { it.startsWith("androidx.compose.foundation.lazy.$name(") }
      return Emitter(config, plan(config, listOf(api.find(spec).also(src::complete)), LockBuilder(Lock("g")))).host().second
    }
    val row = hostOf("LazyRow")
    assertTrue("val lazyHost_content = rememberLazyHost(state, vertical = false)\n" in row, row)
    assertTrue("flingBehavior = lazyHost_content.fling," in row, "the generated lazy list passes the host's predicting fling")
    val column = hostOf("LazyColumn")
    assertTrue("val lazyHost_content = rememberLazyHost(state)\n" in column, column)
    assertTrue("flingBehavior = lazyHost_content.fling," in column)
  }

  @Test fun aScrollContainerIsMarkedForThePressSignal() {
    // NF3: a press inside a scroll container waits until it is a tap; the renderers mark the container.
    val classpath = System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")
    val sources = System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")
    val api = ApiReader(classpath.split(File.pathSeparator).map(::File))
    val src = SourceReader(sources.split(File.pathSeparator).map(::File))
    val config = loadConfig(File("gatea.json"))
    fun hostOf(spec: String, configured: Config = config): String =
      Emitter(configured, plan(configured, listOf(api.find(spec).also(src::complete)), LockBuilder(Lock("g")))).host().second
    val lazySpec = config.declarations.single { it.startsWith("androidx.compose.foundation.lazy.LazyColumn(") }
    val scrollSpec = "androidx.compose.foundation.verticalScroll(state,enabled,flingBehavior,reverseScrolling)"
    assertTrue(lazySpec in config.scrollContainers && scrollSpec in config.scrollContainers)
    val lazy = hostOf(lazySpec)
    assertTrue(
      "modifier = incoming.applyGenerated(modifier, this@ComposeUiGenLazyColumn1).pressScrollContainer(state = state, enabled = userScrollEnabled),\n" in lazy,
      lazy,
    )
    val scroll = hostOf(scrollSpec)
    assertTrue(
      "m.verticalScroll(state = state, enabled = enabled, reverseScrolling = reverseScrolling).pressScrollContainer(state = state, enabled = enabled) }" in scroll,
      scroll,
    )
    // Without the config entry, nothing is added.
    val unmarked = Config(
      config.group, config.schemaPackage, config.schemaName, config.dependencies, config.declarations,
      config.errors, config.typeMessages, config.icons, config.extras, config.animated, config.extraModifiers,
    )
    assertFalse("pressScrollContainer" in hostOf(lazySpec, unmarked))
    assertFalse("pressScrollContainer" in hostOf(scrollSpec, unmarked))
  }

  @Test fun aRendererHoldsOneSnapshotStatePerWidget() {
    // NF1: properties, events, the lazy counts and the modifier are plain fields; a setter that
    // changes one bumps the widget's one state, which the renderer reads first.
    val classpath = System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")
    val sources = System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")
    val api = ApiReader(classpath.split(File.pathSeparator).map(::File))
    val src = SourceReader(sources.split(File.pathSeparator).map(::File))
    val config = loadConfig(File("gatea.json"))
    fun hostOf(prefix: String): String {
      val spec = config.declarations.first { it.startsWith(prefix) }
      return Emitter(config, plan(config, listOf(api.find(spec).also(src::complete)), LockBuilder(Lock("g")))).host().second
    }
    val button = hostOf("androidx.compose.material3.Button(")
    assertFalse("mutableStateOf" in button, "no state per property: $button")
    assertEquals(1, Regex("mutableIntStateOf").findAll(button.substringAfter("internal class")).count(), button)
    assertTrue("  private var version by mutableIntStateOf(0)\n" in button, button)
    assertTrue(
      "  override var modifier: KeliverModifier = KeliverModifier\n    set(value) { if (field != value) { field = value; version++ } }\n" in button,
      button,
    )
    // An event, and a value with a default (starts as "not sent").
    assertTrue("  private var p_onClick: Any? = null\n" in button, button)
    assertTrue("  override fun onClick(onClick: (() -> Unit)?) { if (p_onClick != onClick) { p_onClick = onClick; version++ } }\n" in button, button)
    assertTrue("  private var p_enabled: Any? = null\n" in button, button)
    assertTrue("{ if (p_enabled != enabled) { p_enabled = enabled; version++ } }\n" in button, button)
    // The renderer reads the version before any field.
    assertTrue("  override val value: @Composable (Modifier) -> Unit = { incoming ->\n    version\n" in button, button)
    // A required value starts unset.
    val text = hostOf("androidx.compose.material3.Text(text,modifier,color,autoSize,")
    assertTrue("  private var p_text: Any? = Unset\n" in text, text)
    assertTrue("{ if (p_text != text) { p_text = text; version++ } }\n" in text, text)
    // A lazy list's before and after counts are state of their own, outside `version`: a scroll
    // window shift changes them and must not recompose the renderer. Its viewport callback is a
    // plain field.
    val lazy = hostOf("androidx.compose.foundation.lazy.LazyColumn(")
    assertEquals(3, Regex("mutableIntStateOf").findAll(lazy.substringAfter("internal class")).count(), lazy)
    assertTrue("  private var count_contentBefore by mutableIntStateOf(0)\n" in lazy, lazy)
    assertTrue("  override fun contentBefore(contentBefore: kotlin.Int) { count_contentBefore = contentBefore }\n" in lazy, lazy)
    assertTrue("  private var count_contentAfter by mutableIntStateOf(0)\n" in lazy, lazy)
    assertTrue("  override fun contentAfter(contentAfter: kotlin.Int) { count_contentAfter = contentAfter }\n" in lazy, lazy)
    assertFalse(Regex("p_content(Before|After)").containsMatchIn(lazy), lazy)
    assertTrue("  override fun contentViewport(contentViewport: ((kotlin.Int, kotlin.Int, kotlin.Int) -> Unit)?) { p_contentViewport = contentViewport }\n" in lazy, lazy)
    // The counts are read only inside the list's content lambda, which Compose runs in a derived
    // state: that state, not the renderer, is invalidated when they change.
    assertTrue("content = { lazyWindow(this@ComposeUiGenLazyColumn1.content, count_contentBefore, count_contentAfter, lazyHost_content) },\n" in lazy, lazy)
    assertEquals(1, Regex("count_contentBefore\\b").findAll(lazy.substringAfter("override val value")).count(), lazy)
  }

  @Test fun noRendererReadsAPlainFieldInsideALambda() {
    // NF1: a plain field (`p_`) is seen by Compose only because `version`, read first in the
    // renderer's scope, changes with it. A read inside a lambda in the renderer (a slot, a modifier
    // block, a derived state, an effect) would run outside that scope and miss the change. Across
    // every renderer gate A's config emits, every `p_` read sits in the renderer's own scope: inside
    // its one wrapper (`run {` or `if (required) {`), not inside a further `{`. The viewport callback
    // is the exception: it was a plain field before NF1, called from an effect, never composed.
    val classpath = System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")
    val sources = System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")
    val config = loadConfig(File("gatea.json"))
    val lock = lockJson.decodeFromString(Lock.serializer(), File("gatea.lock.json").readText())
    val host = generate(config, lock, classpath.split(File.pathSeparator).map(::File), sources.split(File.pathSeparator).map(::File))
      .host.values.single()
    val renderers = host.split("\ninternal class ").drop(1).filter { "override val value: @Composable (Modifier) -> Unit = { incoming ->" in it }
    // Every widget class has a renderer (29 today), the lazy lists among them.
    assertEquals(Regex(", GeneratedWidget \\{\n").findAll(host).count(), renderers.size)
    assertTrue(renderers.size >= 29 && renderers.any { it.startsWith("ComposeUiGenLazyRow1 ") }, "every renderer: ${renderers.size}")
    for (r in renderers) {
      val name = r.substringBefore(" ")
      assertEquals(emptyList(), plainFieldReadsInsideALambda(r), name)
      assertTrue("Unit = { incoming ->\n    version\n" in r, "$name reads its version first")
    }
    // The check itself finds a read inside a lambda, and allows the viewport callback.
    val bad = "override val value: @Composable (Modifier) -> Unit = { incoming ->\n    version\n    run {\n" +
      "      val a = p_a\n      Box(content = { Text(p_b) })\n      Report { p_contentViewport?.invoke() }\n    }\n  }\n}\n"
    assertEquals(listOf("p_b"), plainFieldReadsInsideALambda(bad))
  }

  /** The `p_` names read inside a `{` nested in a renderer's wrapper, the viewport callback aside. */
  private fun plainFieldReadsInsideALambda(renderer: String): List<String> {
    val marker = "Unit = { incoming ->"
    val body = renderer.substring(renderer.indexOf(marker) + marker.length)
    val found = mutableListOf<String>()
    var depth = 0 // 0: the value lambda's own scope; 1: inside its wrapper
    var i = 0
    while (i < body.length && depth >= 0) {
      when {
        body[i] == '{' -> depth++
        body[i] == '}' -> depth--
        body.startsWith("p_", i) && (i == 0 || !(body[i - 1].isLetterOrDigit() || body[i - 1] == '_')) -> {
          val name = Regex("p_\\w+").find(body, i)!!.value
          if (depth > 1 && !name.endsWith("Viewport")) found += name
          i += name.length
          continue
        }
      }
      i++
    }
    return found
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

  @Test fun coilIsItsOwnGroupOnCoresRuntime() {
    // MS4-03 (Q1: coil is optional): AsyncImage left core for its own group, which uses core's runtime.
    val core = loadConfig(File("core.json"))
    val coil = loadConfig(File("coil.json"))
    assertTrue(core.sharedRuntime)
    assertEquals("dev.keliver.compose.core", coil.base)
    assertTrue(coil.declarations.isNotEmpty() && coil.declarations.all { it.startsWith("coil3.") }, "${coil.declarations}")
    assertTrue(core.declarations.none { it.startsWith("coil3.") }, "core declares no Coil")
    // Coil's lock is its own; core's has no AsyncImage entry since its fresh lock (MS4-06).
    val coilLock = lockJson.decodeFromString(Lock.serializer(), File("coil.lock.json").readText())
    assertEquals("coil", coilLock.group)
    assertTrue(coilLock.widgets.keys.all { it.startsWith("coil3.compose/AsyncImage(") }, "${coilLock.widgets.keys}")
    assertEquals(emptyMap(), coilLock.factories, "coil reads core's factory tags and owns none")
    val coreLock = lockJson.decodeFromString(Lock.serializer(), File("core.lock.json").readText())
    assertTrue(coreLock.widgets.keys.none { it.startsWith("coil3.") })
  }

  @Test fun aSharedRuntimesApplierIsPublicForGeneratedCodeOnly() {
    val shared = Config("g", "dev.example.schema", "S", emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), sharedRuntime = true)
    val host = Emitter(shared, plan(shared, listOf(alphaDecl), LockBuilder(Lock("g")))).host().second
    assertTrue("@dev.keliver.RedwoodCodegenApi\npublic fun Modifier.applyGenerated(" in host, host)
    assertTrue("@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, dev.keliver.RedwoodCodegenApi::class)\n" in host)
    // Without it (the prototype), the applier stays internal and the header as it was.
    val own = animationConfig(emptyList(), emptyList())
    val ownHost = Emitter(own, plan(own, listOf(alphaDecl), LockBuilder(Lock("g")))).host().second
    assertTrue("@Composable\ninternal fun Modifier.applyGenerated(" in ownHost)
    assertTrue("@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)\n" in ownHost)
  }

  @Test fun aBasedGroupsRenderersUseItsBasesRuntime() {
    val classpath = System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")
    val sources = System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")
    val api = ApiReader(classpath.split(File.pathSeparator).map(::File))
    val src = SourceReader(sources.split(File.pathSeparator).map(::File))
    val config = loadConfig(File("coil.json"))
    val decls = config.declarations.map { api.find(it).also(src::complete) }
    val host = Emitter(config, plan(config, decls, LockBuilder(Lock("coil")))).host().second
    assertTrue("import dev.keliver.compose.core.composeui.*\n" in host, "core's applier, converters and widget runtime")
    assertTrue("@file:OptIn(dev.keliver.RedwoodCodegenApi::class)\n" in host, "no material3 opt-in: coil's module does not depend on it")
    assertTrue("modifier = incoming.applyGenerated(modifier, this@ComposeUiGenAsyncImage1)," in host)
    assertTrue("public class ComposeUiKeliverComposeCoilWidgetFactory : KeliverComposeCoilWidgetFactory<" in host)
    // None of the runtime of its own: those would shadow core's.
    for (own in listOf("fun Modifier.applyGenerated(", "fun Modifier.applyScoped(", "fun iconByName(", "fun defaultsColor(")) {
      assertFalse(own in host, "a based group emits no $own")
    }
    // The checked-in coil host is this generation's.
    val checkedIn = File("../keliver-compose-coil-composeui/src/commonMain/kotlin/dev/keliver/compose/coil/composeui/Generated.kt").readText()
    assertTrue(checkedIn.startsWith(header("coil")))
  }

  @Test fun aBasedGroupHasWidgetsOnly() {
    fun configWith(extra: String): Result<Config> {
      val file = File.createTempFile("based", ".json").apply {
        deleteOnExit()
        writeText("""{"group": "b", "schemaPackage": "p", "schemaName": "B", "base": "dev.keliver.compose.core", $extra}""")
      }
      return runCatching { loadConfig(file) }
    }
    assertTrue(configWith(""""declarations": []""").isSuccess)
    for (own in listOf(""""icons": ["Filled.Info"]""", """"extras": ["Shimmer"]""", """"extraModifiers": ["Press"]""", """"sharedRuntime": true""")) {
      val failure = configWith(own).exceptionOrNull()?.message.orEmpty()
      assertTrue("a group with a base" in failure, "$own: $failure")
    }
    // A modifier needs the base to dispatch to this group: refused when the group is generated.
    val classpath = System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")
    val sources = System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")
    val based = Config("b", "p", "B", emptyList(), listOf(alphaDecl.spec), emptyList(), emptyMap(), emptyList(), emptyList(), base = "dev.keliver.compose.core")
    val failure = runCatching {
      generate(based, Lock("b"), classpath.split(File.pathSeparator).map(::File), sources.split(File.pathSeparator).map(::File))
    }.exceptionOrNull()?.message.orEmpty()
    assertTrue("has widgets only; it declares modifiers" in failure, failure)
  }
}
