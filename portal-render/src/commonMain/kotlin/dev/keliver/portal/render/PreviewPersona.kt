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
package dev.keliver.portal.render

import dev.keliver.capabilities.AuthState
import dev.keliver.capabilities.CapabilityFixtures
import dev.keliver.capabilities.HOST_ANALYTICS_CAPABILITY
import dev.keliver.capabilities.HOST_AUTH_CAPABILITY
import dev.keliver.capabilities.HOST_FLAGS_CAPABILITY
import dev.keliver.capabilities.HOST_HTTP_CAPABILITY

/**
 * A named, app-owned start-state for real presenter/flow preview.
 *
 * [auth] and [flags] produce the framework's typed fixtures. [states] names
 * extra app-specific capability/domain fixtures for the editor's fidelity
 * report; the app maps those values into its own typed interfaces.
 */
public data class PreviewPersona(
  public val id: String,
  public val label: String = id,
  public val description: String = "",
  public val auth: AuthState = AuthState.SignedOut,
  public val flags: Map<String, Boolean> = emptyMap(),
  public val states: Map<String, String> = emptyMap(),
  /** App-owned relay replay fixture set selected for this start-state. */
  public val httpFixtureSet: String? = null,
) {
  /** Human-readable fixture states for the editor fidelity panel. */
  public fun fixtureStates(): Map<String, String> = buildMap {
    put(
      HOST_AUTH_CAPABILITY,
      when (val state = auth) {
        AuthState.SignedOut -> "signed out"
        is AuthState.SignedIn -> buildString {
          append("signed in: ")
          append(state.displayName ?: state.subject)
          if (state.attributes.isNotEmpty()) {
            append(" (")
            append(state.attributes.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" })
            append(")")
          }
        }
      },
    )
    put(
      HOST_FLAGS_CAPABILITY,
      if (flags.isEmpty()) {
        "empty flag set"
      } else {
        flags.entries.sortedBy { it.key }.joinToString { "${it.key}=${if (it.value) "on" else "off"}" }
      },
    )
    put(HOST_ANALYTICS_CAPABILITY, "recording sink")
    httpFixtureSet?.let { put(HOST_HTTP_CAPABILITY, "replay set: $it") }
    putAll(states)
  }

  /** A fresh typed graph; callers remember it with [id] as the key. */
  public fun createCapabilityFixtures(): CapabilityFixtures = CapabilityFixtures(auth, flags)
}

/**
 * Validate the app-owned catalog before the editor mounts.
 *
 * Invalid catalogs fail loudly instead of silently selecting the wrong state.
 */
public fun AppPreviewEntry.validatePersonaCatalog() {
  val ids = personas.map { it.id }
  require(ids.none { it.isBlank() }) { "Persona IDs for '$label' must not be blank" }
  require(ids.distinct().size == ids.size) { "Persona IDs for '$label' must be unique: $ids" }
  require(personas.none { it.label.isBlank() }) { "Persona labels for '$label' must not be blank" }
  require(personas.none { it.httpFixtureSet?.isBlank() == true }) {
    "HTTP fixture-set IDs for '$label' must not be blank"
  }
  val default = defaultPersonaId
  require(default == null || personas.any { it.id == default }) {
    "Default persona '$default' is not declared by '$label'"
  }
}

/**
 * Resolve a selected ID, tolerating stale browser storage by falling back to
 * the app's declared default.
 */
public fun AppPreviewEntry.resolvePersona(selectedId: String?): PreviewPersona? {
  validatePersonaCatalog()
  return personas.firstOrNull { it.id == selectedId }
    ?: personas.firstOrNull { it.id == defaultPersonaId }
}
