package inventory.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import inventory.screens.InventoryScreen
import inventory.screens.ItemScreen

/** HAND-OWNED navigation: the list, or the selected item's screen. */
@Composable
fun InventoryApp() {
  val state = remember { InventoryState() }
  if (state.selectedId == null) {
    InventoryScreen(InventoryPresenter(state))
  } else {
    ItemScreen(ItemPresenter(state))
  }
}
