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
package dev.keliver.portalpublished.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.keliver.capabilities.AnalyticsEvent
import dev.keliver.capabilities.AuthState
import dev.keliver.capabilities.HostAnalytics
import dev.keliver.capabilities.HostAuth
import dev.keliver.capabilities.HostFlags
import dev.keliver.capabilities.HostHttp
import dev.keliver.portalpublished.screens.SettingsScreenBindings

/**
 * Real settings presenter shared by device and browser preview compositions.
 *
 * The composition root decides whether the capabilities are native adapters
 * or named fixtures; the presenter has no preview-only branch.
 */
@Composable
public fun SettingsPresenter(
  auth: HostAuth,
  flags: HostFlags,
  analytics: HostAnalytics,
  http: HostHttp? = null,
  onOpen: (String) -> Unit,
): SettingsScreenBindings {
  val authState by auth.state.collectAsState()
  val flagValues by flags.values.collectAsState()
  var recordedProfile by remember(http) { mutableStateOf<ProfileSummary?>(null) }
  LaunchedEffect(http) {
    recordedProfile = http?.let { runCatching { ProfileSummaryApi(it).load() }.getOrNull() }
  }

  return object : SettingsScreenBindings {
    override val name: String = recordedProfile?.let { "${it.displayName} · ${it.source}" }
      ?: settingsName(authState, flagValues)

    override fun open(value: String) {
      analytics.track(
        AnalyticsEvent(
          name = "settings_opened",
          properties = mapOf("destination" to value),
        ),
      )
      onOpen(value)
    }
  }
}

internal fun settingsName(
  authState: AuthState,
  flags: Map<String, Boolean>,
): String = buildString {
  when (authState) {
    AuthState.SignedOut -> append("Signed out")
    is AuthState.SignedIn -> append(authState.displayName ?: authState.subject)
  }
  if (flags["new-profile"] == true) append(" · beta")
}
