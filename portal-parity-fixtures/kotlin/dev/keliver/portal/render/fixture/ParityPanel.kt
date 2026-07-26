package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column

@Composable
internal fun ParityPanel(title: String) {
  ParitySection {
    Column(width = Constraint.Fill) {
      ParityLeaf(label = title)
    }
  }
}
