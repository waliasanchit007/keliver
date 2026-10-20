package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.material.compose.StyledText

// Compiled component preview: an opaque component that THROWS while composing
// for some arguments (`first()` of an empty name) — on a device as in the
// canvas. The canvas must fall back to the placeholder, not stop.
@Composable
internal fun ParityOpaqueInitials(name: String, modifier: Modifier = Modifier) {
  StyledText(modifier = modifier, text = name.trim().first().uppercaseChar().toString(), bold = true)
}
