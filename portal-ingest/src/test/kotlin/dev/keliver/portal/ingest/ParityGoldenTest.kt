package dev.keliver.portal.ingest

import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.serializeTree
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * K1b JVM half of the cross-target parity bridge.
 *
 * The same Kotlin fixture sources compile as the "device" path in portal-render's
 * Wasm tests. Here PSI recognition must produce the checked-in trees consumed by
 * those tests. A recognizer drift therefore fails here; a bad golden update then
 * fails compiled-vs-interpreted parity on Wasm.
 */
class ParityGoldenTest {
  private val fixtureRoot = File(
    requireNotNull(System.getProperty("dev.keliver.portal.parityFixtures")) {
      "portal parity fixture root was not configured"
    },
  )

  private fun source(name: String): String =
    File(fixtureRoot, "kotlin/dev/keliver/portal/render/fixture/$name.kt").readText()

  private fun assertGolden(name: String, tree: WidgetNode) {
    val actual = serializeTree(tree.canonicalIds())
    val file = File(fixtureRoot, "goldens/$name.json")
    if (!file.exists()) {
      error("missing parity golden ${file.absolutePath}\n$actual")
    }
    assertEquals(file.readText().trim(), actual, name)
  }

  @Test
  fun recognizedSourcesMatchCrossTargetGoldens() {
    val leaf = Recognizer.recognizeComponent("ParityLeaf.kt", source("ParityLeaf"))!!
    assertTrue(leaf.spec.transparent, leaf.spec.diagnostic)
    assertEquals(listOf("label"), leaf.spec.props.map { it.name })
    assertGolden("parity-leaf-body", leaf.spec.body!!)

    val section = Recognizer.recognizeComponent("ParitySection.kt", source("ParitySection"))!!
    assertTrue(section.spec.transparent, section.spec.diagnostic)
    assertEquals(listOf("content"), section.spec.slots.map { it.name })
    assertGolden("parity-section-body", section.spec.body!!)

    val baseRegistry = MapComponentRegistry(listOf(leaf.spec, section.spec))
    val panel = Recognizer.recognizeComponent(
      "ParityPanel.kt",
      source("ParityPanel"),
      baseRegistry,
    )!!
    assertTrue(panel.spec.transparent, panel.spec.diagnostic)
    assertEquals(setOf("ParityLeaf", "ParitySection"), panel.spec.dependencies)
    assertGolden("parity-panel-body", panel.spec.body!!)

    val registry = MapComponentRegistry(listOf(leaf.spec, section.spec, panel.spec))
    val screen = Recognizer.recognize(
      "IntegratedParityScreen.kt",
      source("IntegratedParityScreen"),
      registry,
    )!!
    assertGolden(
      "integrated-screen",
      UiDocument("parity", screen.root, screen.contract, 0, 0).toWidgetTree(),
    )
  }

  /** K1: Margin values + scoped layout modifiers recognize transparently, scope-resolved. */
  @Test
  fun layoutModifierFixtureMatchesItsGolden() {
    val layout = Recognizer.recognizeComponent("ParityLayout.kt", source("ParityLayout"))!!
    assertTrue(layout.spec.transparent, layout.spec.diagnostic)
    assertEquals(listOf("left", "right"), layout.spec.props.map { it.name })
    assertGolden("parity-layout-body", layout.spec.body!!)
  }

  /** K2: design-token references recognize transparently and carry their resolved values. */
  @Test
  fun designTokenFixtureMatchesItsGolden() {
    val text = source("ParityTokens")
    val tokens = TokenTable.scan(listOf("ParityTokens.kt" to text))
    val card = Recognizer.recognizeComponent("ParityTokens.kt", text, tokens = tokens)!!
    assertTrue(card.spec.transparent, card.spec.diagnostic)
    assertEquals("ParityTokenCard", card.spec.name)
    assertGolden("parity-tokens-body", card.spec.body!!)
  }

  private fun WidgetNode.canonicalIds(): WidgetNode =
    copy(id = 0, children = children.map { it.canonicalIds() })
}
