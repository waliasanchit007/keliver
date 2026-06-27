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
fun ExportedScreen() {
  StyledBox(
    colorArgb = 0,
    gradientColorsArgb = listOf(-2840, -5674),
    gradientStops = listOf(0.0f, 1.0f),
    gradientDirection = 3,
    borderColorArgb = -9808,
    borderWidthDp = 1,
    cornerRadiusDp = 12,
    fillWidth = true,
    paddingDp = 20,
    heightDp = 0,
    contentAlignment = 0,
  ) {
    Column(
      width = Constraint.Fill,
      horizontalAlignment = CrossAxisAlignment.Stretch,
    ) {
      StyledText(text = "PORTAL PREVIEW · rendered from a tree", fontSize = 12, bold = true, colorArgb = -7697782)
      Spacer(height = Dp(10.0))
      StyledText(text = "items: 3", fontSize = 24, bold = true, colorArgb = -15658735)
      Spacer(height = Dp(8.0))
      StyledText(text = "• item 1", fontSize = 16, bold = false, colorArgb = -13421773)
      StyledText(text = "• item 2", fontSize = 16, bold = false, colorArgb = -13421773)
      StyledText(text = "• item 3", fontSize = 16, bold = false, colorArgb = -13421773)
    }
  }
}
