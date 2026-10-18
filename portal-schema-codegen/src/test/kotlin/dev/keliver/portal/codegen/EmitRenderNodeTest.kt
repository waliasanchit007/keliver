package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    skippedProps = emptyList(), events = emptyList(), hasChildren = true, childScope = "ColumnScope",
  )
  private val card = WidgetPlan.Include(
    name = "Card", composePackage = "dev.keliver.material.compose", category = "Material",
    props = emptyList(), skippedProps = emptyList(), events = emptyList(), hasChildren = true,
  )
  private val spacer = WidgetPlan.Include(
    name = "Spacer", composePackage = "dev.keliver.layout.compose", category = "Layout",
    props = listOf(MappedProp("height", MappedKind.DP, required = false, defaultExpr = "Dp(0.0)")),
    skippedProps = emptyList(), events = emptyList(), hasChildren = false,
  )

  @Test fun emitsBranchesGettersAndChildren() {
    val src = emitRenderNode(listOf(text, column, spacer, card))
    assertContains(src, "package dev.keliver.portal.render")
    assertContains(src, "import dev.keliver.material.compose.StyledText")
    assertContains(src, "import dev.keliver.layout.compose.Column")
    assertContains(src, "import dev.keliver.ui.Dp")
    assertContains(src, "@Composable")
    assertContains(src, "fun RenderNode(node: WidgetNode) {\n  RenderNode(node, null)\n}")
    assertContains(src, "fun RenderNode(node: WidgetNode, scope: Any?)")
    assertContains(src, "\"StyledText\" -> StyledText(")
    assertContains(src, "text = node.strB(\"text\"),")
    assertContains(src, "fontSize = node.intB(\"fontSize\", 14),")
    assertContains(src, "width = constraintOf(node.intB(\"width\", 0)),")
    assertContains(src, "horizontalAlignment = crossAxisOf(node.intB(\"horizontalAlignment\", 0)),")
    assertContains(src, "height = Dp(node.dblB(\"height\", 0.0).coerceAtLeast(0.0)),")
    // K1: a layout widget hands its children its content-lambda receiver; a
    // widget whose content lambda has no receiver hands them none.
    assertContains(src, "\"Column\" -> Column(\n      width = constraintOf(node.intB(\"width\", 0)),\n      horizontalAlignment = crossAxisOf(node.intB(\"horizontalAlignment\", 0)),\n    ) { node.children.forEach { RenderNode(it, this) } }")
    assertContains(src, "\"Card\" -> Card(\n    ) { node.children.forEach { RenderNode(it, null) } }")
    // Logic nodes emit no wrapper on device, so the parent's scope passes through.
    // K3: a Condition renders the branch its condition picks (its else branch included).
    assertContains(src, "\"Condition\" -> node.previewBranch().forEach { RenderNode(it, scope) }")
    assertContains(src, "node.children.forEach { RenderNode(PreviewBindings.mockItemRow(it, itemVar, itemsField, i), scope) }")
    assertContains(src, "RenderNode(PreviewBindings.mockItemRow(it, itemVar, itemsField, i), scope)")
    // C1: unknown type (project component) routes to the settable preview hook,
    // falling back to the unknown-widget marker when unset.
    assertContains(src, "else -> componentPreviewInScope?.invoke(node, scope) ?: componentPreview?.invoke(node)\n      ?: StyledText(text = \"\\u26a0 unknown widget: \${node.type}\"")
    // P3: nullable events are wired to Action props via the preview sink.
    assertContains(src, "onLongPress = node.actionOf(\"onLongPress\")?.let { a -> { PreviewBindings.fire(a) } },")
  }

  @Test fun eventPayloadIsForwardedToThePreviewAction() {
    val input = text.copy(events = listOf(EventPlan("onValueChange", 1, "String")))
    val src = emitRenderNode(listOf(input))
    assertContains(
      src,
      "onValueChange = node.actionOf(\"onValueChange\")?.let { a -> { value -> PreviewBindings.fire(a, value) } },",
    )
  }

  @Test fun emitsModifierChain() {
    val padding = ModPlan("Padding", "padding", "dev.keliver.material.compose",
      listOf(MappedProp("allDp", MappedKind.INT, required = true, defaultExpr = null)))
    val flag = ModPlan("AnimateContentSize", "animateContentSize", "dev.keliver.material.compose", emptyList())
    val src = emitRenderNode(listOf(text), listOf(padding, flag))
    assertContains(src, "import dev.keliver.Modifier")
    assertContains(src, "import dev.keliver.material.compose.padding")
    assertContains(src, "modifier = nodeModifier(node, scope),")
    assertContains(src, "private fun nodeModifier(node: WidgetNode, scope: Any?): Modifier {")
    // N4: applied in the node's (author) key order, one branch per modifier.
    assertContains(src, "for (name in names) {")
    assertContains(src, "\"Padding\" -> m.padding(node.intB(\"mod.Padding.allDp\", 0))")
    assertContains(src, "\"AnimateContentSize\" -> if (node.bool(\"mod.AnimateContentSize\")) m.animateContentSize() else m")
  }

  @Test fun scopedModifiersApplyWithTheParentsReceiver() {
    val padding = ModPlan("Padding", "padding", "dev.keliver.material.compose",
      listOf(MappedProp("allDp", MappedKind.INT, required = true, defaultExpr = null)))
    val flex = ModPlan("Flex", "flex", "dev.keliver.layout.compose",
      listOf(MappedProp("value", MappedKind.DOUBLE, required = true, defaultExpr = null)),
      scopes = listOf("RowScope", "ColumnScope"))
    val margin = ModPlan("Margin", "margin", "dev.keliver.layout.compose",
      listOf(MappedProp("margin", MappedKind.MARGIN, required = true, defaultExpr = null)),
      scopes = listOf("RowScope", "ColumnScope", "BoxScope"))
    val src = emitRenderNode(listOf(text), listOf(padding, flex, margin))
    assertContains(src, "import dev.keliver.layout.compose.RowScope")
    assertContains(src, "import dev.keliver.layout.compose.BoxScope")
    // Scoped extensions are scope members: never imported.
    assertFalse("import dev.keliver.layout.compose.flex" in src)
    assertContains(src, "\"Flex\" -> when (scope) {\n        is ColumnScope -> with(scope) { m.flex(node.dblB(\"mod.Flex.value\", 0.0)) }\n        is RowScope -> with(scope) { m.flex(node.dblB(\"mod.Flex.value\", 0.0)) }\n        else -> m\n      }")
    assertContains(src, "is BoxScope -> with(scope) { m.margin(marginOf(node, \"mod.Margin.margin\")) }")
    assertTrue("is BoxScope -> with(scope) { m.flex(" !in src) // BoxScope has no flex
  }

  @Test fun marginWidgetPropReachesTheComposable() {
    val row = column.copy(name = "Row", props = listOf(MappedProp("margin", MappedKind.MARGIN, required = false, defaultExpr = "Margin.Zero")))
    val src = emitRenderNode(listOf(row))
    assertContains(src, "margin = marginOf(node, \"margin\"),")
  }
}
