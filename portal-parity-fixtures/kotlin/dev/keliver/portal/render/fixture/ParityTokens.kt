package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Row
import dev.keliver.material.compose.AsyncImage
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.background

// K2: design-token constants — compiled here as the device path, recognized by
// portal-ingest into parity-tokens-body.json (each token carries its resolved
// value beside its reference), interpreted by RenderNode from those values.
// The object shares its file with the component, as app token objects often do.
internal object ParityTokens {
  const val Ink = -14737633 // #1F1F1F
  const val Paper = -1 // #FFFFFF
  private const val BASE = "https://example.com"
  const val Logo = "$BASE/logo.png"
  const val Heading = "Parity " + "tokens"
  const val Size = 18
  const val Strong = true
  const val Weight = 2.0
}

@Composable
internal fun ParityTokenCard(label: String) {
  Row(width = Constraint.Fill) {
    StyledText(
      text = ParityTokens.Heading,
      colorArgb = ParityTokens.Ink,
      fontSize = ParityTokens.Size,
      bold = ParityTokens.Strong,
      modifier = Modifier.background(ParityTokens.Paper).flex(ParityTokens.Weight),
    )
    StyledText(text = label, colorArgb = ParityTokens.Ink)
    AsyncImage(url = ParityTokens.Logo, widthDp = 24, heightDp = 24)
  }
}
