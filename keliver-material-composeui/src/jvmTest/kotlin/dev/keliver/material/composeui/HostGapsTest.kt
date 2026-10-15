/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.material.composeui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.keliver.Modifier as KeliverModifier
import dev.keliver.widget.Widget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gaps a production host had to patch around keliver-material (docs/ADOPTION_RETRO_2026-10.md).
 * The tests of what 0.3.3 got wrong fail on 0.3.3's widgets; the others pin behaviour kept or added
 * alongside (the dismiss contract, the dark branch, a bounded column still scrolling, an unsized
 * field keeping its width).
 */
@OptIn(ExperimentalTestApi::class)
class HostGapsTest {
  // BottomSheet

  @Test fun bottomSheetOpensFullyExpanded() = runComposeUiTest {
    val sheet = ComposeUiBottomSheet()
    sheet.children.insert(0, Probe("tall") { Box(it.fillMaxWidth().height(600.dp)) })
    sheet.visible(true)
    setContent { sheet.value(Modifier) }
    waitForIdle()

    // The test window is 768 dp tall at density 1. Half-expanded, a 600 dp sheet would start
    // half way down (≈ 384); fully expanded it starts near the top of the window.
    val top = onNodeWithTag("tall").getBoundsInRoot().top
    assertTrue(top < 250.dp, "the sheet's content starts at $top: it opened half-expanded")
  }

  @Test fun bottomSheetReopenedWhileSlidingOutComesBackFullyExpanded() = runComposeUiTest {
    val sheet = ComposeUiBottomSheet()
    sheet.children.insert(0, Probe("tall") { Box(it.fillMaxWidth().height(600.dp)) })
    sheet.visible(true)
    setContent { sheet.value(Modifier) }
    waitForIdle()
    val expandedTop = onNodeWithTag("tall").getBoundsInRoot().top

    mainClock.autoAdvance = false
    sheet.visible(false) // the guest closes it...
    mainClock.advanceTimeBy(100) // ...part-way through the slide-out...
    sheet.visible(true) // ...and opens it again
    mainClock.autoAdvance = true
    waitForIdle()

    assertEquals(expandedTop, onNodeWithTag("tall").getBoundsInRoot().top)
  }

  @Test fun bottomSheetPassesEveryDismissToTheGuest() {
    val sheet = ComposeUiBottomSheet()
    var dismisses = 0
    sheet.onDismiss { dismisses++ }
    sheet.visible(true)
    sheet.dismissed()
    sheet.dismissed() // the guest kept it open (a "discard changes?" confirm): Back still reaches it
    assertEquals(2, dismisses)
    assertTrue(sheet.visible) // the guest decides
  }

  @Test fun bottomSheetWithoutOnDismissClosesItself() {
    val sheet = ComposeUiBottomSheet()
    sheet.visible(true)
    sheet.dismissed()
    assertFalse(sheet.visible)
  }

  // Theme

  @Test fun themeKeepsTheHostsRolesTheGuestDoesNotSet() = runComposeUiTest {
    val host = lightColorScheme(secondaryContainer = Color(0xFFEDEDED), surfaceContainerLow = Color.White)
    val seen = mutableListOf<ColorScheme>()
    val theme = ComposeUiTheme()
    theme.primaryArgb(0xFF112233.toInt())
    theme.content.insert(0, Probe("probe") { seen += MaterialTheme.colorScheme })
    setContent { MaterialTheme(colorScheme = host) { theme.value(Modifier) } }
    waitForIdle()

    val scheme = seen.last()
    assertEquals(Color(0xFF112233), scheme.primary) // the guest's
    assertEquals(Color(0xFFEDEDED), scheme.secondaryContainer) // the host's, not Material purple
    assertEquals(Color.White, scheme.surfaceContainerLow)
  }

  @Test fun darkThemeOnALightHostStartsFromMaterialsDarkScheme() = runComposeUiTest {
    val seen = mutableListOf<ColorScheme>()
    val theme = ComposeUiTheme()
    theme.dark(true)
    theme.content.insert(0, Probe("probe") { seen += MaterialTheme.colorScheme })
    setContent {
      MaterialTheme(colorScheme = lightColorScheme(secondaryContainer = Color(0xFFEDEDED))) { theme.value(Modifier) }
    }
    waitForIdle()

    assertEquals(darkColorScheme().secondaryContainer, seen.last().secondaryContainer)
  }

  // ScrollableColumn

  @Test fun scrollableColumnInsideAScrollingParentDoesNotCrash() = runComposeUiTest {
    val column = ComposeUiScrollableColumn()
    column.children.insert(0, Probe("tall") { Box(it.fillMaxWidth().height(2000.dp)) })
    // 0.3.3 threw here: "Vertically scrollable component was measured with an infinity maximum
    // height constraints".
    setContent { Column(Modifier.verticalScroll(rememberScrollState())) { column.value(Modifier) } }
    onNodeWithTag("tall").assertExists()
  }

  @Test fun boundedScrollableColumnStillScrolls() = runComposeUiTest {
    val column = ComposeUiScrollableColumn()
    column.children.insert(0, Probe("tall") { Box(it.fillMaxWidth().height(2000.dp)) })
    setContent { Box(Modifier.width(200.dp).height(300.dp)) { column.value(Modifier) } }
    onNode(hasScrollAction()).assertExists()
  }

  // Text field alignment

  @Test fun outlinedTextFieldAlignsItsText() = runComposeUiTest {
    val start = ComposeUiOutlinedTextField().apply { text("42") }
    val center = ComposeUiOutlinedTextField().apply { text("42"); align(1) }
    setContent {
      Column(Modifier.width(300.dp)) {
        start.value(Modifier.fillMaxWidth().testTag("start"))
        center.value(Modifier.fillMaxWidth().testTag("center"))
      }
    }
    val startLeft = onNodeWithTag("start").firstLineLeft()
    val centerLeft = onNodeWithTag("center").firstLineLeft()
    assertTrue(centerLeft > startLeft + 50f, "centred text starts at $centerLeft, start-aligned at $startLeft")
  }

  @Test fun unsizedFieldKeepsItsWidthAtTheDefaultAlignment() = runComposeUiTest {
    val field = ComposeUiOutlinedTextField().apply { text(""); placeholder("Amount") }
    setContent { Box(Modifier.width(1000.dp)) { field.value(Modifier.testTag("field")) } }
    val width = onNodeWithTag("field").getBoundsInRoot().let { it.right - it.left }
    assertTrue(width < 500.dp, "an unsized, start-aligned field grew to $width")
  }

  @Test fun unsizedTextFieldKeepsItsWidthAtTheDefaultAlignment() = runComposeUiTest {
    val field = ComposeUiTextField().apply { text(""); placeholder("Amount") }
    setContent { Box(Modifier.width(1000.dp)) { field.value(Modifier.testTag("field")) } }
    val width = onNodeWithTag("field").getBoundsInRoot().let { it.right - it.left }
    assertTrue(width < 500.dp, "an unsized, start-aligned field grew to $width")
  }

  @Test fun textFieldAlignsItsText() = runComposeUiTest {
    val start = ComposeUiTextField().apply { text("42") }
    val end = ComposeUiTextField().apply { text("42"); align(2) }
    setContent {
      Column(Modifier.width(300.dp)) {
        start.value(Modifier.fillMaxWidth().testTag("start"))
        end.value(Modifier.fillMaxWidth().testTag("end"))
      }
    }
    val startLeft = onNodeWithTag("start").firstLineLeft()
    val endLeft = onNodeWithTag("end").firstLineLeft()
    assertTrue(endLeft > startLeft + 100f, "end-aligned text starts at $endLeft, start-aligned at $startLeft")
  }

  // Lazy lists and pagers: what an item remembers belongs to its widget, not its index.

  @Test fun lazyGridItemStateFollowsTheWidget() = runComposeUiTest {
    val seen = mutableListOf<String>()
    val grid = ComposeUiLazyVerticalGrid()
    grid.columns(1)
    grid.children.insert(0, Remembering("home", seen))
    setContent { Box(Modifier.width(200.dp).height(300.dp)) { grid.value(Modifier) } }
    waitForIdle()

    grid.children.remove(0, 1)
    grid.children.insert(0, Remembering("calculator", seen))
    waitForIdle()

    assertEquals(listOf("home", "calculator"), seen.distinct())
  }

  @Test fun pagerPageStateFollowsTheWidget() = runComposeUiTest {
    val seen = mutableListOf<String>()
    val pager = ComposeUiHorizontalPager()
    pager.children.insert(0, Remembering("first", seen))
    setContent { Box(Modifier.width(200.dp).height(300.dp)) { pager.value(Modifier) } }
    waitForIdle()

    pager.children.remove(0, 1)
    pager.children.insert(0, Remembering("second", seen))
    waitForIdle()

    assertEquals(listOf("first", "second"), seen.distinct())
  }
}

private fun SemanticsNodeInteraction.firstLineLeft(): Float {
  val layouts = mutableListOf<TextLayoutResult>()
  fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
  return layouts.first().getLineLeft(0)
}

/** A child widget that renders [content] under a test tag. */
private class Probe(
  private val tag: String,
  private val content: @Composable (Modifier) -> Unit,
) : Widget<@Composable (Modifier) -> Unit> {
  override var modifier: KeliverModifier = KeliverModifier
  override val value: @Composable (Modifier) -> Unit = { m -> content(m.testTag(tag)) }
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

