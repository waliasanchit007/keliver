package dev.keliver.portal

/**
 * Export a WidgetNode tree to real keliver guest Kotlin (a @Composable function).
 * The emitted call/args mirror RenderNode's interpreter so render == export.
 * (The two parallel when()s are an accepted MVP coupling; unify via schema later.)
 */
fun exportKotlin(tree: WidgetNode, functionName: String = "ExportedScreen"): String = buildString {
  appendLine("import androidx.compose.runtime.Composable")
  appendLine("import dev.keliver.layout.api.Constraint")
  appendLine("import dev.keliver.layout.api.CrossAxisAlignment")
  appendLine("import dev.keliver.layout.compose.Column")
  appendLine("import dev.keliver.layout.compose.Row")
  appendLine("import dev.keliver.layout.compose.Spacer")
  appendLine("import dev.keliver.material.compose.AsyncImage")
  appendLine("import dev.keliver.material.compose.Button")
  appendLine("import dev.keliver.material.compose.StyledBox")
  appendLine("import dev.keliver.material.compose.StyledText")
  appendLine("import dev.keliver.ui.Dp")
  appendLine()
  appendLine("@Composable")
  appendLine("fun $functionName() {")
  emitNode(tree, "  ")
  appendLine("}")
}

private fun StringBuilder.emitNode(node: WidgetNode, indent: String) {
  when (node.type) {
    "StyledBox" -> {
      appendLine("${indent}StyledBox(")
      appendLine("$indent  colorArgb = ${node.int("colorArgb")},")
      appendLine("$indent  gradientColorsArgb = ${intListLit(node.intList("gradientColorsArgb"))},")
      appendLine("$indent  gradientStops = ${floatListLit(node.floatList("gradientStops"))},")
      appendLine("$indent  gradientDirection = ${node.int("gradientDirection")},")
      appendLine("$indent  borderColorArgb = ${node.int("borderColorArgb")},")
      appendLine("$indent  borderWidthDp = ${node.int("borderWidthDp")},")
      appendLine("$indent  cornerRadiusDp = ${node.int("cornerRadiusDp")},")
      appendLine("$indent  fillWidth = ${node.bool("fillWidth")},")
      appendLine("$indent  paddingDp = ${node.int("paddingDp")},")
      appendLine("$indent  heightDp = ${node.int("heightDp")},")
      appendLine("$indent  contentAlignment = ${node.int("contentAlignment")},")
      appendLine("$indent) {")
      node.children.forEach { emitNode(it, "$indent  ") }
      appendLine("$indent}")
    }
    "Column" -> {
      appendLine("${indent}Column(")
      appendLine("$indent  width = ${if (node.bool("fillWidth", true)) "Constraint.Fill" else "Constraint.Wrap"},")
      appendLine("$indent  horizontalAlignment = CrossAxisAlignment.Stretch,")
      appendLine("$indent) {")
      node.children.forEach { emitNode(it, "$indent  ") }
      appendLine("$indent}")
    }
    "Row" -> {
      appendLine("${indent}Row(width = ${if (node.bool("fillWidth")) "Constraint.Fill" else "Constraint.Wrap"}) {")
      node.children.forEach { emitNode(it, "$indent  ") }
      appendLine("$indent}")
    }
    "StyledText" -> appendLine("${indent}StyledText(text = ${strLit(node.str("text"))}, fontSize = ${node.int("fontSize", 14)}, bold = ${node.bool("bold")}, colorArgb = ${node.int("colorArgb")})")
    "Button" -> appendLine("${indent}Button(text = ${strLit(node.str("text"))}, onClick = {})")
    "AsyncImage" -> appendLine("${indent}AsyncImage(url = ${strLit(node.str("url"))}, fillWidth = ${node.bool("fillWidth")})")
    "Spacer" -> appendLine("${indent}Spacer(height = Dp(${node.dbl("height")}))")
    else -> appendLine("$indent// unknown widget: ${node.type}")
  }
}

private fun strLit(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun intListLit(xs: List<Int>): String = if (xs.isEmpty()) "emptyList()" else "listOf(${xs.joinToString(", ")})"

private fun floatListLit(xs: List<Float>): String = if (xs.isEmpty()) "emptyList()" else "listOf(${xs.joinToString(", ") { "${it}f" }})"
