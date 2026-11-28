/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.gatea.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import dev.keliver.compose.BackHandler
import dev.keliver.compose.ui.Modifier
import dev.keliver.sample.threeway.GateAFrame
import dev.keliver.sample.threeway.ThreewayStack

/**
 * NF5's go/no-go measurement (docs/SHOW_BEFORE_COMPUTE.md, appendix): what opening a detail costs
 * when a detail is already composed, hidden, with other arguments. A measurement stand-in, not a
 * design: the real stack would be keliver-nav's. All three variants keep the list composed under
 * the detail in [GateAFrame]'s entries (as `retain=1`), so only the detail's path differs:
 *
 *  - [Variant.FRESH]: the detail is composed on the tap and disposed on back (today's open on a
 *    retained stack).
 *  - [Variant.REBIND]: one detail slot, keyed by its kind, stays composed and hidden; the tap
 *    changes its argument (the item) and shows it, so the detail RECOMPOSES with new arguments.
 *  - [Variant.SHOWN]: the same slot, but the driver's `nf5prepare` step sets the argument before
 *    the tap (timed on its own); the tap then only shows it (the floor: what a retained back pays,
 *    plus the detail's "opened N×" line, which reads the app's navigation counter).
 */
internal class Nf5Stack(private val variant: Variant) : ThreewayStack {
  enum class Variant { FRESH, REBIND, SHOWN }

  /** The hidden slot's argument while no detail is shown (REBIND, SHOWN). */
  private val hiddenArg = mutableStateOf<Any?>(null)

  /** Gives the hidden slot [item] (the driver's `nf5prepare`, and the slot's first argument). */
  fun prepare(item: Any) {
    hiddenArg.value = item
  }

  @Composable
  override fun Show(keys: List<Any>, next: Any?, onBack: () -> Unit, content: @Composable (Any) -> Unit) {
    BackHandler(enabled = keys.size > 1) { onBack() }
    val list = keys[0]
    val top = keys.getOrNull(1)
    GateAFrame.Stack {
      key(list) { GateAFrame.Entry(visible = top == null) { content(list) } }
      if (variant == Variant.FRESH) {
        if (top != null) key(top) { GateAFrame.Entry(visible = true) { content(top) } }
      } else {
        // `hiddenArg` is read only while nothing is shown, so keeping it in step below does not
        // invalidate this scope while a detail is shown.
        val arg = top ?: hiddenArg.value
        if (top != null) SideEffect { hiddenArg.value = top }
        if (arg != null) key(SLOT) { GateAFrame.Entry(visible = top != null) { content(arg) } }
      }
    }
  }

  @Composable
  override fun rowPress(key: Any): Modifier = Modifier

  private companion object {
    const val SLOT = "nf5-detail-slot"
  }
}
