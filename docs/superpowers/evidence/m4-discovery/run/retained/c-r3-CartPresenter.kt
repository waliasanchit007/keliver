package shopcart.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import shopcart.screens.CartScreenBindings

private const val ITEM_PRICE_CENTS = 1200
private const val SHIPPING_CENTS = 500

private fun money(cents: Int): String =
  "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')

@Composable
fun CartPresenter(): CartScreenBindings {
  var items by remember { mutableStateOf(0) }
  val subtotalCents = items * ITEM_PRICE_CENTS
  val totalCents = if (items == 0) 0 else subtotalCents + SHIPPING_CENTS
  return object : CartScreenBindings {
    override val subtotal: String = money(subtotalCents)
    override val total: String = money(totalCents)
    override fun addItem() { items += 1 }
  }
}
