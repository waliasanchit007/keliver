package tally.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tally.screens.HomeScreenBindings

/** HAND-OWNED: the app's real presenter. Holds the count and mutates it. */
@Composable
fun HomePresenter(): HomeScreenBindings {
  var count by remember { mutableStateOf(0) }
  return object : HomeScreenBindings {
    override val tally: String = "$count tallied"
    override fun add() { count += 1 }
  }
}
