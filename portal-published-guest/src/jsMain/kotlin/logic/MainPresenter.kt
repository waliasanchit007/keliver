package dev.keliver.portalpublished.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.portalpublished.screens.MainScreenBindings

/**
 * HAND-OWNED (M6 app project model, design §8): the presenter produces the
 * screen's Bindings — this is keliver Style B with the portal's generated
 * contract as the Model+events. The portal never touches logic/.
 */
@Composable
fun MainPresenter(): MainScreenBindings {
  var taps by remember { mutableStateOf(0) }
  return object : MainScreenBindings {
    override val text: String =
      if (taps == 0) "Compiled + SIGNED Kotlin from the portal" else "buyTapped ×$taps — live presenter state"

    override fun buyTapped() {
      taps++
    }
  }
}
