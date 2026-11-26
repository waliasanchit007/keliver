/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.compose.gatea.composeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.keliver.compose.foundation.clickable as guestClickable
import dev.keliver.compose.foundation.layout.fillMaxSize as guestFillMaxSize
import dev.keliver.compose.gatea.compose.ExtraRetained
import dev.keliver.compose.gatea.compose.ExtraRetainedEntry
import dev.keliver.compose.material3.Text as GuestText
import dev.keliver.compose.ui.Modifier as GuestModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NF5 R1's bench-only reveal ([RetainedReveal]) in the gate-A container: prepared entries hidden by
 * the stack's order, a click revealing one in the next frame before the guest runs, the reveal
 * ending when the guest shows the entry, and prepared entries measured while hidden.
 */
@OptIn(ExperimentalTestApi::class)
class RetainedRevealTest {
  private class Policy(
    override val revealOnClick: Boolean = true,
    override val measurePrepared: Boolean = false,
  ) : RetainedReveal {
    val events = mutableListOf<String>()

    override fun isPrepared(key: String) = key.startsWith("prep:") || key.startsWith("park:")

    override fun isRevealable(key: String) = key.startsWith("prep:")

    override fun onReveal(key: String) {
      events += "reveal $key"
    }

    override fun onRevealDrawn(key: String) {
      events += "drawn $key"
    }

    override fun onRevealLive(key: String) {
      events += "live $key"
    }
  }

  // The guest: a list entry whose row opens the detail, and the detail entry, prepared until opened.
  private val tap = EventTap()
  private var opened by mutableStateOf(false)
  private var detailKey by mutableStateOf("prep:detail")
  private var clicks = 0

  private fun ComposeUiTest.start(policy: RetainedReveal?): GuestOnHost {
    val host = guestOnHost(tap) {
      ExtraRetained(backHandled = 0, onBack = {}, modifier = GuestModifier.guestFillMaxSize()) {
        ExtraRetainedEntry(key = "list", hostBack = false, modifier = GuestModifier.guestFillMaxSize()) {
          GuestText(
            "open",
            GuestModifier.guestClickable {
              clicks++
              opened = true
            },
          )
        }
        ExtraRetainedEntry(key = if (opened) "shown:detail" else detailKey, hostBack = false, modifier = GuestModifier.guestFillMaxSize()) {
          GuestText("detail")
        }
      }
    }
    setContent {
      CompositionLocalProvider(LocalRetainedReveal provides policy) { host.root.Render(null) }
    }
    sync(host)
    return host
  }

  /** The guest's state changes reach the host: a guest frame, its batch, the host's frames. */
  private fun ComposeUiTest.sync(host: GuestOnHost) {
    repeat(3) {
      Snapshot.sendApplyNotifications()
      host.pump()
      waitForIdle()
    }
  }

  private fun ComposeUiTest.assertShows(text: String, vararg hidden: String) {
    onNodeWithText(text).assertIsDisplayed()
    for (h in hidden) onAllNodesWithText(h).assertCountEquals(0)
  }

  /** A click on "open", with the clock stopped: the up's events are handled, no frame has run. */
  private fun ComposeUiTest.clickOpen() {
    mainClock.autoAdvance = false
    onNodeWithText("open").performTouchInput { down(center) }
    onNodeWithText("open").performTouchInput { up() }
  }

  @Test
  fun aPreparedEntryIsNeverShownByTheStacksOrder() = runComposeUiTest {
    start(Policy())
    assertShows("open", "detail")
  }

  @Test
  fun withoutAPolicyTheLastEntryIsShownAsBefore() = runComposeUiTest {
    start(null)
    assertShows("detail", "open")
  }

  @Test
  fun aClickRevealsThePreparedEntryInTheNextFrameWithoutTheGuest() = runComposeUiTest {
    val policy = Policy()
    val host = start(policy)
    tap.hold = true
    val batches = host.wire.size

    clickOpen()
    assertEquals(listOf("reveal prep:detail"), policy.events, "revealed in the input event of the click")
    assertEquals(1, tap.sent.size, "the click itself went to the guest")
    mainClock.advanceTimeByFrame()
    assertShows("detail", "open")
    assertEquals(listOf("reveal prep:detail", "drawn prep:detail"), policy.events, "drawn in that frame")
    assertEquals(batches, host.wire.size, "no guest batch was needed")
    assertEquals(0, clicks, "the guest has not run")
    mainClock.autoAdvance = true

    // The guest runs the click: its own state now shows the entry, and the reveal is live.
    tap.release()
    sync(host)
    assertEquals(1, clicks)
    assertShows("detail", "open")
    assertEquals(listOf("reveal prep:detail", "drawn prep:detail", "live shown:detail"), policy.events)
  }

  @Test
  fun withoutRevealOnClickTheEntryWaitsForTheGuest() = runComposeUiTest {
    val policy = Policy(revealOnClick = false)
    val host = start(policy)
    tap.hold = true
    clickOpen()
    mainClock.advanceTimeByFrame()
    assertShows("open", "detail")
    mainClock.autoAdvance = true

    tap.release()
    sync(host)
    assertShows("detail", "open")
    assertEquals(emptyList(), policy.events)
  }

  @Test
  fun aParkedEntryStaysHiddenAndIsNotRevealed() = runComposeUiTest {
    detailKey = "park:detail"
    val policy = Policy()
    start(policy)
    tap.hold = true
    clickOpen()
    mainClock.advanceTimeByFrame()
    assertShows("open", "detail")
    assertEquals(emptyList(), policy.events)
    mainClock.autoAdvance = true
  }

  @Test
  fun oneRevealAtATime() = runComposeUiTest {
    val policy = Policy()
    start(policy)
    tap.hold = true
    clickOpen()
    mainClock.advanceTimeByFrame()
    // A second click (on the revealed entry, which this bench hook does not make inert) reveals nothing.
    onNodeWithText("detail").performTouchInput { down(center) }
    onNodeWithText("detail").performTouchInput { up() }
    mainClock.advanceTimeByFrame()
    assertEquals(listOf("reveal prep:detail", "drawn prep:detail"), policy.events)
    mainClock.autoAdvance = true
  }

  // Host-built stacks: the measure pass and the click observer, without a guest.

  /** A host widget that counts its measures and draws, and whose width the test can change. */
  private class Probe : dev.keliver.widget.Widget<@Composable (Modifier) -> Unit> {
    var measures = 0
    var draws = 0
    var width by mutableIntStateOf(10)
    override var modifier: dev.keliver.Modifier = dev.keliver.Modifier
    override val value: @Composable (Modifier) -> Unit = { incoming ->
      Box(
        incoming
          .layout { measurable, constraints ->
            measures++
            val placeable = measurable.measure(constraints)
            layout(width, 10) { placeable.place(0, 0) }
          }
          .drawBehind { draws++ },
      )
    }
  }

  /** A host widget: a scrolling column with one clickable row (a click or a scroll on it). */
  private class ScrollingRow : dev.keliver.widget.Widget<@Composable (Modifier) -> Unit> {
    var clicks = 0
    override var modifier: dev.keliver.Modifier = dev.keliver.Modifier
    override val value: @Composable (Modifier) -> Unit = { incoming ->
      Column(incoming.fillMaxSize().testTag("list").verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(2000.dp).testTag("row").clickable { clicks++ })
      }
    }
  }

  private fun entry(key: String, child: dev.keliver.widget.Widget<@Composable (Modifier) -> Unit>?) =
    ComposeUiExtraRetainedEntry().apply {
      key(key)
      hostBack(false)
      if (child != null) content.insert(0, child)
    }

  private fun hostStack(
    below: dev.keliver.widget.Widget<@Composable (Modifier) -> Unit>?,
    prepared: Probe,
  ): ComposeUiExtraRetained = ComposeUiExtraRetained().apply {
    onBack {}
    entries.insert(0, entry("list", below))
    entries.insert(1, entry("prep:detail", prepared))
  }

  @Test
  fun withMeasurePreparedThePreparedEntryIsMeasuredWhileHiddenAndTheRevealOnlyPlacesIt() = runComposeUiTest {
    val policy = Policy(measurePrepared = true)
    val probe = Probe()
    val stack = hostStack(null, probe)
    setContent { CompositionLocalProvider(LocalRetainedReveal provides policy) { stack.value(Modifier.fillMaxSize()) } }
    waitForIdle()
    assertTrue(probe.measures > 0, "measured while hidden")
    assertEquals(0, probe.draws, "never placed, so never drawn")

    // A change while hidden is measured in the frame it lands in, not at the reveal.
    val before = probe.measures
    runOnIdle { probe.width = 20 }
    waitForIdle()
    assertTrue(probe.measures > before, "the change was measured while hidden")
    assertEquals(0, probe.draws)

    val measured = probe.measures
    runOnIdle { assertTrue(stack.revealOnClick()) }
    waitForIdle()
    assertEquals(1, stack.shownIndex)
    assertEquals(measured, probe.measures, "the reveal's frame did not measure it again")
    assertTrue(probe.draws > 0, "the reveal's frame drew it")
    assertEquals(listOf("reveal prep:detail", "drawn prep:detail"), policy.events)
  }

  @Test
  fun withoutMeasurePreparedThePreparedEntryIsFirstMeasuredByTheReveal() = runComposeUiTest {
    val policy = Policy(measurePrepared = false)
    val probe = Probe()
    val stack = hostStack(null, probe)
    setContent { CompositionLocalProvider(LocalRetainedReveal provides policy) { stack.value(Modifier.fillMaxSize()) } }
    waitForIdle()
    assertEquals(0, probe.measures, "a hidden entry is not measured")
    runOnIdle { assertTrue(stack.revealOnClick()) }
    waitForIdle()
    assertTrue(probe.measures > 0, "measured in the reveal's frame")
    assertTrue(probe.draws > 0)
  }

  @Test
  fun aClickBelowRevealsAndAScrollDoesNot() = runComposeUiTest {
    val policy = Policy()
    val row = ScrollingRow()
    val stack = hostStack(row, Probe())
    setContent { CompositionLocalProvider(LocalRetainedReveal provides policy) { stack.value(Modifier.fillMaxSize()) } }
    waitForIdle()

    onNodeWithTag("list").performTouchInput { swipeUp() }
    waitForIdle()
    assertEquals(0, row.clicks, "the scroll took the gesture")
    assertEquals(emptyList(), policy.events, "a scroll reveals nothing")
    assertEquals(0, stack.shownIndex)

    // The column's center, on the visible part of the (tall) row.
    onNodeWithTag("list").performTouchInput { down(center) }
    onNodeWithTag("list").performTouchInput { up() }
    waitForIdle()
    assertEquals(1, row.clicks)
    assertEquals(listOf("reveal prep:detail", "drawn prep:detail"), policy.events)
    assertEquals(1, stack.shownIndex)
  }
}
