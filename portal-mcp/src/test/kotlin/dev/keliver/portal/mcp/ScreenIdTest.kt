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
package dev.keliver.portal.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for KNOWN_BUGS U16.
 *
 * `list_screens` returns bare names; the relay's log prints `default/home`.
 * Forwarding the qualified form verbatim made the server mint a fresh empty
 * document under `default/default_home` and return it with no error, so an
 * agent that copied the id out of the log was told the screen was empty.
 */
class ScreenIdTest {
  @Test fun bareNamePassesThrough() =
    assertEquals("home", Tools.normalizeScreen("default", "home"))

  @Test fun ownProjectPrefixIsStripped() =
    assertEquals("home", Tools.normalizeScreen("default", "default/home"))

  @Test fun nonDefaultProjectPrefixIsStripped() =
    assertEquals("checkout", Tools.normalizeScreen("shop", "shop/checkout"))

  @Test fun aDifferentProjectPrefixIsNotStripped() =
    assertEquals("shop/checkout", Tools.normalizeScreen("default", "shop/checkout"))

  @Test fun repeatedNormalizationIsStable() {
    val once = Tools.normalizeScreen("default", "default/home")
    assertEquals(once, Tools.normalizeScreen("default", once))
  }

  @Test fun straySlashesAndSpacesAreTolerated() {
    assertEquals("home", Tools.normalizeScreen("default", " default/home "))
    assertEquals("home", Tools.normalizeScreen("default", "/home/"))
  }

  @Test fun anEmptySuffixIsNotTreatedAsAScreen() =
    assertEquals("default", Tools.normalizeScreen("default", "default/"))
}
