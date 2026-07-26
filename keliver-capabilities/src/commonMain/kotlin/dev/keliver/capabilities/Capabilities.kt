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

import kotlinx.coroutines.flow.StateFlow

/** Stable manifest/fidelity name for the authentication capability. */
public const val HOST_AUTH_CAPABILITY: String = "HostAuth@1"

/** Stable manifest/fidelity name for the feature-flag capability. */
public const val HOST_FLAGS_CAPABILITY: String = "HostFlags@1"

/** Stable manifest/fidelity name for the analytics capability. */
public const val HOST_ANALYTICS_CAPABILITY: String = "HostAnalytics@1"

/**
 * Authentication state exposed to guest presenters and repositories.
 *
 * Tokens deliberately do not appear here. Authentication headers belong in the
 * host HTTP client; the guest receives only the identity/claims it needs to
 * choose UI and business behavior.
 */
public sealed interface AuthState {
  /** No authenticated subject is available. */
  public data object SignedOut : AuthState

  /** Authenticated identity plus small, non-secret claims needed by the guest. */
  public data class SignedIn(
    public val subject: String,
    public val displayName: String? = null,
    public val attributes: Map<String, String> = emptyMap(),
  ) : AuthState
}

/**
 * Pure guest-facing authentication capability.
 *
 * A production composition root adapts its native/Zipline auth service to this
 * interface. Preview and tests use [FixtureHostAuth]. The interface itself
 * stays Zipline-free so the exact same presenter compiles to Wasm.
 */
public interface HostAuth {
  public val state: StateFlow<AuthState>
}

/**
 * Pure guest-facing feature-flag capability.
 *
 * The complete observable snapshot makes presenter recomposition explicit;
 * [isEnabled] is the convenient read path.
 */
public interface HostFlags {
  public val values: StateFlow<Map<String, Boolean>>

  public fun isEnabled(name: String): Boolean = values.value[name] == true
}

/** A structured analytics event emitted by guest logic. */
public data class AnalyticsEvent(
  public val name: String,
  public val properties: Map<String, String> = emptyMap(),
)

/**
 * Pure guest-facing analytics capability.
 *
 * Production adapters forward to the host analytics SDK. Preview/tests can
 * record events without pulling a vendor SDK into guest Kotlin.
 */
public fun interface HostAnalytics {
  public fun track(event: AnalyticsEvent)
}
