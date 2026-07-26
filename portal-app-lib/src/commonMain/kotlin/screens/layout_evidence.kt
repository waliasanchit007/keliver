package dev.keliver.portalpublished.screens

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.AsyncImage
import dev.keliver.material.compose.Divider
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.background
import dev.keliver.material.compose.border
import dev.keliver.material.compose.cornerRadius
import dev.keliver.material.compose.fillWidth
import dev.keliver.material.compose.padding
import dev.keliver.material.compose.size
import dev.keliver.ui.Dp

/**
 * K3's deterministic layout kitchen sink. Keep this portal-recognizable and
 * literal-only: the same relay tree is captured by web, Android, and iOS.
 */
@Composable
fun LayoutEvidenceScreen() {
  StyledBox(
    fillWidth = true,
    paddingDp = 16,
    colorArgb = -460036,
  ) {
    Column {
      StyledText(
        text = "K3 · LAYOUT EVIDENCE · V1",
        fontSize = 22,
        bold = true,
        colorArgb = -15788246,
        letterSpacingX100 = 40,
      )
      StyledText(
        text = "Fill · wrap · align · nest · modifiers · text · image",
        fontSize = 12,
        colorArgb = -10193781,
      )
      Spacer(height = Dp(14.0))

      StyledText(
        text = "01  FILL VS WRAP",
        fontSize = 11,
        bold = true,
        colorArgb = -13418155,
      )
      Spacer(height = Dp(6.0))
      StyledBox(
        fillWidth = true,
        paddingDp = 8,
        colorArgb = -2364674,
        borderColorArgb = -14326805,
        borderWidthDp = 1,
      ) {
        StyledText(
          text = "FILL · reaches both content edges",
          fontSize = 13,
          bold = true,
          colorArgb = -14326805,
        )
      }
      Spacer(height = Dp(6.0))
      StyledBox(
        paddingDp = 8,
        colorArgb = -2294553,
        borderColorArgb = -15293622,
        borderWidthDp = 1,
      ) {
        StyledText(
          text = "WRAP · stops here",
          fontSize = 13,
          bold = true,
          colorArgb = -15293622,
        )
      }
      Spacer(height = Dp(14.0))

      StyledText(
        text = "02  ROW ALIGNMENT",
        fontSize = 11,
        bold = true,
        colorArgb = -13418155,
      )
      Spacer(height = Dp(6.0))
      Row(
        modifier = Modifier.background(-1906448).cornerRadius(10).padding(10),
        width = Constraint.Fill,
        horizontalAlignment = MainAxisAlignment.SpaceBetween,
        verticalAlignment = CrossAxisAlignment.Center,
      ) {
        StyledBox(
          modifier = Modifier.size(62, 26),
          colorArgb = -14326805,
          cornerRadiusDp = 6,
        ) {
          StyledText(text = "START", fontSize = 10, bold = true, colorArgb = -1)
        }
        StyledBox(
          modifier = Modifier.size(62, 42),
          colorArgb = -7130134,
          cornerRadiusDp = 6,
        ) {
          StyledText(text = "CENTER", fontSize = 10, bold = true, colorArgb = -1)
        }
        StyledBox(
          modifier = Modifier.size(62, 32),
          colorArgb = -2024120,
          cornerRadiusDp = 6,
        ) {
          StyledText(text = "END", fontSize = 10, bold = true, colorArgb = -1)
        }
      }
      Spacer(height = Dp(14.0))

      StyledText(
        text = "03  NESTING + MODIFIER ORDER",
        fontSize = 11,
        bold = true,
        colorArgb = -13418155,
      )
      Spacer(height = Dp(6.0))
      Column(
        modifier = Modifier.background(-68665).cornerRadius(12).padding(10).border(2, -2525434),
        width = Constraint.Fill,
        horizontalAlignment = CrossAxisAlignment.Stretch,
      ) {
        StyledText(
          text = "OUTER · background → corner → padding → border",
          fontSize = 12,
          bold = true,
          colorArgb = -2525434,
        )
        Spacer(height = Dp(6.0))
        Row(
          modifier = Modifier.background(-792321).cornerRadius(8).padding(8),
          width = Constraint.Fill,
          horizontalAlignment = MainAxisAlignment.SpaceBetween,
          verticalAlignment = CrossAxisAlignment.Center,
        ) {
          StyledText(text = "NESTED ROW", fontSize = 12, bold = true, colorArgb = -7130134)
          StyledBox(
            modifier = Modifier.size(42, 22),
            colorArgb = -7130134,
            cornerRadiusDp = 11,
          ) {
            StyledText(text = "42×22", fontSize = 9, colorArgb = -1)
          }
        }
      }
      Spacer(height = Dp(14.0))

      StyledText(
        text = "04  TEXT CONSTRAINTS",
        fontSize = 11,
        bold = true,
        colorArgb = -13418155,
      )
      Spacer(height = Dp(6.0))
      StyledBox(
        modifier = Modifier.fillWidth().background(-6938).padding(8),
        cornerRadiusDp = 8,
      ) {
        StyledText(
          text = "ELLIPSIS · This deliberately long sentence must stay on exactly one line on every host.",
          fontSize = 13,
          bold = true,
          colorArgb = -2024120,
          maxLines = 1,
          overflow = 1,
        )
      }
      Spacer(height = Dp(6.0))
      StyledBox(
        modifier = Modifier.fillWidth().background(-4528387).padding(8),
        cornerRadiusDp = 8,
      ) {
        StyledText(
          text = "WRAP · Predictable text wraps inside the available width while preserving the same outer padding and line-height contract.",
          fontSize = 13,
          colorArgb = -15788246,
          lineHeightSp = 18,
        )
      }
      Spacer(height = Dp(14.0))

      StyledText(
        text = "05  IMAGE GEOMETRY",
        fontSize = 11,
        bold = true,
        colorArgb = -13418155,
      )
      Spacer(height = Dp(6.0))
      AsyncImage(
        url = "https://picsum.photos/seed/keliver-k3/800/240",
        contentScale = 1,
        heightDp = 96,
        fillWidth = true,
      )
      Spacer(height = Dp(8.0))
      Divider()
      Spacer(height = Dp(8.0))
      StyledBox(
        fillWidth = true,
        paddingDp = 8,
        colorArgb = -15788246,
        cornerRadiusDp = 8,
      ) {
        StyledText(
          text = "END · NOTHING CLIPPED",
          fontSize = 12,
          bold = true,
          colorArgb = -1,
          align = 1,
        )
      }
    }
  }
}
