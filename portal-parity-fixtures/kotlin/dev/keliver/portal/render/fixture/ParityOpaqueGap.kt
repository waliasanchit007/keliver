package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Spacer
import dev.keliver.ui.Dp

// Compiled component preview: an opaque component WITHOUT a `modifier`
// parameter (the arithmetic makes it opaque). It renders exactly, but the
// instance's selection tag has nowhere to go (docs/COMPILED_COMPONENT_PREVIEW.md).
@Composable
internal fun ParityOpaqueGap(units: Int = 1) {
  Spacer(height = Dp(units * 4.0))
}
