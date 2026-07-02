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
}
