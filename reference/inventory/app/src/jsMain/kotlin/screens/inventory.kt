package inventory.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.ListItem
import dev.keliver.material.compose.StyledText
import dev.keliver.material.compose.TextField
import dev.keliver.ui.Dp

/** The stock list: search, an empty state, and one row per matching item. */
@Composable
fun InventoryScreen(b: InventoryScreenBindings) {
  Column {
    StyledText(
      text = "Inventory",
      fontSize = 28,
      bold = true,
    )
    TextField(
      text = b.query,
      placeholder = "Search by name or SKU",
      onValueChange = { b.search(it) },
    )
    StyledText(
      text = b.summary,
      fontSize = 14,
    )
    Spacer(
      height = Dp(8.0),
    )
    if (b.isEmpty) {
      StyledText(
        text = b.emptyMessage,
        fontSize = 16,
      )
      Button(
        text = "Clear search",
        onClick = { b.clearSearch() },
      )
    }
    b.items.forEach { item ->
      ListItem(
        headline = item.name,
        supporting = item.stockLine,
        trailingIcon = "KeyboardArrowRight",
        onClick = { b.open(item.id) },
      )
    }
  }
}

/** The round-trip boundary: implemented by hand in logic/; the portal never touches it. */
interface InventoryScreenBindings {
  val query: String
  val summary: String
  val isEmpty: Boolean
  val emptyMessage: String
  val items: List<StockRow>
  fun search(query: String)
  fun clearSearch()
  fun open(id: String)
}

interface StockRow {
  val id: String
  val name: String
  val stockLine: String
}
