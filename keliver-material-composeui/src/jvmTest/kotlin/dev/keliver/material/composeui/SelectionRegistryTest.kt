/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.material.modifier.SelectionTag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Canvas click-to-select. A project component's preview draws many widgets that all carry the
 * instance's handle, and a Repeat's rows all carry the template's: every one of them must stay
 * hit-testable. Keeping one rect per handle let the last widget laid out overwrite the others, so
 * a click on most of a component fell through to its parent.
 */
@OptIn(ExperimentalTestApi::class)
class SelectionRegistryTest {
  @AfterTest fun reset() = SelectionRegistry.clear()

  private fun tag(handle: Int): KeliverModifier = KeliverModifier.then(
    object : SelectionTag {
      override val handle: Int = handle
    },
  )

  @Test fun everyWidgetDrawingOneHandleIsHitTestable() = runComposeUiTest {
    // A parent (handle 1) holding an "instance" (handle 7) drawn by two widgets, one below the other.
    setContent {
      Column(Modifier.applyKeliverVisuals(tag(1)).size(300.dp, 400.dp)) {
        Box(Modifier.applyKeliverVisuals(tag(7)).size(200.dp, 50.dp))
        Box(Modifier.applyKeliverVisuals(tag(7)).size(200.dp, 50.dp))
      }
    }
    waitForIdle()
    assertEquals(7, SelectionRegistry.hitTest(10f, 10f), "the first widget of the instance")
    assertEquals(7, SelectionRegistry.hitTest(10f, 60f), "the second widget of the instance")
    assertEquals(1, SelectionRegistry.hitTest(250f, 10f), "outside the instance: its parent")
    assertEquals(2, SelectionRegistry.rectsOf(7).size)
    assertEquals(Rect(0f, 0f, 200f, 100f), SelectionRegistry.bounds[7], "the instance's bounds are the union")
  }

  @Test fun aWidgetLeavingTheCompositionWithdrawsItsRect() = runComposeUiTest {
    var second by mutableStateOf(true)
    setContent {
      Column(Modifier.size(300.dp, 400.dp)) {
        Box(Modifier.applyKeliverVisuals(tag(7)).size(200.dp, 50.dp))
        if (second) Box(Modifier.applyKeliverVisuals(tag(7)).size(200.dp, 50.dp))
      }
    }
    waitForIdle()
    assertEquals(7, SelectionRegistry.hitTest(10f, 60f))
    second = false
    waitForIdle()
    assertNull(SelectionRegistry.hitTest(10f, 60f), "a removed widget no longer answers a click")
    assertEquals(Rect(0f, 0f, 200f, 50f), SelectionRegistry.bounds[7])
  }

  @Test fun aRetaggedWidgetMovesToItsNewHandle() = runComposeUiTest {
    var h by mutableStateOf(7)
    setContent { Box(Modifier.applyKeliverVisuals(tag(h)).size(100.dp, 100.dp)) }
    waitForIdle()
    assertEquals(7, SelectionRegistry.hitTest(10f, 10f))
    h = 8
    waitForIdle()
    assertEquals(8, SelectionRegistry.hitTest(10f, 10f))
    assertNull(SelectionRegistry.bounds[7], "the old handle keeps no rect")
  }

  @Test fun theInnermostWidgetWinsAndTheLegacyReportStillWorks() {
    SelectionRegistry.report(1, Rect(0f, 0f, 100f, 100f))
    SelectionRegistry.report(2, Rect(10f, 10f, 20f, 20f))
    assertEquals(2, SelectionRegistry.hitTest(15f, 15f))
    assertEquals(1, SelectionRegistry.hitTest(50f, 50f))
    SelectionRegistry.report(2, Rect(30f, 30f, 40f, 40f))
    assertEquals(1, SelectionRegistry.rectsOf(2).size, "a report without a reporter replaces the handle's rect")
  }

  @Test fun anEmptyRectNeitherAnswersAClickNorStretchesTheBounds() {
    SelectionRegistry.report(7, "a", Rect(100f, 100f, 200f, 150f))
    SelectionRegistry.report(7, "unloaded image", Rect(0f, 0f, 0f, 0f))
    assertEquals(Rect(100f, 100f, 200f, 150f), SelectionRegistry.bounds[7])
    assertNull(SelectionRegistry.hitTest(0f, 0f))
  }

  @Test fun aWidgetScrolledOffScreenKeepsItsBoundsButAnswersNoClick() = runComposeUiTest {
    // A 300 dp tall window region; the second box sits below it, inside a scrolled column.
    setContent {
      Column(Modifier.size(300.dp, 300.dp).verticalScroll(rememberScrollState())) {
        Box(Modifier.size(200.dp, 300.dp))
        Box(Modifier.applyKeliverVisuals(tag(9)).size(200.dp, 100.dp))
      }
    }
    waitForIdle()
    assertEquals(Rect(0f, 300f, 200f, 400f), SelectionRegistry.bounds[9], "its full rect, below the fold")
    assertNull(SelectionRegistry.hitTest(10f, 350f), "an off-screen widget answers no click")
  }
}
