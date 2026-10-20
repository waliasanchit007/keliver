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
import dev.keliver.portal.render.fixture.PARITY_COMPOUND_BODY_JSON
import dev.keliver.portal.render.fixture.ParityCompound
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * K6 — equality, compound and derived-local parity: a component whose values
 * and children are chosen by `==`/`!=` terms, `&&`/`||` joins and a derived
 * local (`val active = enabled && !loading`) builds the same schema value tree
 * compiled (device) and interpreted (preview expansion decides each term,
 * RenderNode renders), from the tree portal-ingest locks to ParityCompound.kt
 * — for every combination of literal arguments, and for screen bindings
 * decided by their mocks (an Int field's mock compared as an Int).
 */
class CompoundParityTest {
  @AfterTest fun reset() {
    PreviewBindings.mocks.clear()
  }

  private val registry = MapComponentRegistry(
    listOf(
      ComponentSpec(
        name = "ParityCompound",
        props = listOf(
          PropSpec("label", PropKind.Text, "Label"),
          PropSpec("enabled", PropKind.Bool, "Enabled"),
          PropSpec("loading", PropKind.Bool, "Loading"),
          PropSpec("code", PropKind.Text, "Code"),
          PropSpec("tier", PropKind.Int, "Tier"),
          PropSpec("round", PropKind.Bool, "Round"),
        ),
        events = emptyList(),
        paramTypes = mapOf("label" to "String", "enabled" to "Boolean", "loading" to "Boolean", "code" to "String", "tier" to "Int", "round" to "Boolean"),
        defaults = mapOf("tier" to 0, "round" to true),
        body = deserializeTree(PARITY_COMPOUND_BODY_JSON),
        transparent = true,
      ),
    ),
  )

  private fun interpreted(vararg args: Pair<String, Any?>): WidgetNode =
    (expandForPreview(WidgetNode("ParityCompound", mapOf(*args)), registry) as Expansion.Transparent).tree

  @Test
  fun everyLiteralArgumentPicksTheBranchTheDeviceDoes() = runTest {
    for (enabled in listOf(true, false)) for (loading in listOf(true, false)) {
      for ((code, tier, round) in listOf(Triple("XY", 0, true), Triple("BD", -1, false), Triple("XY", 3, true), Triple("", -1, true))) {
        for (label in listOf("L", "")) {
          val compiled = KeliverMaterialTester {
            setContentAndSnapshot { ParityCompound(label = label, enabled = enabled, loading = loading, code = code, tier = tier, round = round) }
          }
          val tree = interpreted("label" to label, "enabled" to enabled, "loading" to loading, "code" to code, "tier" to tier, "round" to round)
          val preview = KeliverMaterialTester { setContentAndSnapshot { RenderNode(tree) } }
          assertEquals(compiled, preview, "label='$label' enabled=$enabled loading=$loading code=$code tier=$tier round=$round")
        }
      }
    }
  }

  @Test
  fun screenBindingArgumentsAreDecidedByTheirMocks() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { ParityCompound(label = "L", enabled = true, loading = false, code = "XY", tier = 3, round = true) }
    }
    PreviewBindings.mocks["ok"] = "true"
    PreviewBindings.mocks["busy"] = "false"
    PreviewBindings.mocks["c"] = "XY"
    PreviewBindings.mocks["t"] = "3"
    val tree = interpreted("label" to "L", "enabled" to Bind("ok"), "loading" to Bind("busy"), "code" to Bind("c"), "tier" to Bind("t"))
    val preview = KeliverMaterialTester { setContentAndSnapshot { RenderNode(tree) } }
    assertEquals(compiled, preview)
  }
}
