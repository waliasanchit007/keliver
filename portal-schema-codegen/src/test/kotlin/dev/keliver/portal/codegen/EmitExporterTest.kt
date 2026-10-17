package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class EmitExporterTest {
  private val text = WidgetPlan.Include(
    name = "StyledText", composePackage = "dev.keliver.material.compose", category = "Material",
    props = listOf(
      MappedProp("text", MappedKind.TEXT, required = true, defaultExpr = null),
      MappedProp("fontSize", MappedKind.INT, required = false, defaultExpr = "14"),
    ),
    skippedProps = emptyList(), events = emptyList(), hasChildren = false,
  )
  private val box = WidgetPlan.Include(
    name = "StyledBox", composePackage = "dev.keliver.material.compose", category = "Material",
    props = listOf(
      MappedProp("width", MappedKind.CONSTRAINT, required = false, defaultExpr = "Constraint.Wrap"),
      MappedProp("gradientStops", MappedKind.FLOAT_LIST, required = false, defaultExpr = "emptyList()"),
    ),
    skippedProps = emptyList(), events = emptyList(), hasChildren = true,
  )

  @Test fun emitsExporterWithPerWidgetBranches() {
    val src = emitExporter(listOf(text, box))
    assertContains(src, "package dev.keliver.portal")
    assertContains(src, "fun exportKotlin(tree: WidgetNode, functionName: String = \"ExportedScreen\", components: ComponentRegistry = EmptyComponentRegistry): String")
    assertContains(src, "\"StyledText\" to \"dev.keliver.material.compose.StyledText\"")
    assertContains(src, "sb.append(\"\${indent}  text = \${fmtString(node.props[\"text\"] ?: \"\")},\\n\")")
    assertContains(src, "if (\"fontSize\" in node.props)")
    assertContains(src, "\"StyledBox\" -> {")
    assertContains(src, "fmtConstraint")
    assertContains(src, "fmtFloatList")
    // C1: unknown type routes to the generic component-instance emitter.
    assertContains(src, "else -> emitComponentInstance(sb, node, indent, components)")
    assertContains(src, "private fun emitComponentInstance(")
    assertContains(src, "components.spec(node.type)?.slots?.singleOrNull() != null")
  }

  @Test fun emitsModifierChainExport() {
    val padding = ModPlan("Padding", "padding", "dev.keliver.material.compose",
      listOf(MappedProp("allDp", MappedKind.INT, required = true, defaultExpr = null)))
    val src = emitExporter(listOf(text), listOf(padding))
    assertContains(src, "\"Padding\" to \"dev.keliver.material.compose.padding\"")
    assertContains(src, "private fun modifierExpr(node: WidgetNode, parent: String?): String?")
    assertContains(src, "\"Padding\" -> parts += \"padding(\" + fmtInt(node.props[\"mod.Padding.allDp\"]) + \")\"")
    assertContains(src, "modifierExpr(node, parent)?.let { sb.append(\"\${indent}  modifier = \$it,\\n\") }")
    // N4: author order; N5: imports from what the body writes.
    assertContains(src, "for (name in modifierNames(node)) {")
    assertContains(src, "requiredImports(body.toString())")
  }

  @Test fun scopedModifiersExportWithoutAnImportButWithTheirArgTypes() {
    val flex = ModPlan("Flex", "flex", "dev.keliver.layout.compose",
      listOf(MappedProp("value", MappedKind.DOUBLE, required = true, defaultExpr = null)),
      scopes = listOf("RowScope", "ColumnScope"))
    val margin = ModPlan("Margin", "margin", "dev.keliver.layout.compose",
      listOf(MappedProp("margin", MappedKind.MARGIN, required = true, defaultExpr = null)),
      scopes = listOf("RowScope", "ColumnScope", "BoxScope"))
    val width = ModPlan("Width", "width", "dev.keliver.layout.compose",
      listOf(MappedProp("width", MappedKind.DP, required = true, defaultExpr = null)),
      scopes = listOf("RowScope"))
    val src = emitExporter(listOf(text), listOf(flex, margin, width))
    val imports = src.substringAfter("public val modifierImport").substringBefore("\n)\n")
    assertFalse("flex" in imports, imports)
    assertContains(src, "\"Margin\" to listOf(\"dev.keliver.ui.Margin\", \"dev.keliver.ui.dp\"),")
    assertContains(src, "\"Width\" to listOf(\"dev.keliver.ui.Dp\"),")
    // B1: a scoped modifier is written only under a parent that is one of its scopes.
    assertContains(src, "\"Flex\" -> if ((parent == \"Row\" || parent == \"Column\")) parts += \"flex(\" + fmtDouble(node.props[\"mod.Flex.value\"]) + \")\"")
    assertContains(src, "\"Margin\" -> if ((parent == \"Row\" || parent == \"Column\" || parent == \"Box\")) parts += \"margin(\" + fmtMargin(node, \"mod.Margin.margin\") + \")\"")
    assertContains(src, "\"Width\" -> if ((parent == \"Row\")) parts += \"width(\" + fmtDp(node.props[\"mod.Width.width\"]) + \")\"")
  }

  @Test fun marginWidgetPropIsPresentWhenAnySideIs() {
    val row = box.copy(name = "Row", props = listOf(MappedProp("margin", MappedKind.MARGIN, required = false, defaultExpr = "Margin.Zero")))
    val src = emitExporter(listOf(row))
    assertContains(src, "if (\"margin.start\" in node.props || \"margin.end\" in node.props || \"margin.top\" in node.props || \"margin.bottom\" in node.props) sb.append(\"\${indent}  margin = \${fmtMargin(node, \"margin\")},\\n\")")
    assertContains(src, "\"Row\" to listOf(\"dev.keliver.ui.Margin\", \"dev.keliver.ui.dp\")")
  }
}
