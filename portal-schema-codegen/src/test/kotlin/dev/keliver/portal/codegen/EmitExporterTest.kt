package dev.keliver.portal.codegen

import kotlin.test.Test
import kotlin.test.assertContains

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
    assertContains(src, "fun exportKotlin(tree: WidgetNode, functionName: String = \"ExportedScreen\"): String")
    assertContains(src, "\"StyledText\" to \"dev.keliver.material.compose.StyledText\"")
    assertContains(src, "sb.append(\"\${indent}  text = \${fmtString(node.props[\"text\"] ?: \"\")},\\n\")")
    assertContains(src, "if (\"fontSize\" in node.props)")
    assertContains(src, "\"StyledBox\" -> {")
    assertContains(src, "fmtConstraint")
    assertContains(src, "fmtFloatList")
    assertContains(src, "// unknown widget:")
  }

  @Test fun emitsModifierChainExport() {
    val padding = ModPlan("Padding", "padding", "dev.keliver.material.compose",
      listOf(MappedProp("allDp", MappedKind.INT, required = true, defaultExpr = null)))
    val src = emitExporter(listOf(text), listOf(padding))
    assertContains(src, "\"Padding\" to \"dev.keliver.material.compose.padding\"")
    assertContains(src, "private fun modifierExpr(node: WidgetNode): String?")
    assertContains(src, "if (\"mod.Padding.allDp\" in node.props) parts += \"padding(\" + fmtInt(node.props[\"mod.Padding.allDp\"]) + \")\"")
    assertContains(src, "modifierExpr(node)?.let { sb.append(\"\${indent}  modifier = \$it,\\n\") }")
    assertContains(src, "collectModifierNames")
  }
}
