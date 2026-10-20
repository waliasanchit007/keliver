package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.Modifier
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText

// Compiled component preview: Double, nullable scalars and one-argument
// events, exercised at runtime (`ratio * 100` makes the body opaque).
@Composable
internal fun ParityOpaqueMeter(
  ratio: Double,
  modifier: Modifier = Modifier,
  caption: String? = null,
  step: Int? = null,
  shown: Boolean? = null,
  onPick: ((String) -> Unit)? = null,
  onStep: (Int) -> Unit = {},
) {
  Column(modifier = modifier) {
    StyledText(text = "${(ratio * 100).toInt()}% ${caption ?: "-"} ${step ?: -1} ${shown ?: "?"}")
    if (onPick != null) Button(text = "pick", onClick = { onPick("a") })
    Button(text = "step", onClick = { onStep(step ?: 1) })
  }
}
