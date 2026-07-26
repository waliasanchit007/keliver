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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mutable auth fixture shared by browser preview and presenter tests. */
public class FixtureHostAuth(
  initialState: AuthState = AuthState.SignedOut,
) : HostAuth {
  private val mutableState = MutableStateFlow(initialState)

  override val state: StateFlow<AuthState> = mutableState.asStateFlow()

  public fun set(state: AuthState) {
    mutableState.value = state
  }
}

/** Mutable flag fixture shared by browser preview and presenter tests. */
public class FixtureHostFlags(
  initialValues: Map<String, Boolean> = emptyMap(),
) : HostFlags {
  private val mutableValues = MutableStateFlow(initialValues.toMap())

  override val values: StateFlow<Map<String, Boolean>> = mutableValues.asStateFlow()

  public fun set(name: String, enabled: Boolean) {
    mutableValues.value = mutableValues.value + (name to enabled)
  }

  public fun replace(values: Map<String, Boolean>) {
    mutableValues.value = values.toMap()
  }
}

/** Deterministic analytics sink for browser preview and presenter tests. */
public class RecordingHostAnalytics : HostAnalytics {
  private val recorded = mutableListOf<AnalyticsEvent>()

  public val events: List<AnalyticsEvent> get() = recorded.toList()

  override fun track(event: AnalyticsEvent) {
    recorded += event
  }

  public fun clear() {
    recorded.clear()
  }
}

/** One fresh typed capability graph for a test or named preview persona. */
public class CapabilityFixtures(
  authState: AuthState = AuthState.SignedOut,
  flags: Map<String, Boolean> = emptyMap(),
) {
  public val auth: FixtureHostAuth = FixtureHostAuth(authState)
  public val flags: FixtureHostFlags = FixtureHostFlags(flags)
  public val analytics: RecordingHostAnalytics = RecordingHostAnalytics()
}
