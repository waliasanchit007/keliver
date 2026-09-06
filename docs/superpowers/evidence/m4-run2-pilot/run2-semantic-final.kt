package basket.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText

@Composable
fun HomeScreen(b: HomeScreenBindings) {
  Column {
    StyledText(
      text = "Basket",
      fontSize = 20,
      bold = true,
    )
    StyledText(
      text = b.summary,
      fontSize = 14,
    )
    Button(
      text = "Add item",
      onClick = { b.addItem() },
    )
  }
}

interface HomeScreenBindings {
  val summary: String
  fun addItem()
}
