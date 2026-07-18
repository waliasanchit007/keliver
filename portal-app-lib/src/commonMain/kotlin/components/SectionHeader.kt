package dev.keliver.portalpublished.components

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.StyledText

/**
 * A section title molecule — the app's canonical "ALL-CAPS grey label" styling
 * captured once. Leaf component (v1): no children slot.
 */
@Composable
fun SectionHeader(label: String) {
  StyledText(
    text = label,
    fontSize = 12,
    bold = true,
    colorArgb = -6643546,
  )
}
