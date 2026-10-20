package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import dev.keliver.Modifier
import dev.keliver.material.compose.StyledText

// Compiled component preview: an opaque component with a SIDE EFFECT that
// calls one of its events — the failure probe must not deliver it.
@Composable
internal fun ParityOpaqueAnnounce(label: String, modifier: Modifier = Modifier, onShown: () -> Unit = {}) {
  SideEffect { onShown() }
  StyledText(modifier = modifier, text = label.lowercase())
}
