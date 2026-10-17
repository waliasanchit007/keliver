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
import dev.keliver.material.compose.padding
import dev.keliver.material.compose.size
import dev.keliver.ui.Dp
import dev.keliver.ui.Margin
import dev.keliver.ui.dp

// K1: Margin values and SCOPED layout modifiers (members of RowScope /
// ColumnScope / BoxScope) — compiled here as the device path, recognized by
// portal-ingest into parity-layout-body.json, interpreted by RenderNode with
// the parent's scope. Chains are in AUTHOR order, scoped and unscoped mixed:
// the document keeps that order and both the exporter and RenderNode use it.
// The positional Margin(…) calls pin Kotlin's overload resolution: one value
// = all, two = (horizontal, vertical), three/four = (start, end, top, bottom).
@Composable
internal fun ParityLayout(left: String, right: String) {
  Column(width = Constraint.Fill, margin = Margin(horizontal = 16.dp, vertical = 8.dp)) {
    Row(width = Constraint.Fill, margin = Margin(start = 4.dp, bottom = 2.dp)) {
      StyledText(text = left, modifier = Modifier.margin(Margin(all = 4.dp)).padding(8).flex(1.0))
      StyledText(text = right, modifier = Modifier.verticalAlignment(CrossAxisAlignment.Center).grow(2.0).shrink(1.0))
      Spacer(width = Dp(8.0), modifier = Modifier.size(Dp(4.0), 6.dp))
      StyledText(text = "fixed", modifier = Modifier.size(12, 12))
    }
    Box(margin = Margin(5.dp)) {
      StyledText(
        text = "boxed",
        modifier = Modifier.width(Dp(40.0)).horizontalAlignment(CrossAxisAlignment.End).height(20.dp),
      )
    }
    Row(margin = Margin(1.dp, 2.dp)) {}
    Column(margin = Margin(1.dp, 2.dp, 3.dp)) {}
    Box(margin = Margin(1.dp, 2.dp, 3.dp, 4.dp)) {}
    StyledText(text = left, modifier = Modifier.margin(Margin.Zero).horizontalAlignment(CrossAxisAlignment.Center))
  }
}
