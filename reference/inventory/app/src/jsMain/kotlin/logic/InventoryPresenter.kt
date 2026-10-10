package inventory.logic

import androidx.compose.runtime.Composable
import inventory.screens.InventoryScreenBindings
import inventory.screens.StockRow

/** HAND-OWNED: InventoryScreen's bindings, read live from [state]. */
@Composable
fun InventoryPresenter(state: InventoryState): InventoryScreenBindings = object : InventoryScreenBindings {
  override val query: String get() = state.query
  override val items: List<StockRow> get() = state.visible().map { it.toRow() }
  override val isEmpty: Boolean get() = state.visible().isEmpty()
  override val emptyMessage: String get() = "No items match \"${state.query.trim()}\"."
  override val summary: String get() {
    val shown = state.visible().size
    return if (state.query.isBlank()) {
      "${state.total} items · ${state.lowCount} low on stock"
    } else {
      "$shown of ${state.total} items match \"${state.query.trim()}\""
    }
  }
  override fun search(query: String) = state.search(query)
  override fun clearSearch() = state.clearSearch()
  override fun open(id: String) = state.open(id)
}

internal fun StockItem.toRow(): StockRow {
  val item = this
  return object : StockRow {
    override val id = item.id
    override val name = item.name
    override val stockLine = "${item.sku} · ${item.quantity} on hand" + if (item.isLow) " · LOW" else ""
  }
}
