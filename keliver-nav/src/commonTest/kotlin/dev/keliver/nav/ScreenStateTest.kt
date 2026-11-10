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
package dev.keliver.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ScreenStateTest {
  private sealed interface Route {
    data class ItemDetail(val id: String) : Route
  }

  @ScreenState("review")
  private fun review() = NamedState<Route>(route = Route.ItemDetail(id = "sample"), fixtures = "review")

  @Test fun aStateCarriesItsTypedRouteAndFixtureSet() {
    val state = review()
    assertEquals(Route.ItemDetail("sample"), state.route)
    assertEquals("review", state.fixtures)
  }

  @Test fun statesCompareByValue() {
    assertEquals(review(), NamedState<Route>(Route.ItemDetail("sample"), "review"))
    assertEquals(review().hashCode(), NamedState<Route>(Route.ItemDetail("sample"), "review").hashCode())
    assertNotEquals(review(), NamedState<Route>(Route.ItemDetail("sample"), "empty"))
    assertNotEquals(review(), NamedState<Route>(Route.ItemDetail("other"), "review"))
    assertEquals("NamedState(route=ItemDetail(id=sample), fixtures=review)", review().toString())
  }

  @Test fun aBlankFixtureSetIsRefused() {
    assertFailsWith<IllegalArgumentException> { NamedState(Route.ItemDetail("x"), " ") }
  }
}
