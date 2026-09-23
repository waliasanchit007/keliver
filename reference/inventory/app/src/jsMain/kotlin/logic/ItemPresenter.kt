package inventory.logic

import androidx.compose.runtime.Composable
import inventory.screens.ItemScreenBindings

/** HAND-OWNED: ItemScreen's bindings for the selected item in [state]. */
@Composable
fun ItemPresenter(state: InventoryState): ItemScreenBindings = object : ItemScreenBindings {
  private val item: StockItem? get() = state.selected()

  override val name: String get() = item?.name ?: "No item selected"
  override val details: String get() = item?.let { "${it.sku} · ${it.location}" } ?: ""
  override val quantityLabel: String get() = "On hand: ${item?.quantity ?: 0}"
  override val isLowStock: Boolean get() = item?.isLow ?: false
  override val lowStockWarning: String get() = "Low stock — reorder at ${item?.reorderAt ?: 0}"
  override val historyLabel: String get() {
    val moves = item?.let { state.adjustments(it.id) }.orEmpty()
    if (moves.isEmpty()) return "No adjustments yet"
    val net = moves.sum()
    val signed = moves.joinToString(", ") { if (it > 0) "+$it" else "$it" }
    return "${moves.size} adjustments: $signed (net ${if (net > 0) "+$net" else "$net"})"
  }

  override fun back() = state.back()
  override fun decrement() = state.adjust(-1)
  override fun increment() = state.adjust(+1)
  override fun receive(amount: Int) = state.adjust(amount)
}
