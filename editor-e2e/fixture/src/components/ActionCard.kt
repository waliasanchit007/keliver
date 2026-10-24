package fieldnotes.components

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp

/** A card with a button inside it (and a nested CardTitle). */
@Composable
fun ActionCard(
  label: String,
  hint: String,
  onAction: () -> Unit,
) {
  StyledBox(
    colorArgb = -19712,
    widthDp = 350,
    cornerRadiusDp = 16,
    paddingDp = 12,
  ) {
    Column {
      CardTitle(
        text = label,
      )
      StyledText(
        text = hint,
        fontSize = 12,
        colorArgb = -10525848,
      )
      Spacer(
        height = Dp(10.0),
      )
      Button(
        text = "Start",
        containerArgb = -9614271,
        onClick = onAction,
      )
    }
  }
}
