package inventory.logic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** HAND-OWNED. One stocked item. */
data class StockItem(
  val id: String,
  val name: String,
  val sku: String,
  val location: String,
  val quantity: Int,
  val reorderAt: Int,
) {
  val isLow: Boolean get() = quantity <= reorderAt
}

/**
 * The app's data. Deterministic and app-owned: no backend, no credentials, the
 * same eight items on every launch, so every run starts from the same state.
 */
object SeedInventory {
  val items: List<StockItem> = listOf(
    StockItem("bean-dark", "Espresso beans, dark roast 1kg", "BEAN-001", "Aisle 1", quantity = 12, reorderAt = 5),
    StockItem("bean-light", "Filter beans, light roast 1kg", "BEAN-002", "Aisle 1", quantity = 4, reorderAt = 5),
    StockItem("milk-oat", "Oat milk 1L", "MILK-010", "Cold room", quantity = 24, reorderAt = 10),
    StockItem("milk-whole", "Whole milk 2L", "MILK-011", "Cold room", quantity = 9, reorderAt = 10),
    StockItem("cup-8oz", "Paper cups 8oz (sleeve of 50)", "CUP-008", "Aisle 3", quantity = 30, reorderAt = 8),
    StockItem("cup-12oz", "Paper cups 12oz (sleeve of 50)", "CUP-012", "Aisle 3", quantity = 18, reorderAt = 8),
    StockItem("lid-8oz", "Lids 8oz (sleeve of 50)", "LID-008", "Aisle 3", quantity = 15, reorderAt = 8),
    StockItem("filter-v60", "V60 paper filters (100)", "FLT-060", "Aisle 2", quantity = 6, reorderAt = 3),
  )
}

/**
 * HAND-OWNED state. Snapshot state, so a screen reading it through its bindings
 * recomposes when it changes — on the device and in the Live preview alike.
 */
class InventoryState(seed: List<StockItem> = SeedInventory.items) {
  private val stock = mutableStateListOf<StockItem>().apply { addAll(seed) }
  private val history = mutableStateMapOf<String, List<Int>>()

  var query: String by mutableStateOf("")
    private set
  var selectedId: String? by mutableStateOf(null)
    private set

  val total: Int get() = stock.size
  val lowCount: Int get() = stock.count { it.isLow }

  fun visible(): List<StockItem> {
    val q = query.trim()
    if (q.isEmpty()) return stock.toList()
    return stock.filter { it.name.contains(q, ignoreCase = true) || it.sku.contains(q, ignoreCase = true) }
  }

  fun search(text: String) { query = text }
  fun clearSearch() { query = "" }

  fun open(id: String) { if (stock.any { it.id == id }) selectedId = id }
  fun back() { selectedId = null }

  fun selected(): StockItem? = selectedId?.let { id -> stock.firstOrNull { it.id == id } }
  fun adjustments(id: String): List<Int> = history[id].orEmpty()

  /** Changes the selected item's quantity. Never below zero; a no-op records nothing. */
  fun adjust(delta: Int) {
    val id = selectedId ?: return
    val i = stock.indexOfFirst { it.id == id }
    if (i < 0) return
    val current = stock[i]
    val next = (current.quantity + delta).coerceAtLeast(0)
    if (next == current.quantity) return
    stock[i] = current.copy(quantity = next)
    history[id] = history[id].orEmpty() + (next - current.quantity)
  }
}
