package inventory.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Row
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp

/** One item: its stock level, a low-stock warning, and quantity adjustments. */
@Composable
fun ItemScreen(b: ItemScreenBindings) {
  Column {
    Button(
      text = "Back to inventory",
      onClick = { b.back() },
    )
    StyledText(
      text = b.name,
      fontSize = 24,
      bold = true,
    )
    StyledText(
      text = b.details,
      fontSize = 14,
    )
    Spacer(
      height = Dp(12.0),
    )
    StyledText(
      text = b.quantityLabel,
      fontSize = 20,
      bold = true,
    )
    if (b.isLowStock) {
      StyledText(
        text = b.lowStockWarning,
        fontSize = 14,
        bold = true,
      )
    }
    Row {
      Button(
        text = "Remove 1",
        onClick = { b.decrement() },
      )
      Button(
        text = "Add 1",
        onClick = { b.increment() },
      )
    }
    Button(
      text = "Receive 10",
      onClick = { b.receive(10) },
    )
    StyledText(
      text = b.historyLabel,
      fontSize = 12,
    )
  }
}

/** The round-trip boundary: implemented by hand in logic/; the portal never touches it. */
interface ItemScreenBindings {
  val name: String
  val details: String
  val quantityLabel: String
  val isLowStock: Boolean
  val lowStockWarning: String
  val historyLabel: String
  fun back()
  fun decrement()
  fun increment()
  fun receive(amount: Int)
}
