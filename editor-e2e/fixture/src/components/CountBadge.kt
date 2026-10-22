package fieldnotes.components

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText

/**
 * A count whose text is COMPUTED (singular or plural), so its body is outside
 * the editor's grammar: an opaque component. It previews as a placeholder and
 * cannot be opened for editing.
 */
@Composable
fun CountBadge(
  count: Int,
) {
  val text = if (count == 1) "1 sample" else "$count samples"
  StyledBox(colorArgb = -1183757, cornerRadiusDp = 12, paddingDp = 6) {
    StyledText(text = text, fontSize = 11, bold = true, colorArgb = -10395295)
  }
}
