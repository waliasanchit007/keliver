/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(ExperimentalKeliverNavRetentionApi::class)

package dev.keliver.compose.gatea.profile

import androidx.compose.runtime.Composable
import dev.keliver.compose.BackHandler
import dev.keliver.compose.ui.Modifier
import dev.keliver.nav.ExperimentalKeliverNavRetentionApi
import dev.keliver.nav.KeliverNavPreparation
import dev.keliver.nav.KeliverPreparedStack
import dev.keliver.nav.prepareOnPress
import dev.keliver.sample.threeway.GateAFrame
import dev.keliver.sample.threeway.ThreewayStack

/**
 * NF3's pre-composition in the profile (`prepared=0|1`): the three-way app's screens on
 * keliver-nav's [KeliverPreparedStack], with the retained stack's frame (RetainedStack.kt). Each row
 * carries `prepareOnPress`, so with `press=1` (the host's press signal declared and provided) the
 * driver's `press` step sends the row's press event, as a host does on touch-down, and the `open`
 * step's click then pushes the same item. [depth] 1 also keeps the list composed under the detail
 * (as `retain=1`), 0 composes only the top (as shipped, but inside the stack's frame).
 */
internal class PreparedThreewayStack(private val depth: Int) : ThreewayStack {
  val preparation = KeliverNavPreparation<Any>()

  @Composable
  override fun Show(keys: List<Any>, onBack: () -> Unit, content: @Composable (Any) -> Unit) {
    // System back through the guest, as RetainedThreewayStack does, so the two stacks compare alike.
    BackHandler(enabled = keys.size > 1) { onBack() }
    KeliverPreparedStack(keys, preparation, GateAFrame, depth, content)
  }

  @Composable
  override fun rowPress(key: Any): Modifier = Modifier.prepareOnPress(preparation, key)
}
