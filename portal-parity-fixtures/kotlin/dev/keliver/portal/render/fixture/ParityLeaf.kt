package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.background
import dev.keliver.material.compose.padding

@Composable
internal fun ParityLeaf(label: String) {
  StyledText(
    modifier = Modifier.background(-1579033).padding(6),
    text = label,
    fontSize = 12,
    bold = true,
  )
}
