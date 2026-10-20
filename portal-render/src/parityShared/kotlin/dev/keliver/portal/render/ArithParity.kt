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
import dev.keliver.portal.render.fixture.PARITY_ARITH_BODY_JSON
import dev.keliver.portal.render.fixture.ParityArith
import kotlin.test.assertEquals

/**
 * K7 — arithmetic parity, shared by the js (the device guest's target) and the
 * wasm (the editor's) test source sets: a component whose values are Int
 * expressions and Ints used as Dps builds the same schema value tree compiled
 * (device) and interpreted (preview expansion substitutes the arguments,
 * RenderNode computes each expression with Kotlin's Int operators), from the
 * tree portal-ingest locks to ParityArith.kt. The arguments reach every
 * boundary: Int.MAX_VALUE and Int.MIN_VALUE (overflow wraps), negative
 * operands (truncation toward zero, the dividend's sign for `%`),
 * `Int.MIN_VALUE / -1`.
 */
internal object ArithParity {
  private val registry = MapComponentRegistry(
    listOf(
      ComponentSpec(
        name = "ParityArith",
        props = listOf(PropSpec("size", PropKind.Int, "Size"), PropSpec("delta", PropKind.Int, "Delta"), PropSpec("gap", PropKind.Int, "Gap")),
        events = emptyList(),
        paramTypes = mapOf("size" to "Int", "delta" to "Int", "gap" to "Int"),
        defaults = mapOf("gap" to 8),
        body = deserializeTree(PARITY_ARITH_BODY_JSON),
        transparent = true,
      ),
    ),
  )

  /** (size, delta, gap) — gap ≥ 7 keeps every Dp non-negative (a device rejects a negative one). */
  val cases: List<Triple<Int, Int, Int>> = listOf(
    Triple(48, 4, 8),
    Triple(Int.MAX_VALUE, Int.MIN_VALUE, 7),
    Triple(Int.MIN_VALUE, -1, 8),
    Triple(-7, -9, 100),
    Triple(0, Int.MAX_VALUE, 7),
    Triple(123, -2147483647, 9),
    Triple(-1, 0, 7),
    Triple(65535, 65537, 1000),
  )

  private fun interpreted(vararg args: Pair<String, Any?>): WidgetNode =
    (expandForPreview(WidgetNode("ParityArith", mapOf(*args)), registry) as Expansion.Transparent).tree

  suspend fun everyLiteralArgumentComputesWhatTheDeviceComputes() {
    for ((size, delta, gap) in cases) {
      val compiled = KeliverMaterialTester { setContentAndSnapshot { ParityArith(size = size, delta = delta, gap = gap) } }
      val tree = interpreted("size" to size, "delta" to delta, "gap" to gap)
      val preview = KeliverMaterialTester { setContentAndSnapshot { RenderNode(tree) } }
      assertEquals(compiled, preview, "size=$size delta=$delta gap=$gap")
    }
    // The default `gap`.
    val compiled = KeliverMaterialTester { setContentAndSnapshot { ParityArith(size = 10, delta = 3) } }
    val preview = KeliverMaterialTester { setContentAndSnapshot { RenderNode(interpreted("size" to 10, "delta" to 3)) } }
    assertEquals(compiled, preview, "the default gap")
  }

  suspend fun screenBindingArgumentsAreComputedFromTheirMocks() {
    try {
      val compiled = KeliverMaterialTester { setContentAndSnapshot { ParityArith(size = Int.MAX_VALUE, delta = -5, gap = 9) } }
      PreviewBindings.mocks["s"] = Int.MAX_VALUE.toString()
      PreviewBindings.mocks["d"] = "-5"
      PreviewBindings.mocks["g"] = "9"
      val tree = interpreted("size" to Bind("s"), "delta" to Bind("d"), "gap" to Bind("g"))
      val preview = KeliverMaterialTester { setContentAndSnapshot { RenderNode(tree) } }
      assertEquals(compiled, preview)
    } finally {
      PreviewBindings.mocks.clear()
    }
  }
}
