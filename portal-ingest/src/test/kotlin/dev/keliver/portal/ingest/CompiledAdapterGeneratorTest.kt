package dev.keliver.portal.ingest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compiled component preview: the adapter generator's per-parameter rules, and
 * the checked-in fixture adapters portal-render's / portal-editor's wasm tests
 * compile and run (a staleness guard, like the parity goldens).
 */
class CompiledAdapterGeneratorTest {
  private val fixtureRoot = File(
    requireNotNull(System.getProperty("dev.keliver.portal.parityFixtures")) {
      "portal parity fixture root was not configured"
    },
  )
  private val fixtureDir = File(fixtureRoot, "kotlin/dev/keliver/portal/render/fixture")

  private fun gen(vararg sources: String, sameModule: Boolean = false) = CompiledAdapterGenerator.generate(
    sources.mapIndexed { i, s -> CompiledAdapterGenerator.SourceFile("C$i.kt", s) },
    packageName = "app.preview",
    valName = "AppCompiled",
    sameModule = sameModule,
  )

  private val header = """
    package app.ui
    import androidx.compose.runtime.Composable
    import dev.keliver.Modifier
  """.trimIndent()

  @Test fun fixtureAdaptersAreUpToDate() {
    val files = listOf(
      "ParityOpaqueBadge", "ParityOpaqueFrame", "ParityOpaqueInitials", "ParityOpaqueGap", "ParityLeaf",
      "ParityOpaqueKeywords", "ParityNamedCompiledComponent", "ParityOpaqueMeter", "ParityOpaqueAnnounce",
    )
    val names = files.map { if (it == "ParityNamedCompiledComponent") "CompiledComponent" else it }
    val out = CompiledAdapterGenerator.generate(
      files.map { CompiledAdapterGenerator.SourceFile("$it.kt", File(fixtureDir, "$it.kt").readText()) },
      packageName = "dev.keliver.portal.render.fixture",
      valName = "ParityCompiledComponents",
      sameModule = true,
      regenerateHint = "Regenerate: KELIVER_UPDATE_COMPILED_ADAPTERS=1 ./gradlew :portal-ingest:test --tests '*CompiledAdapterGeneratorTest*'",
    )
    assertEquals(names.sorted(), out.adapted)
    assertEquals(emptyMap(), out.refused)
    assertEquals(null, CompiledAdapterGenerator.syntaxErrors(out.source))
    val file = File(fixtureDir, "ParityCompiledComponents.kt")
    if (System.getenv("KELIVER_UPDATE_COMPILED_ADAPTERS") == "1") file.writeText(out.source)
    assertEquals(file.takeIf { it.exists() }?.readText(), out.source, "stale ${file.path}; see its regenerate line")
  }

  @Test fun theFixtureComponentsAreOpaqueExceptTheTransparentControl() {
    for (n in listOf(
      "ParityOpaqueBadge", "ParityOpaqueFrame", "ParityOpaqueInitials", "ParityOpaqueGap",
      "ParityOpaqueKeywords", "ParityNamedCompiledComponent", "ParityOpaqueMeter", "ParityOpaqueAnnounce",
    )) {
      val spec = Recognizer.recognizeComponent("$n.kt", File(fixtureDir, "$n.kt").readText())!!.spec
      assertFalse(spec.transparent, "$n must be opaque to exercise compiled preview")
    }
    assertTrue(Recognizer.recognizeComponent("ParityLeaf.kt", File(fixtureDir, "ParityLeaf.kt").readText())!!.spec.transparent)
  }

  @Test fun scalarsDefaultsEventsAndModifier() {
    val out = gen(
      """
      $header
      @Composable
      fun InfoCard(
        title: String,
        count: Int = 3,
        ratio: Double = 0.5,
        note: String? = null,
        on: Boolean = true,
        label: String = "Hi ${'$'}x",
        modifier: Modifier = Modifier,
        onTap: () -> Unit = {},
        onPick: ((String) -> Unit)? = null,
      ) {}
      """.trimIndent(),
    )
    assertEquals(listOf("InfoCard"), out.adapted, out.refused.toString())
    val s = out.source
    assertTrue("import app.ui.InfoCard as InfoCard__c0" in s, s)
    assertTrue("val p0 = call.string(\"title\")" in s, s)
    assertTrue("call.int(\"count\", 3)" in s, s)
    assertTrue("call.double(\"ratio\", 0.5)" in s, s)
    assertTrue("call.stringOrNull(\"note\", null)" in s, s)
    assertTrue("call.bool(\"on\", true)" in s, s)
    // A string template default is not a literal: an instance must set it.
    assertTrue("call.requireSet(\"label\"" in s, s)
    assertTrue("modifier = call.modifier" in s, s)
    assertTrue("call.event(\"onTap\")" in s, s)
    assertTrue("call.event1OrNull<Any?>(\"onPick\")" in s, s)
    assertTrue("onPick = if (" in s, s)
    assertTrue("call.noContent()" in s, s)
    assertTrue("public val AppCompiled" in s, s)
  }

  @Test fun aRequiredSlotRendersTheInstanceChildren() {
    val out = gen("$header\n@Composable\nfun Frame(title: String, content: @Composable () -> Unit) {}")
    assertTrue("call.content()" in out.source, out.source)
    assertFalse("call.noContent()" in out.source, out.source)
  }

  @Test fun unsupportedParametersWithADefaultAreLeftOutAndWithoutOneRefuse() {
    val out = gen(
      "$header\nenum class Kind { A }\n@Composable\nfun Ok(kind: Kind = Kind.A, size: Float = 1f, title: String) {}",
      "$header\nenum class Kind { A }\n@Composable\nfun Bad(kind: Kind) {}",
    )
    assertEquals(listOf("Ok"), out.adapted)
    assertFalse("kind =" in out.source.substringAfter("\"Ok\""), out.source)
    assertTrue(out.refused.getValue("Bad").contains("`kind: Kind`"), out.refused.toString())
    assertTrue(" *  - Bad: " in out.source, out.source)
  }

  @Test fun visibilityReceiversAndSlotsRefuse() {
    val out = gen(
      "$header\n@Composable\nprivate fun Hidden(a: String) {}",
      "$header\n@Composable\ninternal fun Mine(a: String) {}",
      "$header\nimport dev.keliver.layout.compose.RowScope\n@Composable\nfun RowScope.Cell(a: String) {}",
      "$header\n@Composable\nfun Two(a: @Composable () -> Unit, b: @Composable () -> Unit) {}",
      "$header\n@Composable\nfun Opt(a: (@Composable () -> Unit)? = null) {}",
    )
    assertEquals(listOf("Opt"), out.adapted) // its optional slot is left out (compiled default)
    assertEquals(setOf("Cell", "Hidden", "Mine", "Two"), out.refused.keys)
    assertTrue(gen("$header\n@Composable\ninternal fun Mine(a: String) {}", sameModule = true).adapted == listOf("Mine"))
  }

  // ── review M1: every emitted file must be valid Kotlin (the relay rewrites it before EVERY build) ──

  @Test fun keywordNamesAndPackageSegmentsAreBacktickQuoted() {
    // Before the fix: `in = p0` / `package app.in.ui` were emitted bare and did not parse.
    val out = gen(
      """
      package app.`in`.ui
      import androidx.compose.runtime.Composable
      @Composable
      fun Kw(`in`: String, `object`: Int = 2, `fun`: () -> Unit = {}) {}
      """.trimIndent(),
    )
    assertEquals(listOf("Kw"), out.adapted, out.refused.toString())
    assertTrue("import app.`in`.ui.Kw as Kw__c0" in out.source, out.source)
    assertTrue("`in` = p" in out.source && "`object` = p" in out.source && "`fun` = p" in out.source, out.source)
    assertEquals(null, CompiledAdapterGenerator.syntaxErrors(out.source), out.source)
  }

  @Test fun aFullyQualifiedComposableSlotIsTheContentSlot() {
    // Before the fix: matched by the text "@Composable", so this slot was wired as an EVENT.
    val out = gen("$header\n@Composable\nfun Frame(title: String, content: @androidx.compose.runtime.Composable () -> Unit) {}")
    assertTrue("call.content()" in out.source, out.source)
    assertFalse("call.event(\"content\")" in out.source, out.source)
  }

  @Test fun aComponentNamedLikeAGeneratedSymbolIsAliasedAndTheFileStillParses() {
    // Before the fix: `import app.ui.CompiledComponent` next to `import dev.keliver.portal.render.CompiledComponent`.
    val out = gen("$header\n@Composable\nfun CompiledComponent(label: String) {}", "$header\n@Composable\nfun mapOf(label: String) {}")
    assertEquals(listOf("CompiledComponent", "mapOf"), out.adapted, out.refused.toString())
    assertTrue("import app.ui.CompiledComponent as CompiledComponent__c0" in out.source, out.source)
    assertFalse("import dev.keliver.portal.render.CompiledComponent" in out.source, out.source)
    assertTrue("dev.keliver.portal.render.CompiledComponent { call ->" in out.source, out.source)
    assertTrue("kotlin.collections.mapOf(" in out.source, out.source)
    assertEquals(null, CompiledAdapterGenerator.syntaxErrors(out.source), out.source)
  }

  @Test fun twoComponentsWithOneNameAreRefusedWithTheReason() {
    val out = CompiledAdapterGenerator.generate(
      listOf(
        CompiledAdapterGenerator.SourceFile("a/Row2.kt", "package a\nimport androidx.compose.runtime.Composable\n@Composable\nfun Pill(t: String) {}"),
        CompiledAdapterGenerator.SourceFile("b/Row2.kt", "package b\nimport androidx.compose.runtime.Composable\n@Composable\nfun Pill(t: String) {}"),
      ),
      packageName = "app.preview",
      valName = "AppCompiled",
    )
    assertEquals(emptyList(), out.adapted)
    assertTrue("must be unique" in out.refused.getValue("Pill"), out.refused.toString())
  }

  @Test fun aSignatureThatCannotBeRenderedIsRefusedNotEmitted() {
    // A root-package component whose name the generated file itself uses.
    val out = CompiledAdapterGenerator.generate(
      listOf(CompiledAdapterGenerator.SourceFile("call.kt", "import androidx.compose.runtime.Composable\n@Composable\nfun call(t: String) {}")),
      packageName = "",
      valName = "AppCompiled",
    )
    assertEquals(emptyList(), out.adapted)
    assertTrue("shadow" in out.refused.getValue("call"), out.refused.toString())
    assertEquals(null, CompiledAdapterGenerator.syntaxErrors(out.source), out.source)
  }

  // ── review m2 ──

  @Test fun aModifierDefaultOtherThanTheEmptyModifierIsRefused() {
    // Before the fix: emitted `modifier = call.modifier`, silently dropping the padding a device applies.
    val out = gen("$header\nimport dev.keliver.material.compose.padding\n@Composable\nfun Padded(t: String, modifier: Modifier = Modifier.padding(8)) {}")
    assertEquals(emptyList(), out.adapted)
    assertTrue("Modifier.padding(8)" in out.refused.getValue("Padded"), out.refused.toString())
    assertEquals(listOf("Plain"), gen("$header\n@Composable\nfun Plain(t: String, modifier: Modifier = Modifier) {}").adapted)
  }

  // ── review round 2: signatures that PARSE but would not COMPILE are refused ──

  @Test fun optInAndErrorLevelDeprecatedComponentsAreRefused() {
    // Before the fix both were adapted, and the emitted calls do not compile
    // (an opt-in marker demands @OptIn at the call; level = ERROR forbids the call).
    val out = gen(
      "$header\n@RequiresOptIn(level = RequiresOptIn.Level.ERROR)\nannotation class Experimental\n@Experimental\n@Composable\nfun Exp(t: String) {}",
      "$header\n@Deprecated(\"gone\", level = DeprecationLevel.ERROR)\n@Composable\nfun Old(t: String) {}",
      "$header\n@Suppress(\"unused\")\n@androidx.compose.runtime.Composable\nfun Fine(t: String) {}",
    )
    assertEquals(listOf("Fine"), out.adapted, out.refused.toString())
    assertTrue("@Experimental" in out.refused.getValue("Exp"), out.refused.toString())
    assertTrue("@Deprecated" in out.refused.getValue("Old"), out.refused.toString())
  }

  @Test fun contextParametersAndAnnotatedParametersAreRefused() {
    val out = gen(
      "$header\nclass Theme\n@Composable\ncontext(t: Theme)\nfun Ctx(a: String) {}",
      "$header\n@Composable\nfun Ann(@Suppress(\"x\") a: String) {}",
    )
    assertEquals(emptyList(), out.adapted, out.source)
    assertTrue("context parameters" in out.refused.getValue("Ctx"), out.refused.toString())
    assertTrue("annotations" in out.refused.getValue("Ann"), out.refused.toString())
  }

  @Test fun aComponentSharingItsNameWithATypeInItsPackageIsRefused() {
    val out = gen("$header\nclass Pill(val t: String)", "$header\n@Composable\nfun Pill(t: String) {}")
    assertEquals(emptyList(), out.adapted)
    assertTrue("class, object or typealias" in out.refused.getValue("Pill"), out.refused.toString())
  }

  /** Round-2 nit 1: a root-package component named like an alias would be called in place of the aliased one. */
  @Test fun aNameThatLooksLikeAnImportAliasIsRefused() {
    // Before the fix both were adapted and both calls resolved to `Foo__c0`.
    val out = CompiledAdapterGenerator.generate(
      listOf(
        CompiledAdapterGenerator.SourceFile("Foo.kt", "package app.ui\nimport androidx.compose.runtime.Composable\n@Composable\nfun Foo(t: String) {}"),
        CompiledAdapterGenerator.SourceFile("Root.kt", "import androidx.compose.runtime.Composable\n@Composable\nfun Foo__c0(t: String) {}"),
      ),
      packageName = "",
      valName = "AppCompiled",
    )
    assertEquals(listOf("Foo"), out.adapted, out.refused.toString())
    assertTrue("import alias" in out.refused.getValue("Foo__c0"), out.refused.toString())
  }

  /** The per-adapter validity refusal path: an adapter the check rejects is refused, the others are emitted. */
  @Test fun anAdapterThatFailsTheValidityCheckIsRefusedAndTheRestEmitted() {
    val out = CompiledAdapterGenerator.generateWith(
      listOf(
        CompiledAdapterGenerator.SourceFile("Good.kt", "$header\n@Composable\nfun Good(t: String) {}"),
        CompiledAdapterGenerator.SourceFile("Bad.kt", "$header\n@Composable\nfun Bad(t: String) {}"),
      ),
      packageName = "app.preview",
      valName = "AppCompiled",
      tokens = TokenTable.EMPTY,
      sameModule = false,
      regenerateHint = "test",
      adapterCheck = { text -> if ("\"Bad\"" in text) "simulated error" else CompiledAdapterGenerator.syntaxErrors(text) },
    )
    assertEquals(listOf("Good"), out.adapted)
    assertTrue("would not be valid Kotlin (simulated error)" in out.refused.getValue("Bad"), out.refused.toString())
    assertFalse("Bad(" in out.source.substringAfter("Refused"), out.source)
    assertEquals(null, CompiledAdapterGenerator.syntaxErrors(out.source))
  }
}
