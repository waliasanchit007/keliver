package dev.keliver.portal.render

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.material.compose.Slider
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.Theme
import dev.keliver.material.testing.KeliverMaterialTester
import dev.keliver.portal.COMPONENT_FORWARDS_MODIFIER_PROP
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.Expansion
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.expandForPreview
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * K5: the canvas builds the widget tree a phone builds — a screen's Theme, the
 * app's own screen wrapper (its Theme around every screen), and modifiers a
 * caller passes to a component that forwards them to its root.
 */
class CanvasFidelityTest {
  @AfterTest fun reset() {
    PreviewBindings.mocks.clear()
    componentPreviewInScope = null
    componentPreview = null
  }

  @Test
  fun aThemeAroundASliderMatchesTheCompiledScreen() = runTest {
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot {
        Theme(primaryArgb = -50621, secondaryArgb = -50621, surfaceVariantArgb = -1) {
          Slider(position = 0.25f)
        }
      }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          WidgetNode(
            "Theme",
            mapOf("primaryArgb" to -50621, "secondaryArgb" to -50621, "surfaceVariantArgb" to -1),
            listOf(WidgetNode("Slider", mapOf("position" to 0.25))),
          ),
        )
      }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun theAppsScreenWrapperIsAroundTheScreenAsOnADevice() = runTest {
    val entry = object : AppPreviewEntry {
      override val screens: Map<String, ScreenPreview> = emptyMap()
      override val screenWrapper = PreviewWrapper { content -> Theme(primaryArgb = -14737633, onPrimaryArgb = -1) { content() } }
    }
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot {
        Theme(primaryArgb = -14737633, onPrimaryArgb = -1) { StyledText(text = "Funds") }
      }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        AppScreenFrame(entry) { RenderNode(WidgetNode("StyledText", mapOf("text" to "Funds"))) }
      }
    }
    assertEquals(compiled, interpreted)
  }

  @Test
  fun withoutAWrapperTheScreenIsBare() = runTest {
    val compiled = KeliverMaterialTester { setContentAndSnapshot { StyledText(text = "x") } }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot { AppScreenFrame(null) { RenderNode(WidgetNode("StyledText", mapOf("text" to "x"))) } }
    }
    assertEquals(compiled, interpreted)
  }

  /** The device side: a component that forwards `modifier` to its root. */
  @Composable
  private fun InfoCard(title: String, modifier: Modifier = Modifier) {
    Column(width = Constraint.Fill, modifier = modifier) { StyledText(text = title) }
  }

  @Test
  fun aCallersModifierOnAForwardingComponentAppliesInItsParentScope() = runTest {
    val registry = MapComponentRegistry(
      listOf(
        ComponentSpec(
          name = "InfoCard",
          props = listOf(PropSpec("title", PropKind.Text, "Title")),
          events = emptyList(),
          paramTypes = mapOf("title" to "String", "modifier" to "Modifier"),
          body = WidgetNode(
            "Column",
            mapOf("width" to 1, COMPONENT_FORWARDS_MODIFIER_PROP to true),
            listOf(WidgetNode("StyledText", mapOf("text" to Bind("title")))),
          ),
          transparent = true,
        ),
      ),
    )
    componentPreviewInScope = { node, scope ->
      RenderNode((expandForPreview(node, registry) as Expansion.Transparent).tree, scope)
    }
    val compiled = KeliverMaterialTester {
      setContentAndSnapshot {
        Row(width = Constraint.Fill) {
          InfoCard(title = "Left", modifier = Modifier.flex(1.0))
          StyledText(text = "right")
        }
      }
    }
    val interpreted = KeliverMaterialTester {
      setContentAndSnapshot {
        RenderNode(
          WidgetNode(
            "Row", mapOf("width" to 1),
            listOf(
              WidgetNode("InfoCard", mapOf("title" to "Left", "mod.Flex.value" to 1.0)),
              WidgetNode("StyledText", mapOf("text" to "right")),
            ),
          ),
        )
      }
    }
    assertEquals(compiled, interpreted)
  }
}
