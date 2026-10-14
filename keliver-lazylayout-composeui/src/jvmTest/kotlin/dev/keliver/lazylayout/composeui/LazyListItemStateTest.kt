/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.lazylayout.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.widget.Widget
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a lazy list item remembers belongs to its widget, not its index: when the guest replaces
 * the widget at an index (a placeholder filled in, a list re-sorted), the new one starts fresh.
 */
@OptIn(ExperimentalTestApi::class)
class LazyListItemStateTest {
  @Test fun itemStateFollowsTheWidgetNotItsIndex() = runComposeUiTest {
    val seen = mutableListOf<String>()
    val list = ComposeUiLazyList()
    list.isVertical(true)
    list.onViewportChanged { _, _ -> }
    list.items.insert(0, Remembering("first", seen))
    setContent { Box(Modifier.width(200.dp).height(300.dp)) { list.value(Modifier) } }
    waitForIdle()

    list.items.remove(0, 1)
    list.items.insert(0, Remembering("second", seen))
    waitForIdle()

    assertEquals(listOf("first", "second"), seen.distinct())
  }
}

/** Records what it remembered each time it composes. */
private class Remembering(
  private val name: String,
  private val seen: MutableList<String>,
) : Widget<@Composable (Modifier) -> Unit> {
  override var modifier: KeliverModifier = KeliverModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    val remembered = remember { name }
    seen += remembered
    Box(m.width(100.dp).height(100.dp))
  }
}
