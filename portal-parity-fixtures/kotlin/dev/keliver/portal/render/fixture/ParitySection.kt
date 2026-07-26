package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledBox

@Composable
internal fun ParitySection(content: @Composable () -> Unit) {
  StyledBox(
    colorArgb = -1,
    cornerRadiusDp = 12,
    fillWidth = true,
  ) {
    Column(width = Constraint.Fill) {
      content()
    }
  }
}
