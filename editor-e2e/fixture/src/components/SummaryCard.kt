package fieldnotes.components

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.StyledBox
import dev.keliver.ui.Dp

/**
 * A card built almost entirely from other components: its definition nests a
 * CardTitle and three StatTiles. Their ids are local to this definition.
 */
@Composable
fun SummaryCard(
  title: String,
  visits: String,
  samples: String,
  alerts: String,
) {
  StyledBox(
    colorArgb = -13730453,
    widthDp = 350,
    cornerRadiusDp = 16,
    paddingDp = 12,
  ) {
    Column {
      CardTitle(
        text = title,
      )
      Spacer(
        height = Dp(8.0),
      )
      Row {
        StatTile(
          label = "Visits",
          value = visits,
          colorArgb = -14776091,
        )
        Spacer(
          width = Dp(13.0),
        )
        StatTile(
          label = "Samples",
          value = samples,
          colorArgb = -1754827,
        )
        Spacer(
          width = Dp(13.0),
        )
        StatTile(
          label = "Alerts",
          value = alerts,
          colorArgb = -7461718,
        )
      }
    }
  }
}
