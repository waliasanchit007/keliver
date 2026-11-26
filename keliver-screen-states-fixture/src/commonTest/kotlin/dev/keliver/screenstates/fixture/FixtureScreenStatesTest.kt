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
package dev.keliver.screenstates.fixture

import dev.keliver.nav.ExperimentalKeliverScreenStateApi
import dev.keliver.nav.NamedState
import dev.keliver.nav.UnknownScreenStateException
import dev.keliver.screenstates.fixture.generated.FixtureScreenStates
import dev.keliver.screenstates.fixture.logic.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * MS4-28: the generated registry is ordinary code, so it lists and runs states on every target,
 * Kotlin/JS (a guest) and wasm (the editor) included, where there is no annotation reflection.
 */
@OptIn(ExperimentalKeliverScreenStateApi::class)
class FixtureScreenStatesTest {
  @Test fun theRegistryListsTheScreensStatesInSourceOrder() {
    assertEquals(listOf("catalog", "item"), FixtureScreenStates.screens)
    assertEquals(
      listOf(
        listOf("catalog", "loaded", "catalogLoaded", "AppRoute.Catalog", "AppRoute.Catalog", "catalog"),
        listOf("catalog", "empty", "catalogEmpty", "AppRoute.Catalog", "AppRoute.Catalog", "catalog-empty"),
        listOf("item", "detail", "itemDetail", "AppRoute.Item(id = \"42\")", "AppRoute.Item", "item-42"),
        listOf("item", "not-found", "itemNotFound", "AppRoute.Item(id = \"404\")", "AppRoute.Item", "item-404"),
      ),
      FixtureScreenStates.entries.map { listOf(it.screen, it.name, it.function, it.routeSource, it.routeType, it.fixtures) },
    )
    assertEquals(emptyMap(), FixtureScreenStates.problems)
  }

  @Test fun eachStateRunsItsDeclaringFunction() {
    assertEquals(
      listOf(
        NamedState<AppRoute>(AppRoute.Catalog, "catalog"),
        NamedState<AppRoute>(AppRoute.Catalog, "catalog-empty"),
        NamedState<AppRoute>(AppRoute.Item("42"), "item-42"),
        // An internal state function: the registry is in the same module.
        NamedState<AppRoute>(AppRoute.Item("404"), "item-404"),
      ),
      FixtureScreenStates.entries.map { it.state() },
    )
  }

  @Test fun aStateIsFoundByScreenAndName() {
    assertEquals(AppRoute.Item("42"), FixtureScreenStates.open("item", "detail", setOf("item-42")).route)
    assertFailsWith<UnknownScreenStateException> { FixtureScreenStates["item", "loaded"] }
  }
}
