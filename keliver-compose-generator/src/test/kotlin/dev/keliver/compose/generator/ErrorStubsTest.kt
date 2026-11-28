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

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.metadata.ClassKind
import kotlin.metadata.Visibility
import kotlin.metadata.kind
import kotlin.metadata.visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/**
 * MS4-07 (design §11, §19): include lists by package and the error stubs for every public declaration
 * of an included package that is not generated.
 *
 * The compile-fail harness compiles the generator's own error-stub text (every stub of the core group,
 * as `generate` writes it) together with a fixture `Modifier`, and a file of one-line snippets, with
 * the Kotlin compiler embedded in the generator: each snippet must fail with its curated message. The
 * guest modules themselves compile in the build (`compileKotlinJvm/Js/WasmJs` of keliver-compose-core-compose).
 */
class ErrorStubsTest {
  private val classpath = (System.getProperty("keliver.composeApi") ?: error("the test task passes keliver.composeApi")).split(File.pathSeparator).map(::File)
  private val sources = (System.getProperty("keliver.composeSources") ?: error("the test task passes keliver.composeSources")).split(File.pathSeparator).map(::File)
  private val guestRoot = File("../keliver-compose-core-compose/src/commonMain/kotlin")
  private val valuesRoot = File("../keliver-compose-values/src/commonMain/kotlin")

  private val config by lazy { loadConfig(File("core.json")) }
  private val index by lazy { GenericIndex(classpath) }

  /** The core group, generated as `generateComposeApiCore` does (the lock is only read). */
  private val core by lazy {
    val lock = lockJson.decodeFromString(Lock.serializer(), File("core.lock.json").readText())
    generate(config, lock, classpath, sources, guestRoots = listOf(guestRoot), valuesRoots = listOf(valuesRoot))
  }

  private class Snippet(val code: String, val imports: List<String>, val message: String)

  /** Each snippet's compiler errors (one line each), after checking that the generated stubs compile on their own. */
  private fun compileFail(snippets: List<Snippet>): Map<Snippet, List<String>> {
    val dir = Files.createTempDirectory("keliver-stubs").toFile()
    try {
      val files = mutableListOf<File>()
      // Every stub as the generator wrote it, one file per guest package.
      for ((pkg, group) in core.errorStubs.filter { !it.member }.groupBy { it.pkg }) {
        val f = File(dir, "stubs_${pkg.replace('.', '_')}.kt")
        f.writeText("@file:Suppress(\"UNUSED_PARAMETER\")\npackage $pkg\n\n" + group.joinToString("") { it.text })
        files += f
      }
      // The guest types a stub's signature names: the guest's Modifier, and the scopes its receivers use.
      val fixture = File(dir, "fixture.kt")
      val scopes = core.errorStubs.flatMap { Regex("""dev\.keliver\.[\w.]+Scope(?=\.)""").findAll(it.text).map { m -> m.value }.toList() }.toSet()
      fixture.writeText("package dev.keliver.compose.ui\ninterface Modifier { companion object : Modifier }\n")
      for (s in scopes) {
        File(dir, "scope_${s.replace('.', '_')}.kt").also { files += it }.writeText("package ${s.substringBeforeLast('.')}\ninterface ${s.substringAfterLast('.')}\n")
      }
      files += fixture
      val snippetFile = File(dir, "snippets.kt")
      val imports = snippets.flatMap { it.imports }.toSortedSet()
      snippetFile.writeText(
        "@file:Suppress(\"UNUSED_VARIABLE\", \"UNUSED_PARAMETER\")\npackage snippets\n\n" +
          imports.joinToString("\n") { "import $it" } + "\nimport dev.keliver.compose.ui.Modifier\n\n" +
          snippets.joinToString("\n") { "fun s${snippets.indexOf(it)}(m: Modifier) { ${it.code} }" } + "\n",
      )
      files += snippetFile
      val stdlib = kotlin.Unit::class.java.protectionDomain.codeSource.location.path
      val err = ByteArrayOutputStream()
      K2JVMCompiler().exec(
        PrintStream(err), "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", stdlib, "-d", File(dir, "out").path,
        *files.map { it.path }.toTypedArray(),
      )
      val lines = err.toString().lines().filter { ": error: " in it }
      // No error may come from the stubs themselves: they must compile.
      val own = lines.filter { "snippets.kt" !in it }
      assertEquals(emptyList(), own.take(10), "the generated stubs must compile")
      val headerLines = snippetFile.readText().lines().indexOfFirst { it.startsWith("fun s0(") } + 1
      return snippets.associateWith { s ->
        val line = headerLines + snippets.indexOf(s)
        lines.filter { it.contains("snippets.kt:$line:") }
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun assertFailsWith(results: Map<Snippet, List<String>>) {
    for ((s, errors) in results) {
      assertTrue(errors.any { s.message in it }, "`${s.code}` must fail with: ${s.message}\nbut the compiler said:\n" + errors.joinToString("\n").ifEmpty { "(nothing: it compiled)" })
    }
  }

  private val layout = "custom measuring runs per frame on the host. Use Row, Column or Box with weight and align, FlowRow, or an app component."
  private val pointer = "raw pointer handlers run per event on the host. Use Modifier.clickable, combinedClickable, draggable or anchoredDraggable."
  private val gestures = "gesture detectors read raw pointer events on the host. Use Modifier.clickable, combinedClickable, draggable or anchoredDraggable."
  private val android = "and a guest has no Android. Reach the platform through a generated bridge (docs/BRIDGE_CODEGEN_DESIGN.md)."
  private val resources = "and a guest has none."

  @Test fun everyCuratedStubFailsToCompileWithItsMessage() {
    val ui = "dev.keliver.compose.ui"
    val snippets = listOf(
      Snippet("Layout(modifier = null, measurePolicy = 1)", listOf("$ui.layout.Layout"), layout),
      Snippet("SubcomposeLayout(modifier = null, measurePolicy = 1)", listOf("$ui.layout.SubcomposeLayout"), layout),
      Snippet("m.layout(measure = 1)", listOf("$ui.layout.layout"), "Modifier.layout, layoutId and onPlaced belong to custom layouts"),
      Snippet("m.pointerInput(Unit) { }", listOf("$ui.input.pointer.pointerInput"), pointer),
      Snippet("Unit.detectTapGestures(onTap = null)", listOf("dev.keliver.compose.foundation.gestures.detectTapGestures"), gestures),
      Snippet("Unit.detectDragGestures(onDrag = 1)", listOf("dev.keliver.compose.foundation.gestures.detectDragGestures"), gestures),
      Snippet("Unit.detectTransformGestures(onGesture = 1)", listOf("dev.keliver.compose.foundation.gestures.detectTransformGestures"), gestures),
      Snippet("val c = LocalContext", listOf("$ui.platform.LocalContext"), "LocalContext is an Android Context, $android"),
      Snippet("val c = LocalView", listOf("$ui.platform.LocalView"), "LocalView is an Android View, $android"),
      Snippet("val c = LocalConfiguration", listOf("$ui.platform.LocalConfiguration"), "LocalConfiguration is Android's, $android"),
      Snippet("val c = LocalActivity", listOf("dev.keliver.activity.compose.LocalActivity"), "LocalActivity is Android's, $android"),
      Snippet("stringResource(id = 1)", listOf("$ui.res.stringResource"), "stringResource reads Android resources, $resources"),
      Snippet("painterResource(id = 1)", listOf("$ui.res.painterResource"), "painterResource reads Android resources, $resources"),
      Snippet("vectorResource(id = 1)", listOf("$ui.res.vectorResource"), "vectorResource reads Android resources, $resources"),
      Snippet("AndroidView(factory = 1)", listOf("$ui.viewinterop.AndroidView"), "AndroidView embeds a native View, and a guest has no Android. Use an app component"),
      Snippet("BackHandler(true, 1)", listOf("dev.keliver.activity.compose.BackHandler"), "use dev.keliver.compose.BackHandler, which the host routes"),
      Snippet("FlexBox(config = null, content = 1)", listOf("dev.keliver.compose.foundation.layout.FlexBox"), "not yet: FlexBox and Grid are Compose 1.12's experimental API. Keliver generates them in a 0.5.x"),
      Snippet("Grid(config = 1, content = 1)", listOf("dev.keliver.compose.foundation.layout.Grid"), "not yet: FlexBox and Grid are Compose 1.12's experimental API. Keliver generates them in a 0.5.x"),
      Snippet("val s = Style(1, 2)", listOf("dev.keliver.compose.foundation.style.Style"), "not yet: Style is Compose 1.12's experimental API. Keliver generates it in a 0.5.x"),
      Snippet("SelectionContainer(content = 1)", listOf("dev.keliver.compose.foundation.text.selection.SelectionContainer"), "text selection state is the host's"),
      Snippet("val i: IndicationNodeFactory? = null", listOf("dev.keliver.compose.foundation.IndicationNodeFactory"), "a custom Indication runs per frame on the host. Leave it out: the theme's ripple applies."),
    )
    assertFailsWith(compileFail(snippets))
  }

  @Test fun theNeverByDesignAndExperimentalSamplesFailWithACuratedMessage() {
    // MS4-05 (Google's vendored samples) is later; until then, a hand-picked set per class. The full corpus
    // (136 never by design, 53 experimental) is MS4-05's.
    val ui = "dev.keliver.compose.ui"
    val g = "dev.keliver.compose.foundation"
    val never = listOf(
      Snippet("Layout(modifier = null, measurePolicy = 1)", listOf("$ui.layout.Layout"), layout),
      Snippet("SubcomposeLayout(modifier = null, measurePolicy = 1)", listOf("$ui.layout.SubcomposeLayout"), layout),
      Snippet("m.layout(measure = 1)", listOf("$ui.layout.layout"), "belong to custom layouts"),
      Snippet("m.layoutId(layoutId = 1)", listOf("$ui.layout.layoutId"), "belong to custom layouts"),
      Snippet("m.onPlaced(onPlaced = 1)", listOf("$ui.layout.onPlaced"), "belong to custom layouts"),
      Snippet("m.pointerInput(Unit, Unit) { }", listOf("$ui.input.pointer.pointerInput"), pointer),
      Snippet("m.pointerInput(Unit, Unit, Unit) { }", listOf("$ui.input.pointer.pointerInput"), pointer),
      Snippet("Unit.detectTapGestures(onTap = null)", listOf("$g.gestures.detectTapGestures"), gestures),
      Snippet("Unit.detectDragGestures(onDrag = 1)", listOf("$g.gestures.detectDragGestures"), gestures),
      Snippet("Unit.detectHorizontalDragGestures(onHorizontalDrag = 1)", listOf("$g.gestures.detectHorizontalDragGestures"), gestures),
      Snippet("Unit.detectVerticalDragGestures(onVerticalDrag = 1)", listOf("$g.gestures.detectVerticalDragGestures"), gestures),
      Snippet("Unit.detectTransformGestures(onGesture = 1)", listOf("$g.gestures.detectTransformGestures"), gestures),
      Snippet("Unit.awaitEachGesture(block = 1)", listOf("$g.gestures.awaitEachGesture"), gestures),
      Snippet("m.onGloballyPositioned(onGloballyPositioned = 1)", listOf("$ui.layout.onGloballyPositioned"), "reads host measurements synchronously"),
      Snippet("val p: Placeable? = null", listOf("$ui.layout.Placeable"), "custom layouts measure and place per frame on the host"),
      Snippet("val p: ModifierNodeElement<*>? = null", listOf("$ui.node.ModifierNodeElement"), "a Modifier.Node runs inside the host's layout and draw passes"),
      Snippet("val p: PointerInputChange? = null", listOf("$ui.input.pointer.PointerInputChange"), "raw pointer input runs per event on the host"),
    )
    val experimental = listOf(
      Snippet("FlexBox(config = null, content = 1)", listOf("$g.layout.FlexBox"), "not yet: FlexBox and Grid"),
      Snippet("FlexBoxConfig(1, 2)", listOf("$g.layout.FlexBoxConfig"), "not yet: FlexBox and Grid"),
      Snippet("FlexConfig(1, 2, 3)", listOf("$g.layout.FlexConfig"), "not yet: FlexBox and Grid"),
      Snippet("Grid(config = 1, content = 1)", listOf("$g.layout.Grid"), "not yet: FlexBox and Grid"),
      Snippet("val d: FlexDirection? = null", listOf("$g.layout.FlexDirection"), "not yet: FlexBox and Grid"),
      Snippet("val d: FlexWrap? = null", listOf("$g.layout.FlexWrap"), "not yet: FlexBox and Grid"),
      Snippet("val d: FlexBasis? = null", listOf("$g.layout.FlexBasis"), "not yet: FlexBox and Grid"),
      Snippet("val d: GridFlow? = null", listOf("$g.layout.GridFlow"), "not yet: FlexBox and Grid"),
      Snippet("val d: GridTrackSize? = null", listOf("$g.layout.GridTrackSize"), "not yet: FlexBox and Grid"),
      Snippet("val d: FlexBoxScope? = null", listOf("$g.layout.FlexBoxScope"), "not yet: FlexBox and Grid"),
      Snippet("val s = Style(1, 2)", listOf("$g.style.Style"), "not yet: Style"),
      Snippet("val s = Style(1, 2, 3)", listOf("$g.style.Style"), "not yet: Style"),
      Snippet("val s: StyleScope? = null", listOf("$g.style.StyleScope"), "not yet: Style"),
      Snippet("val s: StyleState? = null", listOf("$g.style.StyleState"), "not yet: Style"),
    )
    assertTrue(never.size >= 10 && experimental.size >= 10, "10 or more hand-picked samples per class")
    assertFailsWith(compileFail(never + experimental))
  }

  @Test fun everyStubMessageNamesItsAlternativeOrItsCategory() {
    for (s in core.errorStubs) {
      assertTrue(s.message.startsWith("Keliver: "), "${s.name}: ${s.message}")
      when (s.kind) {
        "never" -> assertTrue(Regex("Use |Compose the generated|a generated bridge|host-owned").containsMatchIn(s.message), "a never-by-design stub names the alternative: ${s.name}: ${s.message}")
        "notYet" -> assertTrue("not yet" in s.message && "0.5.x" in s.message, "${s.name}: ${s.message}")
        "generic" -> assertTrue("is not in the generated API" in s.message, "${s.name}: ${s.message}")
      }
    }
  }

  @Test fun noPublicNameOfAnIncludedPackageIsUnresolved() {
    // The scan: every public name of an included package is declared in its guest package, by a generated
    // stub, a generated or hand-written declaration, a placeholder, or the values module.
    val declared = mutableMapOf<String, MutableSet<String>>()
    for (text in core.guest.values) declaredNames(text, declared)
    for ((pkg, names) in scanDeclaredNames(listOf(guestRoot, valuesRoot), setOf("Generated.kt", GENERATED_DEFAULTS))) declared.getOrPut(pkg) { mutableSetOf() } += names
    for (text in core.values.values) declaredNames(text, declared)
    val missing = mutableListOf<String>()
    for (pkg in config.includePackages) {
      val here = declared[mirrorPackage(pkg)].orEmpty()
      fun check(name: String, what: String) {
        if (name.contains('$') || name.contains('-')) return
        if (name !in here) missing += "$pkg.$name ($what)"
      }
      for (pf in index.packageFunctions[pkg].orEmpty()) {
        if (index.isHidden(pf.facade, pf.fn)) continue
        // An extension on an unmapped receiver named like the standard library's resolves to the standard library's.
        if (pf.fn.name in stdlibNames && (pf.fn.receiverParameterType?.classifier as? kotlin.metadata.KmClassifier.Class)?.name != "androidx/compose/ui/Modifier") continue
        check(pf.fn.name, "function")
      }
      for (p in index.packageProperties[pkg].orEmpty()) check(p.name, "property")
      for ((fq, c) in index.classes) {
        if (c.name.substringBeforeLast('/', "") != pkg.replace('.', '/') || '.' in c.name.substringAfterLast('/')) continue
        if (c.visibility != Visibility.PUBLIC || c.kind == ClassKind.COMPANION_OBJECT || '$' in fq) continue
        val simple = fq.removePrefix("$pkg.")
        if (simple.first().isUpperCase()) check(simple, "class")
      }
    }
    assertEquals(emptyList(), missing.take(50), "public names with no declaration in the guest (${missing.size})")
  }

  @Test fun theStubCountsByClass() {
    val by = core.errorStubs.groupingBy { it.kind }.eachCount()
    println("MS4-07 error stubs: ${stubCounts(core.errorStubs)}")
    assertTrue((by["generic"] ?: 0) > 500, by.toString())
    assertTrue((by["notYet"] ?: 0) > 50, by.toString())
    assertTrue((by["never"] ?: 0) > 20, by.toString())
    assertTrue((by["curated"] ?: 0) >= config.extraStubs.size, by.toString())
  }

  @Test fun aStubOnANameTheGuestHasRequiresItsFirstParameterAndNoStubIsAnotherStubsTwin() {
    // `fadeIn` is the guest's own (values module): a stub that a no-argument call could pick would be ambiguous.
    val fadeIn = core.errorStubs.filter { it.name == "fadeIn" }
    assertTrue(fadeIn.all { Regex("""fun fadeIn\(\w+: Any\?[,)]""").containsMatchIn(it.text) }, fadeIn.joinToString { it.text })
    // Two overloads that differ only in parameter names have one signature: one is written.
    val signatures = core.errorStubs.filter { !it.member }.mapNotNull { it.erased?.let { e -> it.pkg + "|" + e } }
    assertEquals(signatures.size, signatures.toSet().size)
  }

  @Test fun globsMatchByNameAndAMessageNeedsAMatch() {
    val stubs = ErrorStubs(
      Config(
        "g", "p", "N", emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(),
        includePackages = listOf("androidx.compose.foundation.gestures"),
        stubMessages = listOf(StubMessage(listOf("androidx.compose.foundation.gestures.detect*Gestures"), emptyList(), "gestures", "never")),
      ),
      index,
      emptyList(),
      emptyMap(),
      emptyList(),
    ).stubs().filter { !it.member }
    assertEquals("Keliver: gestures", stubs.first { it.name == "detectTapGestures" }.message)
    assertEquals("never", stubs.first { it.name == "detectTapGestures" }.kind)
    assertEquals("generic", stubs.first { it.name == "awaitEachGesture" }.kind, "a name the glob does not match gets the generic message")
    val failure = runCatching { loadConfig(File.createTempFile("cfg", ".json").also { it.writeText("""{"group":"g","schemaPackage":"p","schemaName":"N","stubMessages":[{"message":"x"}]}""") }) }
    assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("needs match or types"))
  }
}
