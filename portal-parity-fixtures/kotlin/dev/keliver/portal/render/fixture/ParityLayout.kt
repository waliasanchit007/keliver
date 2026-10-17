package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.compose.Box
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.size
import dev.keliver.ui.Dp
import dev.keliver.ui.Margin
import dev.keliver.ui.dp

// K1: Margin values and SCOPED layout modifiers (members of RowScope /
// ColumnScope / BoxScope) — compiled here as the device path, recognized by
// portal-ingest into parity-layout-body.json, interpreted by RenderNode with
// the parent's scope. Chains are written unscoped-first, then alphabetical:
// the canonical order the exporter writes and RenderNode applies.
@Composable
internal fun ParityLayout(left: String, right: String) {
  Column(width = Constraint.Fill, margin = Margin(horizontal = 16.dp, vertical = 8.dp)) {
    Row(width = Constraint.Fill, margin = Margin(start = 4.dp, bottom = 2.dp)) {
      StyledText(text = left, modifier = Modifier.flex(1.0).margin(Margin(all = 4.dp)))
      StyledText(text = right, modifier = Modifier.grow(2.0).shrink(1.0).verticalAlignment(CrossAxisAlignment.Center))
      Spacer(width = Dp(8.0), modifier = Modifier.size(Dp(4.0), 6.dp))
      StyledText(text = "fixed", modifier = Modifier.size(12, 12))
    }
    Box(margin = Margin(all = Dp(2.0))) {
      StyledText(
        text = "boxed",
        modifier = Modifier.height(20.dp).horizontalAlignment(CrossAxisAlignment.End).width(Dp(40.0)),
      )
    }
    StyledText(text = left, modifier = Modifier.horizontalAlignment(CrossAxisAlignment.Center).margin(Margin.Zero))
  }
}
