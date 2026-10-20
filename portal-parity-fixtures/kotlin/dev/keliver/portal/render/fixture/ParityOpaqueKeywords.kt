package dev.keliver.portal.render.fixture.`in`

import dev.keliver.Modifier
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText

// Compiled component preview, generator edge cases that must still COMPILE:
// a keyword package segment, keyword parameter names, and a content slot
// whose @Composable is written fully qualified (`text.length * 2` makes the
// body opaque).
@androidx.compose.runtime.Composable
internal fun ParityOpaqueKeywords(
  `in`: String,
  `object`: Int = 2,
  modifier: Modifier = Modifier,
  content: @androidx.compose.runtime.Composable () -> Unit,
) {
  Column(modifier = modifier) {
    StyledText(text = "${`in`} ${`in`.length * `object`}")
    content()
  }
}
