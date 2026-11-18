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

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.keliver.compose.BackHandler
import dev.keliver.compose.foundation.clickable as guestClickable
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.gatea.compose.ExtraRetained
import dev.keliver.compose.gatea.compose.ExtraRetainedEntry
import dev.keliver.compose.material3.Text as GuestText
import dev.keliver.compose.ui.Modifier as GuestModifier
import dev.keliver.nav.ExperimentalKeliverNavRetentionApi
import dev.keliver.nav.KeliverNavHostFrame
import dev.keliver.nav.KeliverNavRetention
import dev.keliver.nav.KeliverRetainedStack
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NF1, back on the host: keliver-nav's retained stack in the gate-A container (`ExtraRetained`),
 * guest and host joined by the real protocol (JSON), back presses through one dispatcher that, as
 * Android's, gives a press to the newest enabled callback (the host's and the guest's alike).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalKeliverNavRetentionApi::class)
class RetainedHostBackTest {
  /** The guest's frame on the container, as the three-way prototype's `retainhost` experiment has it. */
  private object HostFrame : KeliverNavHostFrame {
    @Composable
    override fun HostStack(backHandled: Int, onBack: (entryId: String) -> Unit, content: @Composable () -> Unit) {
      ExtraRetained(backHandled = backHandled, onBack = onBack, modifier = GuestModifier.fillMaxSize()) { content() }
    }

    @Composable
    override fun HostEntry(entryId: String, hostBack: Boolean, content: @Composable () -> Unit) {
      ExtraRetainedEntry(key = entryId, hostBack = hostBack, modifier = GuestModifier.fillMaxSize()) { content() }
    }

    @Composable
    override fun Stack(content: @Composable () -> Unit) = HostStack(0, {}, content)

    @Composable
    override fun Entry(visible: Boolean, content: @Composable () -> Unit) = HostEntry("", false, content)
  }

  private class BackDispatcher : HostBackDispatcher {
    val callbacks = mutableListOf<OnBackPressedCallback>()

    override fun onBackPressed() {
      press()
    }

    override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
      callbacks += onBackPressedCallback
      return object : Cancellable {
        override fun cancel() {
          callbacks -= onBackPressedCallback
        }
      }
    }

    fun press(): Boolean {
      val callback = callbacks.lastOrNull { it.isEnabled } ?: return false
      callback.handleOnBackPressed()
      return true
    }
  }

  private val back = BackDispatcher()
  private val tap = EventTap()
  private var keys by mutableStateOf(listOf("home"))
  private var handlerOn by mutableStateOf(false)
  private var guestHandled = 0
  private var homeClicks = 0

  private fun ComposeUiTest.start(): GuestOnHost {
    val host = guestOnHost(tap, back) {
      KeliverRetainedStack(
        keys,
        KeliverNavRetention(HostFrame),
        onHostBack = { key -> keys = keys.subList(0, keys.indexOf(key) + 1) },
      ) { key ->
        if (key == "home") {
          GuestText("open", GuestModifier.guestClickable { homeClicks++ })
        } else {
          BackHandler(enabled = handlerOn) { guestHandled++ }
          GuestText("$key screen")
        }
      }
    }
    setContent {
      CompositionLocalProvider(LocalHostBackDispatcher provides back) { host.root.Render(null) }
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

  private fun ComposeUiTest.push(host: GuestOnHost, vararg pushed: String) {
    for (key in pushed) {
      keys = keys + key
      sync(host)
    }
  }

  private fun ComposeUiTest.assertShows(text: String, vararg hidden: String) {
    onNodeWithText(text).assertIsDisplayed()
    // The merged tree, which accessibility services read: a hidden entry's semantics are cleared.
    for (h in hidden) onAllNodesWithText(h).assertCountEquals(0)
  }

  @Test
  fun backShowsTheRetainedEntryInTheFrameOfThePressThenTheGuestConfirms() = runComposeUiTest {
    val host = start()
    push(host, "detail")
    assertShows("detail screen", "open")

    tap.hold = true
    val batches = host.wire.size
    mainClock.autoAdvance = false
    runOnIdle { assertTrue(back.press(), "the host's callback takes the press") }
    mainClock.advanceTimeByFrame()
    assertShows("open", "detail screen")
    assertEquals(batches, host.wire.size, "no guest batch was needed")
    assertEquals(1, tap.sent.size, "the guest is told")
    mainClock.autoAdvance = true

    tap.release()
    sync(host)
    assertEquals(listOf("home"), keys, "the guest popped to the entry the host shows")
    assertShows("open", "detail screen")
  }

  @Test
  fun anEnabledGuestBackHandlerWins() = runComposeUiTest {
    handlerOn = true
    val host = start()
    push(host, "detail")
    runOnIdle { assertTrue(back.press()) }
    waitForIdle()
    assertEquals(1, guestHandled)
    assertEquals(0, tap.sent.size, "the host did not go back")
    assertShows("detail screen", "open")
    assertEquals(listOf("home", "detail"), keys)
  }

  @Test
  fun hiddenEntriesTakeNoInputAndExposeNoSemantics() = runComposeUiTest {
    val host = start()
    var openBounds: Rect? = null
    onNodeWithText("open").assertIsDisplayed().also { openBounds = it.fetchSemanticsNode().boundsInRoot }
    push(host, "detail")
    onAllNodesWithText("open").assertCountEquals(0)
    onRoot().performTouchInput { click(openBounds!!.center) }
    waitForIdle()
    sync(host)
    assertEquals(0, homeClicks, "a hidden entry is not hit")
    assertEquals(0, tap.sent.size)
  }

  @Test
  fun twoQuickBacksResolveWithoutAJump() = runComposeUiTest {
    val host = start()
    push(host, "b", "c")
    assertShows("c screen", "open", "b screen")
    tap.hold = true
    runOnIdle { assertTrue(back.press()) }
    waitForIdle()
    assertShows("b screen", "open", "c screen")
    runOnIdle { assertTrue(back.press()) }
    waitForIdle()
    assertShows("open", "b screen", "c screen")
    assertEquals(2, tap.sent.size)

    tap.release()
    sync(host)
    assertEquals(listOf("home"), keys)
    assertShows("open", "b screen", "c screen")
  }

  @Test
  fun backDuringAForwardNavigationKeepsWhatTheHostShows() = runComposeUiTest {
    val host = start()
    push(host, "b")
    tap.hold = true
    runOnIdle { assertTrue(back.press()) }
    waitForIdle()
    assertShows("open", "b screen")
    // The guest opens c before it hears of the press; its batch reaches the host first.
    push(host, "c")
    assertShows("open", "b screen", "c screen")

    tap.release()
    sync(host)
    assertEquals(listOf("home"), keys, "the guest resolves to what the host shows")
    assertShows("open", "b screen", "c screen")
  }

  @Test
  fun aGuestThatRefusesIsShownAgain() = runComposeUiTest {
    val host = start()
    push(host, "detail")
    // A handler enabled in the guest whose batch has not reached the host when back is pressed.
    handlerOn = true
    Snapshot.sendApplyNotifications()
    host.clock.sendFrame(0L)
    runOnIdle { assertTrue(back.press()) }
    waitForIdle()
    assertEquals(1, guestHandled, "the top's handler took the press in the guest")
    sync(host)
    assertEquals(listOf("home", "detail"), keys)
    assertShows("detail screen", "open")
  }

  /** A host-side focusable (the guest has no focus API yet) that records whether it has focus. */
  private class Focusable : dev.keliver.widget.Widget<@Composable (Modifier) -> Unit> {
    val requester = FocusRequester()
    var focused = false
    override var modifier: dev.keliver.Modifier = dev.keliver.Modifier
    override val value: @Composable (Modifier) -> Unit = { incoming ->
      Box(incoming.size(10.dp).focusRequester(requester).onFocusChanged { focused = it.isFocused }.focusable())
    }
  }

  private fun entry(
    key: String,
    child: dev.keliver.widget.Widget<@Composable (Modifier) -> Unit>?,
    hostBack: Boolean = true,
  ) = ComposeUiExtraRetainedEntry().apply {
    key(key)
    hostBack(hostBack)
    if (child != null) content.insert(0, child)
  }

  /** A host-side widget that records what its slot provides. */
  private class Probe : dev.keliver.widget.Widget<@Composable (Modifier) -> Unit> {
    var hidden: Boolean? = null
    var dispatcher: HostBackDispatcher? = null
    override var modifier: dev.keliver.Modifier = dev.keliver.Modifier
    override val value: @Composable (Modifier) -> Unit = {
      hidden = LocalRetainedHidden.current
      dispatcher = LocalHostBackDispatcher.current
    }
  }

  @Test
  fun aStackNestedInAHiddenEntryNeverTakesAPress() = runComposeUiTest {
    val innerSent = mutableListOf<String>()
    val inner = ComposeUiExtraRetained().apply { onBack { innerSent += it } }
    inner.entries.insert(0, entry("x", null))
    inner.entries.insert(1, entry("y", null))
    val outer = ComposeUiExtraRetained().apply { onBack {} }
    outer.entries.insert(0, entry("a", inner))
    setContent {
      CompositionLocalProvider(LocalHostBackDispatcher provides back) { outer.value(Modifier) }
    }
    waitForIdle()
    assertEquals(1, back.callbacks.size, "shown, the nested stack can go back")

    // b covers a, and the outer stack may not go back from b: no callback may take a press.
    runOnIdle { outer.entries.insert(1, entry("b", null, hostBack = false)) }
    waitForIdle()
    assertEquals(0, back.callbacks.size, "the hidden entry's nested stack removed its callback")
    // The nested stack changes while hidden (its callback would be added again, as the newest).
    runOnIdle { inner.entries.insert(2, entry("z", null)) }
    waitForIdle()
    runOnIdle { assertEquals(false, back.press(), "nothing takes the press") }
    assertEquals(emptyList(), innerSent)

    // Shown again, the nested stack takes presses.
    runOnIdle { outer.entries.remove(1, 1) }
    waitForIdle()
    runOnIdle { assertTrue(back.press()) }
    assertEquals(listOf("y"), innerSent)
  }

  @Test
  fun aSecondPressInTheFrameOfTheFirstIsPassedOn() = runComposeUiTest {
    var passedOn = 0
    // Registered first: an app's root handler, which the stack's callback is ahead of.
    back.addCallback(
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          passedOn++
        }
      },
    )
    val sent = mutableListOf<String>()
    val stack = ComposeUiExtraRetained().apply { onBack { sent += it } }
    stack.entries.insert(0, entry("a", null))
    stack.entries.insert(1, entry("b", null))
    setContent {
      CompositionLocalProvider(LocalHostBackDispatcher provides back) { stack.value(Modifier) }
    }
    waitForIdle()
    runOnIdle {
      // Both before the stack recomposes: the second finds it at its bottom entry.
      assertTrue(back.press())
      assertTrue(back.press())
    }
    assertEquals(listOf("a"), sent, "the first press went back on the host")
    assertEquals(1, passedOn, "the second was passed on, not swallowed")
  }

  @Test
  fun aStackTakesPressesOnlyWhenItCanShowTheEntryBelow() = runComposeUiTest {
    // goBack() fails only when canGoBack is false: an entry below with no key yet is not a place to
    // go back to, so no callback is added (before, one was, and it switched itself off for good).
    val sent = mutableListOf<String>()
    val stack = ComposeUiExtraRetained().apply { onBack { sent += it } }
    val below = ComposeUiExtraRetainedEntry().apply { hostBack(true) }
    stack.entries.insert(0, below)
    stack.entries.insert(1, entry("b", null))
    setContent {
      CompositionLocalProvider(LocalHostBackDispatcher provides back) { stack.value(Modifier) }
    }
    waitForIdle()
    assertEquals(false, stack.canGoBack)
    assertEquals(0, back.callbacks.size, "nothing to go back to: no callback")

    runOnIdle { below.key("a") }
    waitForIdle()
    assertEquals(1, back.callbacks.size, "the entry below has a key: the stack takes presses")
    runOnIdle { assertTrue(back.press()) }
    assertEquals(listOf("a"), sent)
  }

  @Test
  fun aHiddenEntryStopsHostAnimationsAndSeesNoDispatcher() = runComposeUiTest {
    val stack = ComposeUiExtraRetained().apply { onBack {} }
    val probe = Probe()
    stack.entries.insert(0, entry("a", probe))
    setContent {
      CompositionLocalProvider(LocalHostBackDispatcher provides back) { stack.value(Modifier) }
    }
    waitForIdle()
    assertEquals(false, probe.hidden)
    assertTrue(probe.dispatcher === back)
    runOnIdle { stack.entries.insert(1, entry("b", null)) }
    waitForIdle()
    assertEquals(true, probe.hidden, "the shimmer reads this and stops its transition")
    assertEquals(null, probe.dispatcher)
  }

  /** Whether anything in the composition waits for a frame (a running animation always does). */
  private fun framesAsked(): Boolean = Recomposer.runningRecomposers.value.any { it.hasPendingWork }

  @Test
  fun aHiddenShimmerAsksForNoFramesAndAnimatesAgainWhenShown() = runComposeUiTest {
    mainClock.autoAdvance = false
    val stack = ComposeUiExtraRetained().apply { onBack {} }
    stack.entries.insert(0, entry("a", ComposeUiExtraShimmer()))
    setContent { stack.value(Modifier) }
    mainClock.advanceTimeBy(500)
    assertTrue(framesAsked(), "shown, the shimmer animates")

    runOnUiThread { stack.entries.insert(1, entry("b", null)) }
    mainClock.advanceTimeBy(500)
    assertEquals(false, framesAsked(), "hidden, the shimmer asks for no frames")

    runOnUiThread { stack.entries.remove(1, 1) }
    mainClock.advanceTimeBy(500)
    assertTrue(framesAsked(), "shown again, it animates")
  }

  @Test
  fun hidingAnEntryDropsItsFocus() = runComposeUiTest {
    val stack = ComposeUiExtraRetained().apply { onBack {} }
    val field = Focusable()
    stack.entries.insert(0, entry("a", field))
    setContent { stack.value(Modifier) }
    runOnIdle { field.requester.requestFocus() }
    waitForIdle()
    assertTrue(field.focused)

    runOnIdle { stack.entries.insert(1, entry("b", null)) }
    waitForIdle()
    assertEquals(false, field.focused, "a hidden entry holds no focus")
    runOnIdle { field.requester.requestFocus() }
    waitForIdle()
    assertEquals(false, field.focused, "nor can it take focus")
  }

  @Test
  fun withoutAHostDispatcherBackStaysWithTheGuest() = runComposeUiTest {
    val host = guestOnHost(tap, back) {
      KeliverRetainedStack(keys, KeliverNavRetention(HostFrame), onHostBack = { keys = listOf("home") }) { key ->
        GuestText("$key screen")
      }
    }
    setContent { host.root.Render(null) }
    sync(host)
    push(host, "detail")
    assertEquals(false, back.press(), "no host callback without LocalHostBackDispatcher")
  }
}
