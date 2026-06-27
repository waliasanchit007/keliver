/*
 * spike/keliver-web portal sub-project A — the runtime engine.
 * RenderNode is a guest @Composable that interprets a WidgetNode tree into real
 * keliver composables. Editing the tree (a MutableState) recomposes this and the
 * host re-renders the minimal change — no recompile, no protocol tags by hand.
 * The per-widget when() covers the curated MVP subset; it is codegen-able from
 * the schema later, and the same mapping drives Export-to-Kotlin (sub-project B).
 */
import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.AsyncImage
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp

@Composable
fun RenderNode(node: WidgetNode) {
  when (node.type) {
    "StyledBox" -> StyledBox(
      colorArgb = node.int("colorArgb"),
      gradientColorsArgb = node.intList("gradientColorsArgb"),
      gradientStops = node.floatList("gradientStops"),
      gradientDirection = node.int("gradientDirection"),
      borderColorArgb = node.int("borderColorArgb"),
      borderWidthDp = node.int("borderWidthDp"),
      cornerRadiusDp = node.int("cornerRadiusDp"),
      fillWidth = node.bool("fillWidth"),
      paddingDp = node.int("paddingDp"),
      heightDp = node.int("heightDp"),
      contentAlignment = node.int("contentAlignment"),
    ) { node.children.forEach { RenderNode(it) } }

    "Column" -> Column(
      width = if (node.bool("fillWidth", true)) Constraint.Fill else Constraint.Wrap,
      horizontalAlignment = CrossAxisAlignment.Stretch,
    ) { node.children.forEach { RenderNode(it) } }

    "Row" -> Row(
      width = if (node.bool("fillWidth")) Constraint.Fill else Constraint.Wrap,
    ) { node.children.forEach { RenderNode(it) } }

    "StyledText" -> StyledText(
      text = node.str("text"),
      fontSize = node.int("fontSize", 14),
      bold = node.bool("bold"),
      colorArgb = node.int("colorArgb"),
    )

    "Button" -> Button(text = node.str("text"), onClick = {})

    "AsyncImage" -> AsyncImage(url = node.str("url"), fillWidth = node.bool("fillWidth"))

    "Spacer" -> Spacer(height = Dp(node.dbl("height")))

    else -> StyledText(text = "⚠ unknown widget: ${node.type}", colorArgb = 0xFFB00020.toInt())
  }
}
