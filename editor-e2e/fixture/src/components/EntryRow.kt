package fieldnotes.components

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText

/** One logbook entry. Repeated by the logbook screen's forEach. */
@Composable
fun EntryRow(
  title: String,
  detail: String,
) {
  StyledBox(
    colorArgb = -10720320,
    widthDp = 350,
    heightDp = 110,
    cornerRadiusDp = 12,
    paddingDp = 12,
  ) {
    Column {
      StyledText(
        text = title,
        fontSize = 15,
        bold = true,
        colorArgb = -1,
      )
      StyledText(
        text = detail,
        fontSize = 12,
        colorArgb = -1,
      )
    }
  }
}
