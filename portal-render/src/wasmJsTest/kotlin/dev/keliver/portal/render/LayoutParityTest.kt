package dev.keliver.portal.render

import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.layout.compose.RowScope
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.render.fixture.PARITY_LAYOUT_BODY_JSON
import dev.keliver.portal.render.fixture.ParityLayout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * K1 — layout grammar parity: Margin widget props and SCOPED layout modifiers
 * (flex/grow/shrink/margin/width/height/size/alignment) must build the same
 * schema value tree compiled (device) and interpreted (preview). The tree is
 * the golden portal-ingest locks to ParityLayout.kt on JVM.
 */
class LayoutParityTest {
  @AfterTest fun reset() {
    PreviewBindings.mocks.clear()
    componentPreviewInScope = null
    componentPreview = null
  }

  @Test
  fun scopedModifiersAndMarginsMatchTheCompiledFixture() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { ParityLayout(left = "Users", right = "Funds") }
    }
    PreviewBindings.mocks["left"] = "Users"
    PreviewBindings.mocks["right"] = "Funds"
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot { RenderNode(deserializeTree(PARITY_LAYOUT_BODY_JSON)) }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun aScopedModifierOutsideItsScopeIsNotApplied() = runTest {
    // StyledBox's content lambda has no receiver: flex means nothing there.
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot { StyledBox { StyledText(text = "x") } }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          WidgetNode("StyledBox", emptyMap(), listOf(WidgetNode("StyledText", mapOf("text" to "x", "mod.Flex.value" to 1.0)))),
        )
      }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun conditionAndComponentExpansionReceiveTheParentScope() = runTest {
    val seen = mutableListOf<Any?>()
    componentPreviewInScope = { node, scope ->
      seen += scope
      RenderNode(WidgetNode("StyledText", mapOf("text" to node.type, "mod.Flex.value" to 2.0)), scope)
    }
    PreviewBindings.mocks["show"] = "true"
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          WidgetNode(
            "Row", mapOf("width" to 1),
            listOf(
              WidgetNode("Condition", mapOf("field" to "show"), listOf(
                WidgetNode("StyledText", mapOf("text" to Bind("show"), "mod.Flex.value" to 1.0)),
              )),
              WidgetNode("SomeComponent"),
            ),
          ),
        )
      }
    }
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot {
        Row(width = Constraint.Fill) {
          StyledText(text = "true", modifier = dev.keliver.Modifier.flex(1.0))
          StyledText(text = "SomeComponent", modifier = dev.keliver.Modifier.flex(2.0))
        }
      }
    }
    assertEquals(compiled, interpreted)
    assertTrue(seen.isNotEmpty() && seen.all { it is RowScope }, "component expansion got scope $seen")
  }

  @Test
  fun marginWidgetPropReachesTheComposable() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot {
        Column(margin = dev.keliver.ui.Margin(start = dev.keliver.ui.Dp(3.0), bottom = dev.keliver.ui.Dp(5.0))) {}
      }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(WidgetNode("Column", mapOf("margin.start" to 3.0, "margin.bottom" to 5.0)))
      }
    }
    assertEquals(compiled, interpreted)
  }
}
