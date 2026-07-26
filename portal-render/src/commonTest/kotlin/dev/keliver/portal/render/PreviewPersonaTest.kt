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
import dev.keliver.capabilities.HOST_ANALYTICS_CAPABILITY
import dev.keliver.capabilities.HOST_AUTH_CAPABILITY
import dev.keliver.capabilities.HOST_FLAGS_CAPABILITY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreviewPersonaTest {
  @Test
  fun resolvesSelectionAndFallsBackToDeclaredDefault() {
    val entry = entry(
      personas = listOf(
        PreviewPersona("signed-out"),
        PreviewPersona("logged-in"),
      ),
      default = "logged-in",
    )

    assertEquals("signed-out", entry.resolvePersona("signed-out")?.id)
    assertEquals("logged-in", entry.resolvePersona("stale-browser-value")?.id)
    assertEquals("logged-in", entry.resolvePersona(null)?.id)
  }

  @Test
  fun catalogValidationRejectsAmbiguousOrMissingDefaults() {
    val duplicate = entry(
      personas = listOf(PreviewPersona("same"), PreviewPersona("same")),
    )
    val missingDefault = entry(
      personas = listOf(PreviewPersona("known")),
      default = "missing",
    )

    assertFailsWith<IllegalArgumentException> { duplicate.validatePersonaCatalog() }
    assertFailsWith<IllegalArgumentException> { missingDefault.validatePersonaCatalog() }
  }

  @Test
  fun fixtureDescriptionsAndTypedGraphComeFromTheSamePersona() {
    val persona = PreviewPersona(
      id = "kyc-pending",
      auth = AuthState.SignedIn(
        subject = "user-42",
        displayName = "Ari",
        attributes = mapOf("kyc" to "pending"),
      ),
      flags = mapOf("new-profile" to true),
      states = mapOf("HostKyc@1" to "pending"),
    )

    val states = persona.fixtureStates()
    val fixtures = persona.createCapabilityFixtures()

    assertTrue(states.getValue(HOST_AUTH_CAPABILITY).contains("Ari"))
    assertEquals("new-profile=on", states.getValue(HOST_FLAGS_CAPABILITY))
    assertEquals("recording sink", states.getValue(HOST_ANALYTICS_CAPABILITY))
    assertEquals("pending", states.getValue("HostKyc@1"))
    assertEquals(persona.auth, fixtures.auth.state.value)
    assertTrue(fixtures.flags.isEnabled("new-profile"))
  }

  private fun entry(
    personas: List<PreviewPersona>,
    default: String? = personas.firstOrNull()?.id,
  ): AppPreviewEntry = object : AppPreviewEntry {
    override val screens: Map<String, ScreenPreview> = emptyMap()
    override val personas: List<PreviewPersona> = personas
    override val defaultPersonaId: String? = default
    override val label: String = "test entry"
  }
}
