package shopcart.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.Button
import dev.keliver.material.compose.StyledText

@Composable
fun CartScreen(b: CartScreenBindings) {
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
      text = b.subtotal,
      fontSize = 14,
    )
    Button(
      text = "Add item",
      onClick = { b.addItem() },
    )
  }
}

interface CartScreenBindings {
  val subtotal: String
  val total: String
  fun addItem()
}
