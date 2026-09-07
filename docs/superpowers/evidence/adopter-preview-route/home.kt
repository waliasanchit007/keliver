package tally.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText

@Composable
fun HomeScreen(b: HomeScreenBindings) {
  Column {
    StyledText(
      text = "Tally",
      fontSize = 24,
      bold = true,
    )
    StyledText(
      text = b.tally,
      fontSize = 16,
    )
    Button(
      text = "Add one",
      onClick = { b.add() },
    )
  }
}

interface HomeScreenBindings {
  val tally: String
  fun add()
}
