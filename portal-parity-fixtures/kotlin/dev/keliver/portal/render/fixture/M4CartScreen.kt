package dev.keliver.portal.render.fixture

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText

@Composable
fun M4CartScreen(b: M4CartBindings) {
  Column {
    StyledText(
      text = "Cart",
      fontSize = 20,
      bold = true,
    )
    StyledText(
      text = "Subtotal",
      fontSize = 12,
    )
    StyledText(
      text = b.subtotal,
      fontSize = 14,
    )
    StyledText(
      text = "Total",
      fontSize = 12,
    )
    StyledText(
      text = b.total,
      fontSize = 14,
    )
    Button(
      text = "Add item",
      onClick = { b.addItem() },
    )
  }
}

interface M4CartBindings {
  val subtotal: String
  val total: String
  fun addItem()
}
