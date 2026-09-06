package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText

@Composable
fun M4CounterScreen(b: M4CounterBindings) {
  Column {
    StyledText(
      text = "Basket",
      fontSize = 20,
      bold = true,
    )
    StyledText(
      text = "0 items",
      fontSize = 14,
    )
    Button(
      text = "Add item",
      onClick = { b.addItem() },
    )
  }
}

interface M4CounterBindings {
  val summary: String
  fun addItem()
}
