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
package dev.keliver.treehouse.composeui

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.concurrent.thread
import kotlin.test.Test

class BackGateTest {
  private val platform = FakePlatformBackDispatcher()

  @Test
  fun closedGateSendsBackPastTheGuestToTheHostBeneath() {
    platform.addCallback(platform.hostCallback("screen beneath"))
    val gate = BackGate(platform, open = false)
    gate.addCallback(GuestBackHandler("guest", enabled = true, platform.received))

    platform.back()

    assertThat(platform.received).containsExactly("screen beneath")
  }

  @Test
  fun closedGateWithNothingBeneathGoesToTheActivity() {
    val gate = BackGate(platform, open = false)
    gate.addCallback(GuestBackHandler("guest", enabled = true, platform.received))

    platform.back()

    assertThat(platform.received).containsExactly("activity")
  }

  @Test
  fun openGateIsTheGuestsBackAsWithoutAGate() {
    platform.addCallback(platform.hostCallback("screen beneath"))
    val gate = BackGate(platform, open = true)
    val guest = GuestBackHandler("guest", enabled = true, platform.received)
    gate.addCallback(guest)

    platform.back()
    guest.isEnabled = false
    platform.back()

    assertThat(platform.received).containsExactly("guest", "screen beneath")
  }

  @Test
  fun openingTheGateEnablesExactlyTheCallbacksTheGuestHasEnabled() {
    val gate = BackGate(platform, open = false)
    val first = GuestBackHandler("first", enabled = true, platform.received)
    val second = GuestBackHandler("second", enabled = false, platform.received)
    gate.addCallback(first)
    gate.addCallback(second)
    platform.back()

    gate.open = true
    platform.back() // second is disabled: the guest's first handler takes it
    second.isEnabled = true
    platform.back() // the guest's order holds: the one it registered last

    assertThat(platform.received).containsExactly("activity", "first", "second")
  }

  @Test
  fun guestChangesWhileClosedNeverReachThePlatform() {
    val gate = BackGate(platform, open = false)
    val guest = GuestBackHandler("guest", enabled = false, platform.received)
    gate.addCallback(guest)

    guest.isEnabled = true
    assertThat(platform.registered.single().isEnabled).isFalse()
    platform.back()
    gate.open = true
    assertThat(platform.registered.single().isEnabled).isTrue()

    assertThat(platform.received).containsExactly("activity")
  }

  @Test
  fun closingTheGateAgainDisablesIt() {
    val gate = BackGate(platform, open = true)
    gate.addCallback(GuestBackHandler("guest", enabled = true, platform.received))

    gate.open = false
    platform.back()

    assertThat(platform.received).containsExactly("activity")
  }

  @Test
  fun aPressThePlatformAlreadyRoutedNeverReachesAClosedGuest() {
    val gate = BackGate(platform, open = true)
    gate.addCallback(GuestBackHandler("guest", enabled = true, platform.received))
    val routed = platform.registered.single()

    gate.open = false
    routed.handleOnBackPressed() // a platform that picked its target before the gate closed

    assertThat(platform.received).isEmpty()
  }

  @Test
  fun cancelRemovesTheCallbackAndForgetsTheGuest() {
    val gate = BackGate(platform, open = true)
    val guest = GuestBackHandler("guest", enabled = true, platform.received)
    val registration = gate.addCallback(guest)

    registration.cancel()

    assertThat(platform.registered).isEmpty()
    assertThat(guest.enabledChangedCallback).isNull()
    gate.open = false
    gate.open = true
    platform.back()
    assertThat(platform.received).containsExactly("activity")
  }

  /** Treehouse changes a guest's callbacks on its Zipline thread while the UI thread opens the gate. */
  @Test
  fun theLastWriteAgreesWithTheGateAndTheGuestAcrossThreads() {
    repeat(200) { round ->
      val platform = FakePlatformBackDispatcher()
      val gate = BackGate(platform, open = round % 2 == 0)
      val guest = GuestBackHandler("guest", enabled = round % 3 == 0, platform.received)
      gate.addCallback(guest)
      val zipline = thread { repeat(500) { guest.isEnabled = !guest.isEnabled } }
      val ui = thread { repeat(500) { gate.open = !gate.open } }
      zipline.join()
      ui.join()
      assertThat(platform.registered.single().isEnabled, "round $round").isEqualTo(gate.open && guest.isEnabled)
    }
  }
}
