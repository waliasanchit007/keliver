package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class EmitRenderNodeTest {
  private val text = WidgetPlan.Include(
    name = "StyledText", composePackage = "dev.keliver.material.compose", category = "Material",
    props = listOf(
      MappedProp("text", MappedKind.TEXT, required = true, defaultExpr = null),
      MappedProp("fontSize", MappedKind.INT, required = false, defaultExpr = "14"),
    ),
    skippedProps = emptyList(), events = listOf(EventPlan("onLongPress", 0)), hasChildren = false,
  )
  private val column = WidgetPlan.Include(
    name = "Column", composePackage = "dev.keliver.layout.compose", category = "Layout",
    props = listOf(
      MappedProp("width", MappedKind.CONSTRAINT, required = false, defaultExpr = "Constraint.Wrap"),
      MappedProp("horizontalAlignment", MappedKind.CROSS_AXIS, required = false, defaultExpr = "CrossAxisAlignment.Start"),
    ),
    skippedProps = emptyList(), events = emptyList(), hasChildren = true,
  )
  private val spacer = WidgetPlan.Include(
    name = "Spacer", composePackage = "dev.keliver.layout.compose", category = "Layout",
    props = listOf(MappedProp("height", MappedKind.DP, required = false, defaultExpr = "Dp(0.0)")),
    skippedProps = emptyList(), events = emptyList(), hasChildren = false,
  )

  @Test fun emitsBranchesGettersAndChildren() {
    val src = emitRenderNode(listOf(text, column, spacer))
    assertContains(src, "package dev.keliver.portal.render")
    assertContains(src, "import dev.keliver.material.compose.StyledText")
    assertContains(src, "import dev.keliver.layout.compose.Column")
    assertContains(src, "import dev.keliver.ui.Dp")
    assertContains(src, "@Composable")
    assertContains(src, "fun RenderNode(node: WidgetNode)")
    assertContains(src, "\"StyledText\" -> StyledText(")
    assertContains(src, "text = node.strB(\"text\"),")
    assertContains(src, "fontSize = node.intB(\"fontSize\", 14),")
    assertContains(src, "width = constraintOf(node.intB(\"width\", 0)),")
    assertContains(src, "horizontalAlignment = crossAxisOf(node.intB(\"horizontalAlignment\", 0)),")
    assertContains(src, "height = Dp(node.dblB(\"height\", 0.0)),")
    assertContains(src, ") { node.children.forEach { RenderNode(it) } }")
    assertContains(src, "else -> StyledText(text = \"\\u26a0 unknown widget: \${node.type}\"")
    // P3: nullable events are wired to Action props via the preview sink.
    assertContains(src, "onLongPress = node.actionOf(\"onLongPress\")?.let { n -> { PreviewBindings.fire(n) } },")
  }

  @Test fun emitsModifierChain() {
    val padding = ModPlan("Padding", "padding", "dev.keliver.material.compose",
      listOf(MappedProp("allDp", MappedKind.INT, required = true, defaultExpr = null)))
    val flag = ModPlan("AnimateContentSize", "animateContentSize", "dev.keliver.material.compose", emptyList())
    val src = emitRenderNode(listOf(text), listOf(padding, flag))
    assertContains(src, "import dev.keliver.Modifier")
    assertContains(src, "import dev.keliver.material.compose.padding")
    assertContains(src, "modifier = nodeModifier(node),")
    assertContains(src, "private fun nodeModifier(node: WidgetNode): Modifier {")
    assertContains(src, "if (\"mod.Padding.allDp\" in node.props) m = m.padding(node.intB(\"mod.Padding.allDp\", 0))")
    assertContains(src, "if (node.bool(\"mod.AnimateContentSize\")) m = m.animateContentSize()")
  }
}
