package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText

// Compiled component preview: an opaque component with a content slot. Its
// title is computed (`trim()` / `ifEmpty`), so the body is outside the grammar.
@Composable
internal fun ParityOpaqueFrame(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Column(modifier = modifier) {
    StyledText(text = title.trim().ifEmpty { "Untitled" }, bold = true)
    content()
  }
}
