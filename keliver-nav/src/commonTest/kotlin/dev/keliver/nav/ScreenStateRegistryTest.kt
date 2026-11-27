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
package dev.keliver.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalKeliverNavScreenStateApi::class)
class ScreenStateRegistryTest {
  private sealed interface Route {
    data object Home : Route
    data class Item(val id: String) : Route
  }

  private var calls = 0

  private fun entry(screen: String, name: String, fixtures: String, route: Route, returns: String = fixtures) =
    ScreenStateEntry(
      screen = screen,
      name = name,
      function = "${screen}_$name",
      routeSource = route.toString(),
      routeType = route::class.simpleName,
      fixtures = fixtures,
    ) {
      calls++
      NamedState(route, returns)
    }

  private val registry = ScreenStateRegistry(
    listOf(
      entry("home", "signed-in", "home", Route.Home),
      entry("item", "detail", "item-42", Route.Item("42")),
      entry("item", "missing", "item-404", Route.Item("404")),
    ),
    problems = mapOf("broken" to listOf("x: a state function must not be private")),
  )

  @Test fun statesAreListedByScreenInOrder() {
    assertEquals(listOf("home", "item"), registry.screens)
    assertEquals(listOf("detail", "missing"), registry.states("item").map { it.name })
    assertEquals(emptyList(), registry.states("broken"))
    assertEquals(listOf("x: a state function must not be private"), registry.problems["broken"])
  }

  @Test fun listingRunsNoStateFunction() {
    registry.entries.forEach { it.routeSource + it.fixtures }
    registry.find("item", "detail")
    assertEquals(0, calls)
  }

  @Test fun openingRunsTheDeclaringFunctionEachTime() {
    val first = registry.open("item", "detail", setOf("item-42"))
    assertEquals(NamedState<Route>(Route.Item("42"), "item-42"), first)
    val second = registry["item", "detail"].state()
    assertEquals(first, second)
    assertNotSame(first, second)
    assertEquals(2, calls)
  }

  @Test fun anUnknownStateIsANamedError() {
    assertNull(registry.find("item", "gone"))
    val noState = assertFailsWith<UnknownScreenStateException> { registry["item", "gone"] }
    assertEquals("item", noState.screen)
    assertEquals("gone", noState.state)
    assertEquals("screen 'item' has no state 'gone' (its states: detail, missing)", noState.message)
    val noScreen = assertFailsWith<UnknownScreenStateException> { registry.open("cart", "empty", setOf("cart")) }
    assertEquals("no screen 'cart' declares states (screens with states: home, item)", noScreen.message)
  }

  @Test fun aStateWhoseFixturesAreMissingIsANamedErrorAndRunsNothing() {
    val e = assertFailsWith<MissingScreenStateFixturesException> {
      registry.open("item", "missing", listOf("item-42", "home"))
    }
    assertEquals("item", e.screen)
    assertEquals("missing", e.state)
    assertEquals("item-404", e.fixtures)
    assertEquals(
      "item/missing replays fixture set 'item-404', which this host does not have (it has home, item-42); " +
        "record it, or add item-404.json to the app's HTTP fixtures",
      e.message,
    )
    assertEquals(0, calls)
    val none = assertFailsWith<MissingScreenStateFixturesException> { registry.open("home", "signed-in", emptySet()) }
    assertTrue("(it has none)" in none.message!!, none.message)
  }

  @Test fun aRegistryOlderThanItsScreenFileSaysSo() {
    val stale = entry("home", "signed-in", "home", Route.Home, returns = "home-v2")
    val e = assertFailsWith<StaleScreenStateRegistryException> { stale.state() }
    assertEquals("home", e.screen)
    assertEquals("signed-in", e.state)
    assertEquals("home", e.registryFixtures)
    assertEquals("home-v2", e.declaredFixtures)
    assertEquals(
      "home/signed-in: home_signed-in() replays 'home-v2' but the registry says 'home'; " +
        "the registry is older than the screen file: regenerate it",
      e.message,
    )
    // Opening it fails the same way, once the host has the set.
    assertFailsWith<StaleScreenStateRegistryException> { stale.open(setOf("home")) }
  }

  /** A host catches one type: which case it was is the subtype, and every case names the state. */
  @Test fun everyFailureToOpenIsAScreenStateException() {
    val stale = ScreenStateRegistry(listOf(entry("home", "signed-in", "home", Route.Home, returns = "home-v2")))
    fun failure(open: () -> Unit): ScreenStateException = assertFailsWith<ScreenStateException> { open() }
    val failures = listOf(
      failure { registry.open("item", "gone", setOf("item-42")) },
      failure { registry.open("item", "detail", emptySet()) },
      failure { stale.open("home", "signed-in", setOf("home")) },
    )
    assertEquals(
      listOf(
        "UnknownScreenStateException item/gone",
        "MissingScreenStateFixturesException item/detail",
        "StaleScreenStateRegistryException home/signed-in",
      ),
      failures.map {
        val kind = when (it) {
          is UnknownScreenStateException -> "UnknownScreenStateException"
          is MissingScreenStateFixturesException -> "MissingScreenStateFixturesException"
          is StaleScreenStateRegistryException -> "StaleScreenStateRegistryException"
        }
        "$kind ${it.screen}/${it.state}"
      },
    )
  }

  @Test fun aStateIsDeclaredOncePerScreen() {
    val e = assertFailsWith<IllegalArgumentException> {
      ScreenStateRegistry(listOf(entry("home", "a", "x", Route.Home), entry("home", "a", "y", Route.Home)))
    }
    assertEquals("screen 'home' declares state 'a' twice", e.message)
    // The same name on two screens is two states.
    val two = ScreenStateRegistry(listOf(entry("home", "a", "x", Route.Home), entry("item", "a", "y", Route.Item("1"))))
    assertSame(two.entries[1], two["item", "a"])
  }

  @Test fun blankFieldsAreRefused() {
    assertFailsWith<IllegalArgumentException> { entry(" ", "a", "x", Route.Home) }
    assertFailsWith<IllegalArgumentException> { entry("home", "", "x", Route.Home) }
    assertFailsWith<IllegalArgumentException> { entry("home", "a", " ", Route.Home) }
  }
}
