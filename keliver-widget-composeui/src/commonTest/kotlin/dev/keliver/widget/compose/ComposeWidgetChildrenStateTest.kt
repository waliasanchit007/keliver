/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.widget.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.widget.Widget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * State a child remembers (a scroll position, a text field's value) belongs to that WIDGET, not
 * to its index: when the guest replaces the widget at an index (a new page, a new sheet view),
 * the new widget starts fresh. Without a key per widget, the calculator page opened at the
 * scroll position Home had left at the same index (found on a device, Stashfin Bonds).
 */
class ComposeWidgetChildrenStateTest {
  @Test fun rememberedStateFollowsTheWidgetNotItsIndex() = runTest {
    val clock = BroadcastFrameClock()
    val job = Job()
    val recomposer = Recomposer(coroutineContext + clock + job)
    launch(clock + job) { recomposer.runRecomposeAndApplyChanges() }

    val seen = mutableListOf<String>()
    val children = ComposeWidgetChildren()
    children.insert(0, StatefulWidget("home", seen))

    val composition = Composition(NoNodesApplier(), recomposer)
    try {
      composition.setContent { children.Render() }
      assertEquals(listOf("home"), seen)

      // The guest navigates: the widget at index 0 is replaced by a different one.
      children.remove(0, 1)
      children.insert(0, StatefulWidget("calculator", seen))
      Snapshot.sendApplyNotifications()
      repeat(5) { clock.sendFrame(0L); yield() }

      assertEquals(listOf("home", "calculator"), seen)
    } finally {
      composition.dispose()
      job.cancel()
    }
  }
}

/** Records what it remembered on each composition. */
private class StatefulWidget(
  private val name: String,
  private val seen: MutableList<String>,
) : Widget<@Composable (Modifier) -> Unit> {
  override var modifier: KeliverModifier = KeliverModifier
  override val value: @Composable (Modifier) -> Unit = {
    val remembered = remember { name }
    seen += remembered
  }
}

private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
  override fun insertBottomUp(index: Int, instance: Unit) = throw AssertionError()
  override fun insertTopDown(index: Int, instance: Unit) = throw AssertionError()
  override fun move(from: Int, to: Int, count: Int) = throw AssertionError()
  override fun remove(index: Int, count: Int) = throw AssertionError()
  override fun onClear() {}
}
