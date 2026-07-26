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

import dev.keliver.capabilities.AuthState
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPresenterTest {
  @Test
  fun personaIdentityAndFlagDetermineVisibleName() {
    assertEquals("Signed out", settingsName(AuthState.SignedOut, emptyMap()))
    assertEquals(
      "Maya Chen · beta",
      settingsName(
        AuthState.SignedIn(subject = "researcher-42", displayName = "Maya Chen"),
        mapOf("new-profile" to true),
      ),
    )
    assertEquals(
      "applicant-17",
      settingsName(AuthState.SignedIn(subject = "applicant-17"), emptyMap()),
    )
  }
}
