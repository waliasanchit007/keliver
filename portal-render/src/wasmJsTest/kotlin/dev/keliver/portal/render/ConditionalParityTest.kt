package dev.keliver.portal.render

import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.portal.Bind
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.Expansion
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.expandForPreview
import dev.keliver.portal.render.fixture.PARITY_CONDITIONALS_BODY_JSON
import dev.keliver.portal.render.fixture.ParityToggle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * K3 — conditional parity: a component whose values and children are chosen
 * by its parameters builds the same schema value tree compiled (device) and
 * interpreted (preview expansion decides each condition, RenderNode renders),
 * from the tree portal-ingest locks to ParityConditionals.kt on the JVM —
 * for literal arguments, and for a screen binding decided by the mocks.
 */
class ConditionalParityTest {
  @AfterTest fun reset() {
    PreviewBindings.mocks.clear()
  }

  private val registry = MapComponentRegistry(
    listOf(
      ComponentSpec(
        name = "ParityToggle",
        props = listOf(
          PropSpec("label", PropKind.Text, "Label"),
          PropSpec("selected", PropKind.Bool, "Selected"),
          PropSpec("subtitle", PropKind.Text, "Subtitle"),
          PropSpec("raised", PropKind.Bool, "Raised"),
          PropSpec("note", PropKind.Text, "Note"),
        ),
        events = emptyList(),
        paramTypes = mapOf("label" to "String", "selected" to "Boolean", "subtitle" to "String", "raised" to "Boolean", "note" to "String?"),
        defaults = mapOf("raised" to false, "note" to null),
        body = deserializeTree(PARITY_CONDITIONALS_BODY_JSON),
        transparent = true,
      ),
    ),
  )

  private fun interpreted(vararg args: Pair<String, Any?>): WidgetNode =
    (expandForPreview(WidgetNode("ParityToggle", mapOf(*args)), registry) as Expansion.Transparent).tree

  @Test
  fun eachLiteralArgumentPicksTheBranchTheDeviceDoes() = runTest {
    for (selected in listOf(true, false)) {
      for ((subtitle, raised, note) in listOf(Triple("", false, null), Triple("sub", true, "n"))) {
        val compiled = KeliverMaterialTester {
          setContentAndSnapshot { ParityToggle(label = "L", selected = selected, subtitle = subtitle, raised = raised, note = note) }
        }
        val tree = interpreted("label" to "L", "selected" to selected, "subtitle" to subtitle, "raised" to raised, "note" to note)
        val preview = KeliverMaterialTester { setContentAndSnapshot { RenderNode(tree) } }
        assertEquals(compiled, preview, "selected=$selected subtitle='$subtitle' raised=$raised note=$note")
      }
    }
  }

  @Test
  fun aScreenBindingArgumentIsDecidedByItsMock() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { ParityToggle(label = "L", selected = false, subtitle = "") }
    }
    PreviewBindings.mocks["isOn"] = "false"
    PreviewBindings.mocks["sub"] = ""
    val tree = interpreted("label" to "L", "selected" to Bind("isOn"), "subtitle" to Bind("sub"))
    val preview = KeliverMaterialTester { setContentAndSnapshot { RenderNode(tree) } }
    assertEquals(compiled, preview)
  }
}
