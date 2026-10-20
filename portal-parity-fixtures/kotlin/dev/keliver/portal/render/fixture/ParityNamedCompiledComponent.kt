package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.material.compose.StyledText

// Compiled component preview, generator edge case: a component named like a
// type the generated adapters use. Its adapter must still compile.
@Composable
internal fun CompiledComponent(label: String) {
  StyledText(text = label.reversed())
}
