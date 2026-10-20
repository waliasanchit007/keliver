package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.compose.Row
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText

// Compiled component preview: a component the portal grammar cannot read
// (integer arithmetic and `.uppercase()`), so it is OPAQUE — the canvas can
// only draw it by calling this compiled function (ParityCompiledComponents).
@Composable
internal fun ParityOpaqueBadge(
  label: String,
  count: Int,
  modifier: Modifier = Modifier,
  highlighted: Boolean = false,
  accentArgb: Int = -16_745_729,
  onTap: (() -> Unit)? = null,
) {
  val shown = count + 1
  StyledBox(modifier = modifier, colorArgb = if (highlighted) accentArgb else -1_579_033, cornerRadiusDp = 8, paddingDp = 6, onClick = onTap) {
    Row(verticalAlignment = CrossAxisAlignment.Center) {
      StyledText(text = label.uppercase(), bold = highlighted, fontSize = 12)
      StyledText(text = " · $shown", fontSize = 12, colorArgb = -7_829_368)
    }
  }
}
