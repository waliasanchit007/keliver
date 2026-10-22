package fieldnotes.components

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText

/** A card's heading, on an optional tint. Nested inside SummaryCard and ActionCard. */
@Composable
fun CardTitle(
  text: String,
  tintArgb: Int = 0,
) {
  StyledBox(
    colorArgb = tintArgb,
    paddingDp = 2,
  ) {
    StyledText(
      text = text,
      fontSize = 15,
      bold = true,
      colorArgb = -15000801,
    )
  }
}
