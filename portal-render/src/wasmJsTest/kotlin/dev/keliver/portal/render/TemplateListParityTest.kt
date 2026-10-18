package dev.keliver.portal.render

import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.portal.Bind
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.Expansion
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.Template
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.expandForPreview
import dev.keliver.portal.render.fixture.PARITY_TEMPLATES_BODY_JSON
import dev.keliver.portal.render.fixture.ParityScore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * K4 — template and list parity: a component whose text is a template over
 * its parameters and a design token, and whose lists hold parameters and
 * tokens, builds the same schema value tree compiled (device) and
 * interpreted (preview expansion substitutes the arguments, RenderNode
 * evaluates), from the tree portal-ingest locks to ParityTemplates.kt on the
 * JVM — for literal arguments, a template argument and a mocked binding.
 */
class TemplateListParityTest {
  @AfterTest fun reset() {
    PreviewBindings.mocks.clear()
  }

  private val registry = MapComponentRegistry(
    listOf(
      ComponentSpec(
        name = "ParityScore",
        props = listOf(
          PropSpec("label", PropKind.Text, "Label"),
          PropSpec("count", PropKind.Int, "Count"),
          PropSpec("start", PropKind.Color, "Start"),
        ),
        events = emptyList(),
        paramTypes = mapOf("label" to "String", "count" to "Int", "start" to "Int"),
        body = deserializeTree(PARITY_TEMPLATES_BODY_JSON),
        transparent = true,
      ),
    ),
  )

  private fun interpreted(vararg args: Pair<String, Any?>): WidgetNode =
    (expandForPreview(WidgetNode("ParityScore", mapOf(*args)), registry) as Expansion.Transparent).tree

  @Test
  fun literalArgumentsRenderTheDevicesTextAndLists() = runTest {
    for ((label, count, start) in listOf(Triple("Ada", 3, -1), Triple("", 0, -14737633))) {
      val compiled = KeliverMaterialTester { setContentAndSnapshot { ParityScore(label = label, count = count, start = start) } }
      val tree = interpreted("label" to label, "count" to count, "start" to start)
      val preview = KeliverMaterialTester { setContentAndSnapshot { RenderNode(tree) } }
      assertEquals(compiled, preview, "label='$label' count=$count start=$start")
    }
  }

  @Test
  fun aTemplateArgumentAndAMockedBindingEvaluateLikeTheDevice() = runTest {
    val compiled = KeliverMaterialTester { setContentAndSnapshot { ParityScore(label = "Hi Ada", count = 7, start = -1) } }
    PreviewBindings.mocks["name"] = "Ada"
    PreviewBindings.mocks["n"] = "7"
    val tree = interpreted("label" to Template(listOf("Hi ", Bind("name"))), "count" to Bind("n"), "start" to -1)
    val preview = KeliverMaterialTester { setContentAndSnapshot { RenderNode(tree) } }
    assertEquals(compiled, preview)
  }
}
