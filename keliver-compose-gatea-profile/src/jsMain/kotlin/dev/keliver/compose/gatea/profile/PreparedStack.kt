/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(ExperimentalKeliverNavRetentionApi::class)

package dev.keliver.compose.gatea.profile

import androidx.compose.runtime.Composable
import dev.keliver.nav.ExperimentalKeliverNavRetentionApi
import dev.keliver.nav.KeliverNavPreparation
import dev.keliver.nav.KeliverPreparedStack
import dev.keliver.sample.threeway.GateAFrame
import dev.keliver.sample.threeway.ThreewayStack

/**
 * NF3's pre-composition in the profile (`stack=prepared`): the three-way app's screens on
 * keliver-nav's [KeliverPreparedStack], with the retained stack's frame (RetainedStack.kt). The
 * driver's `press` step calls [preparation] as a row's touch-down handler would; the `open` step's
 * click then pushes the same item. [depth] 1 also keeps the list composed under the detail (as
 * `retain=1`), 0 composes only the top (as shipped, but inside the stack's frame).
 */
internal class PreparedThreewayStack(private val depth: Int) : ThreewayStack {
  val preparation = KeliverNavPreparation<Any>()

  @Composable
  override fun Show(keys: List<Any>, content: @Composable (Any) -> Unit) {
    KeliverPreparedStack(keys, preparation, GateAFrame, depth, content)
  }
}
