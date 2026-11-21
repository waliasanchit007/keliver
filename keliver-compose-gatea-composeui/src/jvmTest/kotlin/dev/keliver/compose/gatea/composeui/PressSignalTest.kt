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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.LocalHostFeatures
import dev.keliver.compose.extras.PRESS_HOST_FEATURE as GUEST_PRESS_FEATURE
import dev.keliver.compose.extras.pressSignal
import dev.keliver.compose.foundation.clickable as guestClickable
import dev.keliver.compose.foundation.layout.fillMaxSize
import dev.keliver.compose.gatea.compose.ExtraRetained
import dev.keliver.compose.gatea.compose.ExtraRetainedEntry
import dev.keliver.compose.material3.Text as GuestText
import dev.keliver.compose.ui.Modifier as GuestModifier
import dev.keliver.nav.ExperimentalKeliverNavRetentionApi
import dev.keliver.nav.KeliverNavController
import dev.keliver.nav.KeliverNavHost
import dev.keliver.nav.KeliverNavHostFrame
import dev.keliver.nav.KeliverNavPress
import dev.keliver.nav.KeliverNavRetention
import dev.keliver.nav.LocalKeliverNavPress
import dev.keliver.nav.prepareOnPress
import dev.keliver.nav.rememberKeliverNavController
import dev.keliver.compose.foundation.layout.Column
import dev.keliver.compose.foundation.layout.height
import dev.keliver.compose.foundation.rememberScrollState
import dev.keliver.compose.foundation.verticalScroll
import dev.keliver.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ExtraPress`'s second property, as the protocol's JSON names it: only that modifier has it. */
private const val PRESS_ON_WIRE = "\"onCancel\""

/**
 * NF3, the host's press signal: gate A's `ExtraPress` on a row, guest and host joined by the real
 * protocol (JSON). The host sends the press on touch-down, before the click; keliver-nav composes
 * the screen the row opens while the finger is down (hidden, under the retained container's shown
 * entry), and the click shows that composition. A host without the feature never receives the
 * modifier, and the screen is composed at the click (D2).
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

  /** Gate A's press signal for keliver-nav, as an app provides it at its root. */
  private object GateAPress : KeliverNavPress {
    override val hostFeature: String = GUEST_PRESS_FEATURE

    override fun pressSignal(modifier: dev.keliver.Modifier, onPress: () -> Unit, onCancel: () -> Unit) =
      modifier.pressSignal(onPress, onCancel)
  }

  private val tap = EventTap()
  private val compositions = mutableMapOf<String, Int>()
  private val disposed = mutableListOf<String>()
  private lateinit var nav: KeliverNavController<String>

  private fun ComposeUiTest.start(hostFeatures: Set<String>, scrolling: Boolean = false): GuestOnHost {
    val host = guestOnHost(tap) {
      CompositionLocalProvider(LocalHostFeatures provides hostFeatures, LocalKeliverNavPress provides GateAPress) {
        val controller = rememberKeliverNavController("home")
        nav = controller
        KeliverNavHost(controller, KeliverNavRetention(HostFrame, depth = 1)) { route ->
          compositions[route] = (compositions[route] ?: 0) + 1
          DisposableEffect(route) { onDispose { disposed += route } }
          if (route == "home" && scrolling) {
            // A tall row in a scrolling column: a drag that stays inside the row is taken by the scroll.
            Column(GuestModifier.fillMaxSize().verticalScroll(rememberScrollState())) {
              GuestText(
                "open",
                GuestModifier.height(400.dp).guestClickable { controller.navigate("detail") }.prepareOnPress(controller, "detail"),
              )
              repeat(10) { GuestText("row $it", GuestModifier.height(400.dp)) }
            }
          } else if (route == "home") {
            GuestText(
              "open",
              GuestModifier.guestClickable { controller.navigate("detail") }.prepareOnPress(controller, "detail"),
            )
          } else {
            GuestText("$route screen")
          }
        }
      }
    }
    setContent { host.root.Render(null) }
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

  @Test
  fun aPressComposesTheScreenAndTheClickShowsItWithoutComposing() = runComposeUiTest {
    val host = start(setOf(GUEST_PRESS_FEATURE, PRESS_HOST_FEATURE))
    assertEquals(GUEST_PRESS_FEATURE, PRESS_HOST_FEATURE, "the guest's and the host's names agree")
    assertTrue(host.wire.any { PRESS_ON_WIRE in it }, "the press modifier is sent")

    onNodeWithText("open").performTouchInput { down(center) }
    sync(host)
    assertEquals(1, tap.sent.size, "the press reaches the guest while the finger is down")
    assertEquals(1, compositions["detail"], "the screen is composed on touch-down")
    onAllNodesWithText("detail screen").assertCountEquals(0)
    onNodeWithText("open").assertIsDisplayed()
    val created = host.created.values.sum()

    onNodeWithText("open").performTouchInput { up() }
    sync(host)
    assertEquals(2, tap.sent.size, "press, then the click; no cancel")
    assertEquals(listOf("home", "detail"), nav.backstack)
    onNodeWithText("detail screen").assertIsDisplayed()
    assertEquals(1, compositions["detail"], "the click composed nothing")
    assertEquals(created, host.created.values.sum(), "and the host created nothing")
    assertEquals(emptyList(), disposed)
  }

  @Test
  fun aPressThatMovesAwayIsCancelledAndItsCompositionDropped() = runComposeUiTest {
    val host = start(setOf(GUEST_PRESS_FEATURE))
    onNodeWithText("open").performTouchInput {
      down(center)
    }
    sync(host)
    assertEquals(1, compositions["detail"])

    onNodeWithText("open").performTouchInput {
      moveBy(Offset(0f, 5_000f))
      up()
    }
    sync(host)
    assertEquals(2, tap.sent.size, "press, then cancel; no click")
    assertEquals(listOf("home"), nav.backstack)
    assertEquals(listOf("detail"), disposed, "the prepared screen is disposed")
    onAllNodesWithText("detail screen").assertCountEquals(0)

    // A tap after it composes the screen again: press, then the click shows it.
    onNodeWithText("open").performTouchInput {
      down(center)
      up()
    }
    sync(host)
    assertEquals(listOf("home", "detail"), nav.backstack)
    onNodeWithText("detail screen").assertIsDisplayed()
    assertEquals(2, compositions["detail"])
  }

  @Test
  fun aScrollThatTakesTheGestureCancelsThePress() = runComposeUiTest {
    val host = start(setOf(GUEST_PRESS_FEATURE), scrolling = true)
    onNodeWithText("open").performTouchInput {
      down(center)
    }
    sync(host)
    assertEquals(1, compositions["detail"])

    // Up by 150 px: past the touch slop, still inside the 400 dp row.
    onNodeWithText("open").performTouchInput {
      moveBy(Offset(0f, -150f))
      up()
    }
    sync(host)
    assertEquals(2, tap.sent.size, "press, then cancel; the scroll took the gesture, no click")
    assertEquals(listOf("home"), nav.backstack)
    assertEquals(listOf("detail"), disposed)
  }

  @Test
  fun aHostWithoutTheFeatureGetsNoPressAndTheClickComposes() = runComposeUiTest {
    val host = start(hostFeatures = emptySet())
    assertFalse(host.wire.any { PRESS_ON_WIRE in it }, "no press modifier on the wire")

    onNodeWithText("open").performTouchInput { down(center) }
    sync(host)
    assertEquals(0, tap.sent.size, "nothing is sent on touch-down")
    assertEquals(null, compositions["detail"])

    onNodeWithText("open").performTouchInput { up() }
    sync(host)
    assertEquals(1, tap.sent.size, "the click only")
    assertEquals(listOf("home", "detail"), nav.backstack)
    onNodeWithText("detail screen").assertIsDisplayed()
    assertEquals(1, compositions["detail"], "composed at the click, as before")
  }
}
