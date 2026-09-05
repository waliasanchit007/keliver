package falsify.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.layout.compose.Spacer
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.ListItem
import dev.keliver.material.compose.StyledText
import dev.keliver.ui.Dp

@Composable
fun HomeScreen(b: HomeScreenBindings) {
  Column {
    // CONTROL: a correctly bound title.
    StyledText(
      text = b.title,
      fontSize = 28,
      bold = true,
    )

    // F2: literal that happens to equal what the presenter would produce.
    // Pixel-identical to a correct binding.
    StyledText(
      text = "3 notes",
      fontSize = 14,
    )

    Spacer(
      height = Dp(12.0),
    )

    // F5: branch whose Condition is mocked false in preview.
    if (b.showBanner) {
      StyledText(
        text = "You are offline",
        fontSize = 12,
      )
    }

    // F4: forEach over a list that is empty at runtime; preview shows mock rows.
    b.items.forEach { item ->
      ListItem(
        headline = item.title,
        leadingIcon = "Star",
      )
    }

    // F1: event the grammar cannot recognise -> preserved as RawCode, wired to nothing.
    Button(
      text = "Refresh",
      onClick = { if (b.canRefresh) b.refresh() },
    )
  }
}

interface HomeScreenBindings {
  val title: String
  val showBanner: Boolean
  val canRefresh: Boolean
  val items: List<Item>
  fun refresh()
}

interface Item {
  val title: String
}
