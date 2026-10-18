package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Icon
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Margin
import dev.keliver.ui.dp

// K3: values and children chosen by the component's parameters — compiled
// here as the device path, recognized by portal-ingest into
// parity-conditionals-body.json, and interpreted by RenderNode after preview
// expansion decides each condition against the instance's arguments.
internal object ParityToggleTokens {
  const val On = -14737633 // #1F1F1F
  const val Off = -1 // #FFFFFF
}

@Composable
internal fun ParityToggle(label: String, selected: Boolean, subtitle: String, raised: Boolean = false, note: String? = null) {
  Column(width = Constraint.Fill) {
    StyledText(
      text = label,
      colorArgb = if (selected) ParityToggleTokens.On else ParityToggleTokens.Off,
      modifier = Modifier.margin(Margin(top = if (raised) 8.dp else 0.dp)),
    )
    Icon(name = if (selected) "KeyboardArrowUp" else "KeyboardArrowDown")
    if (!selected) {
      StyledText(text = "off")
    } else {
      StyledText(text = "on")
    }
    if (subtitle.isNotEmpty()) {
      StyledText(text = subtitle)
    }
    if (note != null) {
      StyledText(text = "note")
    }
  }
}
