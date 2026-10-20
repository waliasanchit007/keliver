package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.layout.api.Constraint
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.StyledBox
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp
import dev.keliver.ui.dp

// K7: Int arithmetic and Ints used as Dps — compiled here as the device path,
// recognized by portal-ingest into parity-arith-body.json (the derived Int
// local inlined where it is read), and interpreted by RenderNode after
// preview expansion substitutes the instance's arguments. Every operator,
// precedence, parentheses, negative literals and operands, 32-bit overflow,
// truncation toward zero, the dividend's sign for `%`, `Int.MIN_VALUE / -1`,
// both Int→Dp spellings.
internal object ParityArithTokens {
  const val Base = 12
  const val Max = 2147483647
}

@Composable
internal fun ParityArith(size: Int, delta: Int, gap: Int = 8) {
  val half = size / 2
  Column(width = Constraint.Fill) {
    StyledBox(cornerRadiusDp = half, colorArgb = size * 65536 + delta) {
      StyledText(text = "precedence", fontSize = size * 2 / 5 - delta, colorArgb = size + delta * 3)
    }
    StyledText(text = "parentheses", fontSize = size - (delta - 3), colorArgb = (size + delta) * -2)
    StyledText(text = "remainder", fontSize = size % -3 + delta % 7, colorArgb = (size - delta) % 5)
    StyledText(text = "overflow", fontSize = size * 123456789 + ParityArithTokens.Max, colorArgb = delta - 1 - ParityArithTokens.Max)
    StyledText(text = "division", fontSize = delta / -1, colorArgb = size / 4 * 4 + ParityArithTokens.Base)
    StyledText(text = "grouping", fontSize = size * (delta / 3), colorArgb = size - delta - 1)
    Spacer(height = Dp(gap.toDouble()))
    Spacer(width = (gap * 3 - 2).dp)
    Spacer(height = (half % 7 + gap).dp)
  }
}
