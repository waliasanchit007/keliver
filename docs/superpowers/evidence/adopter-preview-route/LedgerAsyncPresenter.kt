package ledger.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.localStorage
import kotlinx.coroutines.delay
import ledger.screens.HomeScreenBindings

/**
 * HAND-OWNED: produce HomeScreen's bindings.
 *
 * This presenter deliberately mixes the two ways its state moves:
 *
 *  - [HomeScreenBindings.tally] changes synchronously, inside the action.
 *  - [HomeScreenBindings.status] changes from a coroutine, with no action
 *    behind the first one at all. The coroutine waits for a signal the test
 *    harness sets, so the moment of completion is chosen by the harness rather
 *    than by a timer — the presenter itself is idle until then.
 */
@Composable
fun HomePresenter(): HomeScreenBindings {
  var status by remember { mutableStateOf("Loading…") }
  var count by remember { mutableStateOf(0) }
  var refreshes by remember { mutableStateOf(0) }

  // Async work nobody asked for: it starts with the screen.
  LaunchedEffect(Unit) {
    awaitRelease("ledgerStart")
    status = "loaded 42 entries"
  }

  // Async work an ACTION starts, completing long after the action rendered.
  LaunchedEffect(refreshes) {
    if (refreshes > 0) {
      status = "refreshing…"
      awaitRelease("ledgerRefresh$refreshes")
      status = "refreshed $refreshes"
    }
  }

  return object : HomeScreenBindings {
    override val status: String = status
    override val tally: String = "$count entries"
    override fun add() { count += 1 }
    override fun refresh() { refreshes += 1 }
  }
}

/**
 * Wait for the harness. Nothing here is timed: the presenter stays idle until
 * the browser is told to release it, so "the host went idle and then the value
 * arrived" is something the test decides, not something it races.
 */
private suspend fun awaitRelease(key: String) {
  while (localStorage.getItem(key) == null) delay(50)
}
