/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.threeway

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.compose.BackHandler
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.gatea.compose.ExtraRetained
import dev.keliver.compose.gatea.compose.ExtraRetainedEntry
import dev.keliver.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * NF5 R1 (`r1prep`; docs/nf5-r1/README.md, docs/SHOW_BEFORE_COMPUTE.md section 7), benchmark-only:
 * the app's two screens in the host's retained container (gate A's `ExtraRetained`), with the
 * detail of the next open composed ahead of the tap, hidden.
 *
 * The container holds two entries: the list ([R1Keys.LIST]) and one detail entry, which is composed
 * once and then only rebound. While the detail is open its key is [R1Keys.SHOWN]. After a back it
 * keeps the item it showed, under [R1Keys.PARKED] (hidden, not revealable); [IDLE_MILLIS] later the
 * stack binds it to the item the next open will show ([Show]'s `next`) and marks it
 * [R1Keys.PREPARED]: hidden, and revealable by the host. The key and the item change in one
 * composition, so they reach the host in one batch. The host's reveal policy (the sample host's
 * `R1Reveal`) never shows a prepared or parked entry by the stack's order. With the `reveal`
 * experiment it shows the prepared detail itself on the click, in the click's frame, before this
 * guest runs; the guest's click then shows it by its own state (the key becomes [R1Keys.SHOWN]),
 * which ends the reveal. Without `reveal` the host waits for that, as for any navigation.
 *
 * What this leaves out of (B)'s design (3.2, 3.4): no shell (the prepared detail is the full detail
 * of the predicted item, so the reveal shows final content and the guest's click only recomposes
 * the detail's counter, series H's floor step), no reveal token or answer, and "idle" is a fixed
 * delay after the back rather than 300 ms without input or guest frames. Back goes through the
 * guest (the header's button, or the system back by this stack's `BackHandler`).
 */
internal object RevealThreewayStack : ThreewayStack {
  /** After a back, how long the detail stays parked before it is bound to the next open's item. */
  private const val IDLE_MILLIS = 300L

  private val Fill = Modifier.fillMaxSize()

  override val preparesNext: Boolean get() = true

  @Composable
  override fun Show(keys: List<Any>, next: Any?, onBack: () -> Unit, content: @Composable (Any) -> Unit) {
    BackHandler(enabled = keys.size > 1) { onBack() }
    val shown = keys.getOrNull(1)
    // The detail entry's item while hidden, and whether the idle preparation bound it to `next`.
    // Read only while no detail is shown, so the writes below never recompose an open detail.
    var bound by remember { mutableStateOf<Any?>(null) }
    var prepared by remember { mutableStateOf(false) }
    LaunchedEffect(shown, next) {
      if (shown != null) {
        // Open: after the next back the entry keeps this item, parked.
        bound = shown
        prepared = false
      } else if (next != null && !(prepared && bound == next)) {
        delay(IDLE_MILLIS)
        bound = next
        prepared = true
      }
    }
    ExtraRetained(backHandled = 0, onBack = {}, modifier = Fill) {
      ExtraRetainedEntry(key = R1Keys.LIST, hostBack = false, modifier = Fill) { content(keys[0]) }
      val item = shown ?: bound
      if (item != null) {
        val key = when {
          shown != null -> R1Keys.SHOWN
          prepared -> R1Keys.PREPARED
          else -> R1Keys.PARKED
        }
        // A hidden detail does not report `composed` (LocalDetailHidden): only the shown one does.
        ExtraRetainedEntry(key = key, hostBack = false, modifier = Fill) {
          CompositionLocalProvider(LocalDetailHidden provides (shown == null)) { content(item) }
        }
      }
    }
  }

  @Composable
  override fun rowPress(key: Any): Modifier = Modifier
}
