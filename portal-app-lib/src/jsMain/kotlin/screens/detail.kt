package dev.keliver.portalpublished.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.Divider
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.TextButton
import dev.keliver.ui.Dp

/** DOGFOOD detail screen — reached by tapping a feed card (hand-owned nav in PublishedEntry). */
@Composable
fun DetailScreen(b: DetailScreenBindings) {
  StyledBox(
    paddingDp = 20,
    fillWidth = true,
  ) {
    Column {
      TextButton(
        text = "< Back",
        onClick = { b.back() },
      )
      Spacer(
        height = Dp(8.0),
      )
      StyledText(
        text = b.title,
        fontSize = 26,
        bold = true,
        colorArgb = -14540254,
      )
      Spacer(
        height = Dp(8.0),
      )
      Divider()
      Spacer(
        height = Dp(8.0),
      )
      StyledText(
        text = b.body,
        fontSize = 16,
        colorArgb = -11184811,
      )
      Spacer(
        height = Dp(12.0),
      )
      StyledText(
        text = b.time,
        fontSize = 12,
        colorArgb = -8355712,
      )
    }
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface DetailScreenBindings {
  val title: String
  val body: String
  val time: String
  fun back()
}
