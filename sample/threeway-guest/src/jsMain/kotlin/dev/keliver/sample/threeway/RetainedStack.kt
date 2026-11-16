/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.threeway

import androidx.compose.runtime.Composable
import dev.keliver.compose.foundation.layout.Box
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.foundation.layout.offset
import dev.keliver.compose.ui.Modifier
import dev.keliver.compose.ui.draw.alpha
import dev.keliver.compose.ui.unit.dp
import dev.keliver.nav.KeliverNavFrame
import dev.keliver.nav.KeliverNavRetention
import dev.keliver.nav.KeliverRetainedStack

// The guest's `retain` experiment (NF1, docs/PLAN_2026-11.md WS7): keliver-nav's retained stack with
// a frame built from the generated API's own widgets. Guest only: the native copy of
// ThreewayScreens.kt never sees this file.

/**
 * Entries overlap in a full-size box; a hidden entry keeps its size (so the list's viewport does not
 * change and nothing is re-laid out by the guest), is moved far outside the screen (so it gets no
 * input) and is fully transparent. Only the entry box's modifier changes when it is shown or hidden.
 */
internal object GateAFrame : KeliverNavFrame {
  private val Shown = Modifier.fillMaxSize()
  private val Hidden = Modifier.fillMaxSize().offset(x = 100_000.dp).alpha(0f)

  @Composable
  override fun Stack(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) { content() }
  }

  @Composable
  override fun Entry(visible: Boolean, content: @Composable () -> Unit) {
    Box(if (visible) Shown else Hidden) { content() }
  }
}

/** The app's two screens on keliver-nav's retained stack (depth 1: the list under one detail). */
internal object RetainedThreewayStack : ThreewayStack {
  private val retention = KeliverNavRetention(GateAFrame, depth = 1)

  @Composable
  override fun Show(keys: List<Any>, content: @Composable (Any) -> Unit) {
    KeliverRetainedStack(keys, retention, content)
  }
}
