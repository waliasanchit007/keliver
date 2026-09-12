package ledger.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ledger.screens.HomeScreenBindings

/** HAND-OWNED: the app's real presenter. Unchanged between preview and device. */
@Composable
fun HomePresenter(): HomeScreenBindings {
  var entries by remember { mutableStateOf(0) }
  return object : HomeScreenBindings {
    override val summary: String = "$entries entries"
    override fun record() { entries += 1 }
  }
}
