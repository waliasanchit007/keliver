/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.capabilities

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityFixturesTest {
  @Test
  fun authAndFlagsAreObservableMutableFixtures() {
    val fixtures = CapabilityFixtures(
      authState = AuthState.SignedOut,
      flags = mapOf("new-profile" to false),
    )

    assertEquals(AuthState.SignedOut, fixtures.auth.state.value)
    assertFalse(fixtures.flags.isEnabled("new-profile"))
    assertFalse(fixtures.flags.isEnabled("missing"))

    val signedIn = AuthState.SignedIn(
      subject = "user-42",
      displayName = "Ari",
      attributes = mapOf("kyc" to "pending"),
    )
    fixtures.auth.set(signedIn)
    fixtures.flags.set("new-profile", true)

    assertEquals(signedIn, fixtures.auth.state.value)
    assertTrue(fixtures.flags.isEnabled("new-profile"))
  }

  @Test
  fun analyticsRecordsImmutableSnapshots() {
    val analytics = RecordingHostAnalytics()
    val event = AnalyticsEvent("settings_opened", mapOf("destination" to "profile"))

    analytics.track(event)
    val snapshot = analytics.events
    analytics.track(AnalyticsEvent("second"))

    assertEquals(listOf(event), snapshot)
    assertEquals(listOf(event, AnalyticsEvent("second")), analytics.events)

    analytics.clear()
    assertTrue(analytics.events.isEmpty())
  }

  @Test
  fun replacingFlagsCopiesCallerState() {
    val fixture = FixtureHostFlags()
    val source = mutableMapOf("a" to true)

    fixture.replace(source)
    source["a"] = false

    assertTrue(fixture.isEnabled("a"))
  }
}
