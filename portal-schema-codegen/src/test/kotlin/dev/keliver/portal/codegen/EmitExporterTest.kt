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
    assertContains(src, "else -> emitComponentInstance(sb, node, indent, components, parent, imports)")
    assertContains(src, "private fun emitComponentInstance(")
    assertContains(src, "spec?.slots?.singleOrNull() != null")
    // K1: mod.* keys are never named args of a component call.
    assertContains(src, "node.props.entries.filter { !it.key.startsWith(\"mod.\") }")
  }

  @Test fun emitsModifierChainExport() {
    val padding = ModPlan("Padding", "padding", "dev.keliver.material.compose",
      listOf(MappedProp("allDp", MappedKind.INT, required = true, defaultExpr = null)))
    val src = emitExporter(listOf(text), listOf(padding))
    assertContains(src, "\"Padding\" to \"dev.keliver.material.compose.padding\"")
    assertContains(src, "private fun modifierExpr(node: WidgetNode, parent: String?, imports: MutableSet<String>): String?")
    assertContains(src, "modifierExpr(node, parent, imports)?.let { sb.append(\"\${indent}  modifier = \$it,\\n\") }")
    // N4: author order; N5: imports from what the body writes.
    assertContains(src, "for (name in modifierNames(node)) {")
    // S2: imports are collected structurally while emitting.
    assertContains(src, "\"Padding\" -> { parts += \"padding(\" + fmtInt(node.props[\"mod.Padding.allDp\"]) + \")\"; imports += \"dev.keliver.material.compose.padding\"; imports.addTokenImport(node.props[\"mod.Padding.allDp\"], \"Int\") }")
    assertContains(src, "imports.sorted().forEach")
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
    // A scoped modifier records no extension import — only its argument types.
    assertContains(src, "\"Flex\" -> if ((parent == \"Row\" || parent == \"Column\")) { parts += \"flex(\" + fmtDouble(node.props[\"mod.Flex.value\"]) + \")\"; imports.addTokenImport(node.props[\"mod.Flex.value\"], \"Double\") }")
    assertContains(src, "\"Margin\" -> if ((parent == \"Row\" || parent == \"Column\" || parent == \"Box\")) { parts += \"margin(\" + fmtMargin(node, \"mod.Margin.margin\") + \")\"; imports += marginImports(node, \"mod.Margin.margin\") }")
    assertContains(src, "\"Width\" -> if ((parent == \"Row\")) { parts += \"width(\" + fmtDp(node.props[\"mod.Width.width\"]) + \")\"; imports += listOf(\"dev.keliver.ui.Dp\") }")
  }

  // K7: a widget's Dp prop records an Int-as-Dp value's imports (its token operands, `dp` or `Dp`); a modifier's does not hold one.
  @Test fun aDpWidgetPropRecordsAnIntAsDpValuesImports() {
    val spacer = box.copy(name = "Spacer", props = listOf(MappedProp("height", MappedKind.DP, required = false, defaultExpr = null)))
    val src = emitExporter(listOf(spacer))
    assertContains(src, "imports += listOf(\"dev.keliver.ui.Dp\"); imports.addTokenImport(node.props[\"height\"], \"Dp\")")
    assertContains(src, "else if (v is IntDp) arithKotlin(v, ::bindRef)")
    assertContains(src, "v is Arith -> arithKotlin(v, ::bindRef)")
  }

  @Test fun marginWidgetPropIsPresentWhenAnySideIs() {
    val row = box.copy(name = "Row", props = listOf(MappedProp("margin", MappedKind.MARGIN, required = false, defaultExpr = "Margin.Zero")))
    val src = emitExporter(listOf(row))
    assertContains(src, "if (\"margin.start\" in node.props || \"margin.end\" in node.props || \"margin.top\" in node.props || \"margin.bottom\" in node.props) { sb.append(\"\${indent}  margin = \${fmtMargin(node, \"margin\")},\\n\"); imports += marginImports(node, \"margin\") }")
    assertContains(src, "\"Row\" to listOf(\"dev.keliver.ui.Margin\", \"dev.keliver.ui.dp\")")
  }

  @Test fun designTokensExportAsReferencesWithTheirImportOnlyForPlainLiteralKinds() {
    val src = emitExporter(listOf(text, box))
    // K2: a Text/Int prop records the token's import (the formatter writes `C.Ink`)…
    assertContains(src, "imports.addTokenImport(node.props[\"text\"], \"String\")")
    assertContains(src, "imports.addTokenImport(node.props[\"fontSize\"], \"Int\")")
    assertContains(src, "v is Token && v.value is String -> v.ref")
    assertContains(src, "v is Token && v.value is Int -> v.ref")
    // …an enum (Constraint) or list prop never takes a token: no import, the value is unwrapped.
    assertFalse("addTokenImport(node.props[\"width\"]" in src)
    assertFalse("addTokenImport(node.props[\"gradientStops\"]" in src)
    assertContains(src, "else if (((tokenValue(v) as? Int) ?: 0) == 1) \"Constraint.Fill\"")
    // A component argument is a reference only where it fits the parameter's type.
    assertContains(src, "tokenFits(propSourceType(node.type, k, components), it.value)")
  }

  @Test fun conditionalValuesExportAsIfExpressionsInEveryKind() {
    val src = emitExporter(listOf(text, box))
    // K3: every literal formatter writes a conditional as `if (cond) T else F`, each branch in its kind.
    for (fmt in listOf("fmtString", "fmtInt", "fmtBool", "fmtDouble", "fmtFloat", "fmtDp", "fmtConstraint", "fmtCrossAxis", "fmtOverflow", "fmtMainAxis", "fmtDpValue")) {
      assertContains(src, "fmtConditional(v, ::$fmt)")
    }
    // Events go through fmtEvent (a conditional action is a reference or a parenthesized lambda).
    assertContains(src, "private fun fmtEvent(v: Any?, paramCount: Int): String?")
    assertContains(src, "a.arg == null && paramCount == 0 -> \"b::${'$'}{a.name}\"")
    // A Condition writes its own condition and its else block.
    assertContains(src, "sb.append(\"${'$'}{indent}if (${'$'}{cond.kotlin(::bindRef)}) {\\n\")")
    assertContains(src, "node.elseNode()?.let { e ->")
    // An emptiness test never retypes a Repeat's List items (review S2).
    // K6: every term; an equality (like a Boolean test) types its field outright.
    assertContains(src, "\"Condition\" -> condOf(node)?.terms?.forEach { c ->")
    assertContains(src, "if (c.test == CondTest.Bool || c.test == CondTest.Eq || out[c.field]?.startsWith(\"List<\") != true) out[c.field] = c.fieldType")
    assertContains(src, "if (value is Conditional) value.cond.terms.filter { it.field.startsWith(\"${'$'}itemVar.\") }.forEach { t ->")
    // K6: a design token an equality compares with is imported, wherever the condition sits.
    assertContains(src, "imports += conditionImports(tree)")
  }

  @Test fun templatesAndListsWithReferencesExportWithTheirTokensImports() {
    val box = box.copy(props = box.props + MappedProp("gradientColorsArgb", MappedKind.INT_LIST, required = false, defaultExpr = "emptyList()"))
    val src = emitExporter(listOf(text, box))
    // K4: a template is written as one; each list element in its kind.
    assertContains(src, "v is Template -> fmtTemplate(v, ::bindRef)")
    assertContains(src, "listItems(v).joinToString(\", \") { fmtInt(it) }")
    assertContains(src, "imports.addTokenImport(node.props[\"gradientColorsArgb\"], \"List<Int>\")")
    assertContains(src, "is Template -> fmtTemplate(x, ::bindRef)")
  }
}
