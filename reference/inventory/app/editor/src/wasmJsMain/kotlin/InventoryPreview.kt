/*
 * Inventory's per-app preview entry: each portal screen name (the .kt basename
 * the relay ingests) runs the REAL hand-owned presenter over the same
 * deterministic InventoryState the device uses. Values are strings, as the
 * preview transport requires; the list goes through putRows.
 */
import androidx.compose.runtime.remember
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.ScreenPreview
import dev.keliver.portal.render.putRows
import inventory.logic.InventoryPresenter
import inventory.logic.InventoryState
import inventory.logic.ItemPresenter
import inventory.logic.SeedInventory

object InventoryPreview : AppPreviewEntry {
  override val label = "inventory (real presenters)"

  override val screens: Map<String, ScreenPreview> = mapOf(
    "inventory" to ScreenPreview { env ->
      val state = remember { InventoryState() }
      val b = InventoryPresenter(state)
      PreviewFrame(
        values = buildMap {
          put("query", b.query)
          put("summary", b.summary)
          put("isEmpty", b.isEmpty.toString())
          put("emptyMessage", b.emptyMessage)
          putRows("items", "item", b.items.map { mapOf("id" to it.id, "name" to it.name, "stockLine" to it.stockLine) })
        },
        dispatch = { action, arg ->
          when (action) {
            "search" -> b.search(arg.orEmpty())
            "clearSearch" -> b.clearSearch()
            // Navigation is the app root's job (logic/InventoryApp.kt); here it is logged.
            "open" -> env.log("→ open ${arg ?: "(no row id delivered)"}")
            else -> env.log("unhandled action: $action")
          }
        },
      )
    },
    "item" to ScreenPreview { env ->
      val state = remember { InventoryState().apply { open(SeedInventory.items.first().id) } }
      val b = ItemPresenter(state)
      PreviewFrame(
        values = mapOf(
          "name" to b.name,
          "details" to b.details,
          "quantityLabel" to b.quantityLabel,
          "isLowStock" to b.isLowStock.toString(),
          "lowStockWarning" to b.lowStockWarning,
          "historyLabel" to b.historyLabel,
        ),
        dispatch = { action, arg ->
          when (action) {
            "increment" -> b.increment()
            "decrement" -> b.decrement()
            "receive" -> b.receive(arg?.toIntOrNull() ?: 10)
            "back" -> env.log("→ back")
            else -> env.log("unhandled action: $action")
          }
        },
      )
    },
  )
}
