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
    val names = listOf("ParityOpaqueBadge", "ParityOpaqueFrame", "ParityOpaqueInitials", "ParityOpaqueGap", "ParityLeaf")
    val out = CompiledAdapterGenerator.generate(
      names.map { CompiledAdapterGenerator.SourceFile("$it.kt", File(fixtureDir, "$it.kt").readText()) },
      packageName = "dev.keliver.portal.render.fixture",
      valName = "ParityCompiledComponents",
      sameModule = true,
      regenerateHint = "Regenerate: KELIVER_UPDATE_COMPILED_ADAPTERS=1 ./gradlew :portal-ingest:test --tests '*CompiledAdapterGeneratorTest*'",
    )
    assertEquals(names.sorted(), out.adapted)
    assertEquals(emptyMap(), out.refused)
    val file = File(fixtureDir, "ParityCompiledComponents.kt")
    if (System.getenv("KELIVER_UPDATE_COMPILED_ADAPTERS") == "1") file.writeText(out.source)
    assertEquals(file.takeIf { it.exists() }?.readText(), out.source, "stale ${file.path}; see its regenerate line")
  }

  @Test fun theFixtureComponentsAreOpaqueExceptTheTransparentControl() {
    for (n in listOf("ParityOpaqueBadge", "ParityOpaqueFrame", "ParityOpaqueInitials", "ParityOpaqueGap")) {
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
    assertTrue("import app.ui.InfoCard" in s, s)
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
}
