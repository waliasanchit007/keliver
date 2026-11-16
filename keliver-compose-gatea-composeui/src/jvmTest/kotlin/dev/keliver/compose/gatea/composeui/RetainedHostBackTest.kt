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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
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
import dev.keliver.ui.OnBackPressedDispatcher
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

  private class BackDispatcher : OnBackPressedDispatcher {
    val callbacks = mutableListOf<OnBackPressedCallback>()

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

  @Test
  fun hidingAnEntryDropsItsFocus() = runComposeUiTest {
    val stack = ComposeUiExtraRetained().apply { onBack {} }
    val field = Focusable()
    fun entry(key: String, child: dev.keliver.widget.Widget<@Composable (Modifier) -> Unit>?) =
      ComposeUiExtraRetainedEntry().apply {
        key(key)
        hostBack(true)
        if (child != null) content.insert(0, child)
      }
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
