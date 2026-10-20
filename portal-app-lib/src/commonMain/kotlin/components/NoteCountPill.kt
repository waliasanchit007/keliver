package dev.keliver.portalpublished.components

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText

/**
 * A count pill whose text is COMPUTED (singular/plural, upper-cased), so its
 * body is outside the portal grammar: an OPAQUE component. The editor draws
 * it with this compiled function (web-spike's AppLibCompiledComponents, written
 * by the relay — docs/COMPILED_COMPONENT_PREVIEW.md) instead of a placeholder.
 */
@Composable
fun NoteCountPill(
  count: Int,
  label: String = "notes",
  modifier: Modifier = Modifier,
) {
  val text = if (count == 1) "1 ${label.removeSuffix("s")}" else "$count $label"
  StyledBox(modifier = modifier, colorArgb = -1_183_757, cornerRadiusDp = 12, paddingDp = 6) {
    StyledText(text = text.uppercase(), fontSize = 11, bold = true, colorArgb = -10_395_295)
  }
}
