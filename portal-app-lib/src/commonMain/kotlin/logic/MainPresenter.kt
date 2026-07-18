package dev.keliver.portalpublished.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.cash.sqldelight.db.SqlDriver
import dev.keliver.portalpublished.screens.MainScreenBindings
import kotlinx.coroutines.launch

/**
 * HAND-OWNED (M6/M7): the presenter produces the screen's Bindings (Style B).
 * M7: taps persist in the HOST's SQLite through the guest-owned data layer —
 * state survives app restarts, and the whole layer shipped OTA.
 */
@Composable
fun MainPresenter(sql: SqlDriver?): MainScreenBindings {
  val scope = rememberCoroutineScope()
  var taps by remember { mutableStateOf(-1L) } // -1 = loading
  val store = remember { sql?.let { TapStore(it) } }

  LaunchedEffect(Unit) {
    if (store != null) {
      store.createTable()
      taps = store.count()
    } else {
      taps = 0
    }
  }

  return object : MainScreenBindings {
    override val text: String = when {
      taps < 0 -> "loading persisted taps…"
      taps == 0L -> "Compiled + SIGNED Kotlin from the portal"
      else -> "buyTapped ×$taps — persisted in SQLite via OTA data layer"
    }

    override fun buyTapped() {
      taps = maxOf(taps, 0) + 1
      scope.launch { store?.insert(at = taps.toString()) }
    }
  }
}
