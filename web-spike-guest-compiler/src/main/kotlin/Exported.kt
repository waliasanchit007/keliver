import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment
import dev.keliver.layout.api.Overflow
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp

@Composable
fun ExportedScreen() {
  StyledBox(
    cornerRadiusDp = 12,
    paddingDp = 20,
    fillWidth = true,
    borderColorArgb = -9808,
    borderWidthDp = 1,
    gradientColorsArgb = listOf(-2840, -5674),
    gradientStops = listOf(0.0f, 1.0f),
    gradientDirection = 3,
  ) {
    Column(
    ) {
      StyledText(
        text = "PORTAL PREVIEW · rendered from a tree",
        fontSize = 12,
        bold = true,
        colorArgb = -7697782,
      )
      Spacer(
        height = Dp(10.0),
      )
      StyledText(
        text = "items: 3",
        fontSize = 24,
        bold = true,
        colorArgb = -15658735,
      )
      Spacer(
        height = Dp(8.0),
      )
      StyledText(
        text = "• item 1",
        fontSize = 16,
        colorArgb = -13421773,
      )
      StyledText(
        text = "• item 2",
        fontSize = 16,
        colorArgb = -13421773,
      )
      StyledText(
        text = "• item 3",
        fontSize = 16,
        colorArgb = -13421773,
      )
    }
  }
}
