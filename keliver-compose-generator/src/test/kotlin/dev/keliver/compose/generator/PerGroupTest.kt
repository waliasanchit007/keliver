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
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MS4-41 (G0): per-group outputs. Each test generates into its own temporary tree (copies of the
 * checked-in configs and locks), through the same entry point the Gradle tasks use ([runGroup]).
 */
class PerGroupTest {
  private val classpath = System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")
  private val sources = System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")
  private val input = System.getProperty("keliver.composeInput") ?: error("the test task passes keliver.composeInput")

  /** One scratch tree: where each group's modules and the shared roots go, and its own lock copies. */
  private class Tree(val dir: File) {
    fun file(path: String) = File(dir, path)

    init {
      for (name in listOf("core", "coil", "gatea")) {
        File(name + ".lock.json").copyTo(file("$name.lock.json"), overwrite = true)
      }
    }

    fun args(group: String, check: Boolean): List<String> {
      val own = when (group) {
        "core" -> listOf("--out-values", file("values").path)
        else -> listOf("--factories-lock", file("core.lock.json").path)
      } + (if (group == "gatea") listOf("--out-editor-render", file("render").path) else emptyList())
      return listOf(
        "--config", File("$group.json").absolutePath,
        "--lock", file("$group.lock.json").path,
        "--out-schema", file("$group/schema").path,
        "--out-guest", file("$group/guest").path,
        "--out-host", file("$group/host").path,
        "--out-editor", file("$group.editor.json").path,
      ) + own + (if (check) listOf("--check") else emptyList())
    }

    /** Every file of the tree, relative path → text. */
    fun snapshot(): Map<String, String> = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText() }.toSortedMap()
  }

  private fun run(tree: Tree, group: String, check: Boolean = false): List<String> =
    runGroup(tree.args(group, check) + listOf("--classpath", classpath, "--sources", sources, "--input", input))

  private val trees = mutableListOf<Tree>()

  private fun newTree() = Tree(Files.createTempDirectory("per-group").toFile()).also { trees += it }

  @AfterTest fun deleteTrees() {
    trees.forEach { it.dir.deleteRecursively() }
    trees.clear()
  }

  @Test fun theProductGroupsGiveTheSameFilesWhicheverRunsFirst() {
    val a = newTree()
    val b = newTree()
    for (g in listOf("core", "coil")) assertEquals(emptyList(), run(a, g))
    for (g in listOf("coil", "core")) assertEquals(emptyList(), run(b, g))
    assertEquals(a.snapshot(), b.snapshot())
    // And they are what is checked in: the values (core's alone) and each group's host renderers.
    assertEquals(
      File("../keliver-compose-values/src/commonMain/kotlin/dev/keliver/compose/material3/GeneratedDefaults.kt").readText(),
      a.file("values/dev/keliver/compose/material3/GeneratedDefaults.kt").readText(),
    )
    assertTrue(a.snapshot().keys.none { it.startsWith("coil/") && it.endsWith("GeneratedDefaults.kt") }, "coil writes no values")
  }

  @Test fun eachGroupsCheckRunsAloneAndSeesOnlyItsOwnStaleFiles() {
    val t = newTree()
    for (g in listOf("core", "coil")) assertEquals(emptyList(), run(t, g))
    // Clean: each group's check passes alone, whichever the other has done.
    assertEquals(emptyList(), run(t, "coil", check = true))
    assertEquals(emptyList(), run(t, "core", check = true))

    // A Generated.kt the coil group no longer writes: coil's check names it; core's does not.
    val coilStale = t.file("coil/guest/dev/keliver/compose/gone/Generated.kt").apply {
      parentFile.mkdirs()
      writeText("// stale")
    }
    assertEquals(listOf("$coilStale is stale"), run(t, "coil", check = true))
    assertEquals(emptyList(), run(t, "core", check = true))
    coilStale.delete()

    // And the other way round, including the values file only core owns.
    val coreStale = t.file("values/dev/keliver/compose/gone/GeneratedDefaults.kt").apply {
      parentFile.mkdirs()
      writeText("// stale")
    }
    assertEquals(listOf("$coreStale is stale"), run(t, "core", check = true))
    assertEquals(emptyList(), run(t, "coil", check = true))
  }

  @Test fun aGroupsGenerationDeletesNoOtherGroupsFile() {
    val t = newTree()
    for (g in listOf("core", "coil")) assertEquals(emptyList(), run(t, g))
    val values = t.file("values/dev/keliver/compose/material3/GeneratedDefaults.kt")
    val coreText = values.readText()
    // A stale file of coil's own is deleted by coil's run; core's files (values included) stay.
    val coilStale = t.file("coil/host/dev/keliver/compose/gone/Generated.kt").apply {
      parentFile.mkdirs()
      writeText("// stale")
    }
    assertEquals(emptyList(), run(t, "coil"))
    assertFalse(coilStale.exists())
    assertEquals(coreText, values.readText())
    // A stale `GeneratedDefaults.kt` is not coil's to judge or delete: coil writes no values.
    val strayDefaults = t.file("coil/guest/dev/keliver/compose/gone/GeneratedDefaults.kt").apply {
      parentFile.mkdirs()
      writeText("// not coil's")
    }
    assertEquals(emptyList(), run(t, "coil"))
    assertTrue(strayDefaults.exists())
  }

  @Test fun theEditorInterpreterIsOneFilePerGroupAndAGeneratedDispatcher() {
    val t = newTree()
    assertEquals(emptyList(), run(t, "core"))
    assertEquals(emptyList(), run(t, "gatea"))
    val render = "render/${EDITOR_RENDER_PACKAGE.replace('.', '/')}"
    val own = t.file("$render/${editorRenderFile("gatea")}")
    assertEquals("GeneratedEditorRender_Gatea.kt", own.name)
    val text = own.readText()
    assertTrue("internal fun renderGeneratedGatea(type: String" in text, text.take(400))
    assertTrue("internal fun applyGeneratedGatea(m: Modifier" in text)
    assertFalse("internal fun renderGenerated(" in text, "the group's file names the group; the dispatcher has the plain names")

    // The dispatcher is not the group's: the group's run and check leave it alone.
    val dispatcher = t.file("$render/$EDITOR_DISPATCHER").apply {
      parentFile.mkdirs()
      writeText("// the dispatcher")
    }
    assertEquals(emptyList(), run(t, "gatea", check = true))
    assertEquals(emptyList(), run(t, "gatea"))
    assertEquals("// the dispatcher", dispatcher.readText())

    val (path, generated) = editorDispatcher(listOf("gatea", "core"), EDITOR_RENDER_PACKAGE)
    assertEquals("${EDITOR_RENDER_PACKAGE.replace('.', '/')}/Generated.kt", path)
    assertTrue("renderGeneratedGatea(type, a, modifier, slots) ||\n  renderGeneratedCore(type, a, modifier, slots)" in generated, generated)
    assertTrue("applyGeneratedGatea(m, key, a, scope) ?:\n  applyGeneratedCore(m, key, a, scope)" in generated, generated)
  }

  @Test fun anotherGroupsFileInTheSharedEditorRenderRootIsNeitherTouchedNorReportedByGatea() {
    val t = newTree()
    assertEquals(emptyList(), run(t, "core"))
    val render = "render/${EDITOR_RENDER_PACKAGE.replace('.', '/')}"
    val other = t.file("$render/${editorRenderFile("core")}").apply {
      parentFile.mkdirs()
      writeText("// core's interpreter")
    }
    assertEquals(emptyList(), run(t, "gatea"))
    assertEquals("// core's interpreter", other.readText())
    assertEquals(emptyList(), run(t, "gatea", check = true))
    assertEquals("// core's interpreter", other.readText())
    assertTrue(t.file("$render/${editorRenderFile("gatea")}").exists())
  }

  @Test fun theDispatcherCheckReportsAnInterpreterNoListedGroupWrites() {
    val t = newTree()
    val render = t.file("render")
    val dir = File(render, EDITOR_RENDER_PACKAGE.replace('.', '/')).apply { mkdirs() }
    val (path, text) = editorDispatcher(listOf("gatea"), EDITOR_RENDER_PACKAGE)
    val dispatcher = File(render, path).apply { writeText(text) }
    File(dir, editorRenderFile("gatea")).writeText("// gatea")
    assertEquals(emptyList(), editorDispatcherProblems(listOf("gatea"), render, dispatcher, text))
    val leftover = File(dir, editorRenderFile("gone")).apply { writeText("// left behind") }
    assertEquals(listOf("$leftover is stale (no listed group writes it)"), editorDispatcherProblems(listOf("gatea"), render, dispatcher, text))
    // Listing that group makes the file its own.
    val (_, both) = editorDispatcher(listOf("gatea", "gone"), EDITOR_RENDER_PACKAGE)
    dispatcher.writeText(both)
    assertEquals(emptyList(), editorDispatcherProblems(listOf("gatea", "gone"), render, dispatcher, both))
  }

  @Test fun aKeyTwoGroupsBothAnswerIsAnErrorNotAFirstWins() {
    val t = newTree()
    val dir = File(t.file("render"), EDITOR_RENDER_PACKAGE.replace('.', '/')).apply { mkdirs() }
    File(dir, editorRenderFile("gatea")).writeText("  when (type) {\n    \"a.B#1\" -> {\n    }\n    \"a.C#2\" -> {\n    }\n  }\n")
    File(dir, editorRenderFile("core")).writeText("  when (type) {\n    \"a.C#2\" -> {\n    }\n    \"x.Y#1\" -> {\n    }\n  }\n")
    assertEquals(listOf("a.C#2: gatea, core"), editorRenderDuplicateKeys(listOf("gatea", "core"), dir))
    assertEquals(emptyList(), editorRenderDuplicateKeys(listOf("gatea"), dir))
    // The checked-in interpreter has none of its own.
    val checkedIn = File("../portal-compose-render/src/commonMain/kotlin/dev/keliver/portal/compose/render")
    assertTrue(File(checkedIn, editorRenderFile("gatea")).exists())
    assertEquals(emptyList(), editorRenderDuplicateKeys(listOf("gatea"), checkedIn))
  }
}
