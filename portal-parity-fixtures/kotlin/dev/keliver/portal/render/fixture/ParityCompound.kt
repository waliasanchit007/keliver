package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Icon
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Margin
import dev.keliver.ui.dp

// K6: equality terms, compound conditions and a derived Boolean local —
// compiled here as the device path, recognized by portal-ingest into
// parity-compound-body.json (the local inlined into each condition reading
// it), and interpreted by RenderNode after preview expansion decides each
// term against the instance's arguments.
internal object ParityCompoundTokens {
  const val Ink = -14737633 // #1F1F1F
  const val Muted = -6250336 // #A0A0A0
  const val Featured = "XY"
  const val Top = 3
}

@Composable
internal fun ParityCompound(label: String, enabled: Boolean, loading: Boolean, code: String, tier: Int = 0, round: Boolean = true) {
  val active = enabled && !loading
  Column(width = Constraint.Fill) {
    StyledText(
      text = if (loading) "Loading" else label,
      colorArgb = if (active) ParityCompoundTokens.Ink else ParityCompoundTokens.Muted,
      modifier = Modifier.margin(Margin(top = if (tier == ParityCompoundTokens.Top || !round) 8.dp else 0.dp)),
    )
    Icon(name = if (code == ParityCompoundTokens.Featured && active) "Star" else "Info")
    if (!active) {
      StyledText(text = "unavailable")
    } else {
      StyledText(text = "ready")
    }
    if (code != "XY" && tier == -1) {
      StyledText(text = "fallback")
    }
    if (round == false || label.isEmpty()) {
      StyledText(text = "square")
    }
  }
}
