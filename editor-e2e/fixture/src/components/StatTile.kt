package fieldnotes.components

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText

/** One number with a caption, on a coloured tile. Nested inside SummaryCard. */
@Composable
fun StatTile(
  label: String,
  value: String,
  colorArgb: Int,
) {
  StyledBox(
    colorArgb = colorArgb,
    widthDp = 100,
    heightDp = 72,
    cornerRadiusDp = 10,
    paddingDp = 8,
  ) {
    Column {
      StyledText(
        text = value,
        fontSize = 20,
        bold = true,
        colorArgb = -1,
      )
      StyledText(
        text = label,
        fontSize = 11,
        colorArgb = -1,
      )
    }
  }
}
