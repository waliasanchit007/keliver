package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.SegmentedButtonRow
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText

// K4: string templates over the component's parameters and design tokens, and
// lists whose elements are parameters and tokens — compiled here as the device
// path, recognized by portal-ingest into parity-templates-body.json, and
// interpreted by RenderNode after preview expansion substitutes the instance's
// arguments.
internal object ParityScoreTokens {
  const val Unit = "pts"
  const val End = -5674 // #FFE9D6
}

@Composable
internal fun ParityScore(label: String, count: Int, start: Int) {
  Column(width = Constraint.Fill) {
    StyledText(text = "$label: $count ${ParityScoreTokens.Unit}")
    StyledBox(gradientColorsArgb = listOf(start, ParityScoreTokens.End), gradientStops = listOf(0.0f, 1.0f)) {
      StyledText(text = "\"${label}\" #${count}\n")
    }
    SegmentedButtonRow(options = listOf("All", label, ParityScoreTokens.Unit), selectedIndex = 0)
  }
}
