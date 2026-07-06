package dev.keliver.portalpublished.screens

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment
import dev.keliver.layout.api.Overflow
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.Snackbar
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.Switch
import dev.keliver.material.compose.TextButton
import dev.keliver.material.compose.cornerRadius
import dev.keliver.material.compose.padding
import dev.keliver.ui.Dp

@Composable
// HAND-WRITTEN COMMENT — must survive edits
fun MainScreen(b: MainScreenBindings) {
  StyledBox(
    cornerRadiusDp = 24,
    paddingDp = 32,
    fillWidth = true,
    borderColorArgb = -9808,
    borderWidthDp = 1,
    gradientColorsArgb = listOf(-2840, -5674),
    gradientStops = listOf(0.0f, 1.0f),
    gradientDirection = 3,
  ) {
    Button(
      text = "M6 In-Repo Canonical",
    )
    Column(
    ) {
      StyledText(
        modifier = Modifier.padding(8),
        text = b.text,
        fontSize = 20,
        bold = false,
        colorArgb = -15658735,
      )
      Spacer(
        height = Dp(8.0),
      )
      StyledText(
        text = "Add widgets from the palette — everything is live on web and device.",
        fontSize = 14,
        colorArgb = -11184811,
      )
      Button(
        text = "New Button",
        onClick = { b.buyTapped() },
      )
      Snackbar(
        modifier = Modifier.cornerRadius(40),
        message = "hey",
      )
      Switch(
        checked = false,
      )
      TextButton(
        text = "New TextButton",
      )
    }
  }
}

/** The round-trip boundary: implement this by hand; the portal never touches it. */
interface MainScreenBindings {
  val text: String
  fun buyTapped()
}
