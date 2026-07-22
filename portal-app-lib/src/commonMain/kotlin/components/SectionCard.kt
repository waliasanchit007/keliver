package dev.keliver.portalpublished.components

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText

/** The canonical titled section container, including its editable content slot. */
@Composable
fun SectionCard(
  label: String,
  content: @Composable () -> Unit,
) {
  Column {
    StyledText(
      text = label,
      fontSize = 12,
      bold = true,
      colorArgb = -6643546,
    )
    StyledBox(
      colorArgb = -1,
      cornerRadiusDp = 16,
      borderColorArgb = -1183757,
      borderWidthDp = 1,
      fillWidth = true,
    ) {
      content()
    }
  }
}
