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
@file:OptIn(dev.keliver.compose.KeliverExperimentalApi::class)

package dev.keliver.compose.core.composeui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.LocalHostFeatures
import dev.keliver.compose.core.compose.ExtraRetained
import dev.keliver.compose.core.compose.ExtraRetainedEntry
import dev.keliver.compose.extras.PRESS_HOST_FEATURE as GUEST_PRESS_FEATURE
import dev.keliver.compose.extras.pressSignal
import dev.keliver.compose.foundation.clickable as guestClickable
import dev.keliver.compose.foundation.layout.Box
import dev.keliver.compose.foundation.layout.Column
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.foundation.layout.height
import dev.keliver.compose.foundation.layout.width
import dev.keliver.compose.foundation.rememberScrollState
import dev.keliver.compose.foundation.verticalScroll
import dev.keliver.compose.material3.Text as GuestText
import dev.keliver.compose.ui.Modifier as GuestModifier
import dev.keliver.compose.ui.unit.dp
import dev.keliver.nav.ExperimentalKeliverNavRetentionApi
import dev.keliver.nav.KeliverNavController
import dev.keliver.nav.KeliverNavHost
import dev.keliver.nav.KeliverNavHostFrame
import dev.keliver.nav.KeliverNavPress
import dev.keliver.nav.KeliverNavRetention
import dev.keliver.nav.LocalKeliverNavPress
import dev.keliver.nav.prepareOnPress
import dev.keliver.nav.rememberKeliverNavController
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ExtraPress`'s second property, as the protocol's JSON names it: only that modifier has it. */
private const val PRESS_ON_WIRE = "\"onCancel\""

/**
 * NF3, the host's press signal: the core group's `ExtraPress` on a row, guest and host joined by the
 * real protocol (JSON). The host sends the press on touch-down, before the click, and the cancel when the
 * gesture ends (after the click, if any); keliver-nav composes the screen the row opens while the
 * finger is down (hidden, under the retained container's shown entry), and the click shows that
 * composition. [log] is what the guest received, in order. A host without the feature never
 * receives the modifier, and the screen is composed at the click (D2).
 *
 * The product's copy (docs/MS4_PLAN.md, MS4-02) of the prototype's test, on the generated root.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalKeliverNavRetentionApi::class, ExperimentalKeliverHostFeaturesApi::class)
class PressSignalTest {
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

  /** As Android's: a press goes to the newest enabled callback. */
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

  private val log = mutableListOf<String>()

  /** The core group's press signal for keliver-nav, as an app provides it at its root; it logs what arrives. */
  private val corePress = object : KeliverNavPress {
    override val hostFeature: String = GUEST_PRESS_FEATURE

    override fun pressSignal(modifier: dev.keliver.Modifier, onPress: () -> Unit, onCancel: () -> Unit) =
      modifier.pressSignal(
        onPress = {
          log += "press"
          onPress()
        },
        onCancel = {
          log += "cancel"
          onCancel()
        },
      )
  }

  /** The row on "home" (or "list"): what its click does. */
  private enum class RowKind { Navigates, Disabled, DoesNotNavigate, InnerButton, InScroll }

  private val tap = EventTap()
  private val back = BackDispatcher()
  private val compositions = mutableMapOf<String, Int>()
  private val disposed = mutableListOf<String>()
  private lateinit var nav: KeliverNavController<String>

  private fun ComposeUiTest.start(
    hostFeatures: Set<String>,
    kind: RowKind = RowKind.Navigates,
    rowOn: String = "home",
  ): GuestOnHost {
    val host = guestOnHost(tap, back) {
      CompositionLocalProvider(LocalHostFeatures provides hostFeatures, LocalKeliverNavPress provides corePress) {
        val controller = rememberKeliverNavController("home")
        nav = controller
        KeliverNavHost(controller, KeliverNavRetention(HostFrame, depth = 1)) { route ->
          compositions[route] = (compositions[route] ?: 0) + 1
          DisposableEffect(route) { onDispose { disposed += route } }
          when {
            route == rowOn -> Row(controller, kind)
            route == "home" -> GuestText("to list", GuestModifier.guestClickable { controller.navigate("list") })
            else -> GuestText("$route screen")
          }
        }
      }
    }
    setContent { CompositionLocalProvider(LocalHostBackDispatcher provides back) { host.root.Render(null) } }
    sync(host)
    return host
  }

  @Composable
  private fun Row(controller: KeliverNavController<String>, kind: RowKind) {
    val clickable = GuestModifier.guestClickable(enabled = kind != RowKind.Disabled) {
      log += "click"
      if (kind != RowKind.DoesNotNavigate) controller.navigate("detail")
    }
    when (kind) {
      RowKind.InnerButton -> Box(clickable.prepareOnPress(controller, "detail")) {
        GuestText("open")
        GuestText("share", GuestModifier.width(40.dp).guestClickable { log += "share" })
      }
      RowKind.InScroll -> Column(GuestModifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GuestText("open", GuestModifier.height(200.dp).then(clickable).prepareOnPress(controller, "detail"))
        repeat(20) { GuestText("row $it", GuestModifier.height(200.dp)) }
      }
      else -> GuestText("open", clickable.prepareOnPress(controller, "detail"))
    }
  }

  /** The guest's state changes reach the host: a guest frame, its batch, the host's frames. */
  private fun ComposeUiTest.sync(host: GuestOnHost) {
    repeat(3) {
      Snapshot.sendApplyNotifications()
      host.pump()
      waitForIdle()
    }
  }

  @Test
  fun aPressComposesTheScreenAndTheClickShowsItWithoutComposing() = runComposeUiTest {
    val host = start(setOf(GUEST_PRESS_FEATURE, PRESS_HOST_FEATURE))
    assertEquals(GUEST_PRESS_FEATURE, PRESS_HOST_FEATURE, "the guest's and the host's names agree")
    assertTrue(host.wire.any { PRESS_ON_WIRE in it }, "the press modifier is sent")

    onNodeWithText("open").performTouchInput { down(center) }
    sync(host)
    assertEquals(listOf("press"), log, "the press reaches the guest while the finger is down")
    assertEquals(1, compositions["detail"], "the screen is composed on touch-down")
    onAllNodesWithText("detail screen").assertCountEquals(0)
    onNodeWithText("open").assertIsDisplayed()
    val created = host.created.values.sum()

    onNodeWithText("open").performTouchInput { up() }
    sync(host)
    assertEquals(listOf("press", "click", "cancel"), log, "press, the click, then the cancel (which finds nothing)")
    assertEquals(listOf("home", "detail"), nav.backstack)
    onNodeWithText("detail screen").assertIsDisplayed()
    assertEquals(1, compositions["detail"], "the click composed nothing")
    assertEquals(created, host.created.values.sum(), "and the host created nothing")
    assertEquals(emptyList(), disposed)
  }

  @Test
  fun aPressThatMovesAwayIsCancelledAndItsCompositionDropped() = runComposeUiTest {
    val host = start(setOf(GUEST_PRESS_FEATURE))
    onNodeWithText("open").performTouchInput { down(center) }
    sync(host)
    assertEquals(1, compositions["detail"])

    onNodeWithText("open").performTouchInput {
      moveBy(Offset(0f, 5_000f))
      up()
    }
    sync(host)
    assertEquals(listOf("press", "cancel"), log, "no click")
    assertEquals(listOf("home"), nav.backstack)
    assertEquals(listOf("detail"), disposed, "the prepared screen is disposed")
    onAllNodesWithText("detail screen").assertCountEquals(0)

    // A tap after it composes the screen again: press, then the click shows it.
    onNodeWithText("open").performTouchInput {
      down(center)
      up()
    }
    sync(host)
    assertEquals(listOf("press", "cancel", "press", "click", "cancel"), log)
    assertEquals(listOf("home", "detail"), nav.backstack)
    onNodeWithText("detail screen").assertIsDisplayed()
    assertEquals(2, compositions["detail"])
  }

  /** A press that ends without the row's click navigating: the cancel drops the preparation. */
  private fun ComposeUiTest.assertPressIsCancelled(kind: RowKind, expected: List<String>) {
    val host = start(setOf(GUEST_PRESS_FEATURE), kind)
    val target = onNodeWithText(if (kind == RowKind.InnerButton) "share" else "open")
    target.performTouchInput { down(center) }
    sync(host)
    assertEquals(listOf("press"), log, "$kind")
    assertEquals(1, compositions["detail"], "prepared on the press")
    target.performTouchInput { up() }
    sync(host)
    assertEquals(expected, log, "$kind")
    assertEquals(listOf("home"), nav.backstack)
    assertEquals(listOf("detail"), disposed, "dropped at the cancel")
    onAllNodesWithText("detail screen").assertCountEquals(0)
  }

  @Test
  fun aDisabledClickIsCancelled() = runComposeUiTest {
    assertPressIsCancelled(RowKind.Disabled, listOf("press", "cancel"))
  }

  @Test
  fun aClickThatDoesNotNavigateIsCancelled() = runComposeUiTest {
    assertPressIsCancelled(RowKind.DoesNotNavigate, listOf("press", "click", "cancel"))
  }

  @Test
  fun aButtonInsideTheRowTakesTheClickAndTheRowsPressIsCancelled() = runComposeUiTest {
    assertPressIsCancelled(RowKind.InnerButton, listOf("press", "share", "cancel"))
  }

  @Test
  fun aHostBackDuringAPressDropsThePreparation() = runComposeUiTest {
    val host = start(setOf(GUEST_PRESS_FEATURE), rowOn = "list")
    onNodeWithText("to list").performTouchInput { click(center) }
    sync(host)
    assertEquals(listOf("home", "list"), nav.backstack)
    log.clear()

    onNodeWithText("open").performTouchInput { down(center) }
    sync(host)
    assertEquals(listOf("press"), log)
    assertEquals(1, compositions["detail"])

    // The row's host node goes when the guest drops the list, and its gesture is stopped: the
    // host's cancel then names a node the guest no longer has. A real guest reports and drops such
    // an event (this harness's adapter throws), which is why the guest cancels on dispose itself.
    val toGuest = tap.deliver
    val dropped = mutableListOf<dev.keliver.protocol.Event>()
    tap.deliver = { event -> runCatching { toGuest(event) }.onFailure { dropped += event } }
    runOnIdle { assertTrue(back.press(), "the host goes back") }
    sync(host)
    assertEquals(listOf("home"), nav.backstack)
    assertNull(nav.backstack.firstOrNull { it == "detail" })
    assertTrue("detail" in disposed, "the prepared screen is dropped: $disposed")
    onNodeWithText("to list").assertIsDisplayed()

    // The finger lifts where the row was: nothing opens.
    onNodeWithText("to list").performTouchInput { up() }
    sync(host)
    assertFalse("click" in log, "$log")
    assertEquals(listOf("home"), nav.backstack)
    assertTrue(dropped.size <= 1, "at most the host's late cancel was dropped: $dropped")
  }

  @Test
  fun inAScrollContainerAHeldTapSendsThePressAfterTheDelay() = runComposeUiTest {
    val host = start(setOf(GUEST_PRESS_FEATURE), RowKind.InScroll)
    mainClock.autoAdvance = false
    onNodeWithText("open").performTouchInput { down(center) }
    mainClock.advanceTimeByFrame()
    assertEquals(emptyList(), log, "not at touch-down")
    mainClock.advanceTimeBy(PressSignalTiming().scrollDelayMillis + 20)
    mainClock.autoAdvance = true
    sync(host)
    assertEquals(listOf("press"), log, "held still")
    assertEquals(1, compositions["detail"])

    onNodeWithText("open").performTouchInput { up() }
    sync(host)
    assertEquals(listOf("press", "click", "cancel"), log)
    assertEquals(listOf("home", "detail"), nav.backstack)
    assertEquals(1, compositions["detail"], "the click composed nothing")
  }

  @Test
  fun inAScrollContainerAFlingThatStartsOnTheRowSendsNothing() = runComposeUiTest {
    val host = start(setOf(GUEST_PRESS_FEATURE), RowKind.InScroll)
    onNodeWithText("open").performTouchInput {
      swipeUp(startY = centerY, endY = centerY - 300f, durationMillis = 80)
    }
    sync(host)
    assertEquals(emptyList(), log, "no press, no cancel, no click")
    assertNull(compositions["detail"], "nothing composed")
  }

  @Test
  fun aHostWithoutTheFeatureGetsNoPressAndTheClickComposes() = runComposeUiTest {
    val host = start(hostFeatures = emptySet())
    assertFalse(host.wire.any { PRESS_ON_WIRE in it }, "no press modifier on the wire")

    onNodeWithText("open").performTouchInput { down(center) }
    sync(host)
    assertEquals(emptyList(), log, "nothing is sent on touch-down")
    assertEquals(null, compositions["detail"])

    onNodeWithText("open").performTouchInput { up() }
    sync(host)
    assertEquals(listOf("click"), log, "the click only")
    assertEquals(listOf("home", "detail"), nav.backstack)
    onNodeWithText("detail screen").assertIsDisplayed()
    assertEquals(1, compositions["detail"], "composed at the click, as before")
  }
}
