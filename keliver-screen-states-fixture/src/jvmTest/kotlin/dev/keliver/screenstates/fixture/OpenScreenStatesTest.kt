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

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import dev.keliver.http.asHostHttp
import dev.keliver.http.replay.debug.DebugHttpReplay
import dev.keliver.http.replay.debug.DebugHttpReplayMiss
import dev.keliver.nav.ExperimentalKeliverScreenStateApi
import dev.keliver.nav.MissingScreenStateFixturesException
import dev.keliver.nav.ScreenStateEntry
import dev.keliver.screenstates.fixture.generated.FixtureScreenStates
import dev.keliver.screenstates.fixture.logic.AppRoute
import dev.keliver.screenstates.fixture.logic.FixtureApp
import dev.keliver.screenstates.fixture.screens.CatalogScreenBindings
import dev.keliver.screenstates.fixture.screens.ItemScreenBindings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * MS4-28's acceptance: every state of the generated registry opens as a debug device opens it.
 * The route its function returns starts the app's own nav host, every presenter is the app's real
 * one, and `HostHttp` is the debug device's in-process replay of the state's fixture set
 * ([DebugHttpReplay], the relay's matching rules, no network). Each state's first frame (loading)
 * and the frame once its data arrived are asserted.
 */
@OptIn(ExperimentalKeliverScreenStateApi::class)
class OpenScreenStatesTest {
  /** The app's fixture sets as a debug host embeds them: id (file name without `.json`) to text. */
  private val fixtureFiles: Map<String, String> =
    File(System.getProperty("keliver.httpFixturesDir") ?: error("keliver.httpFixturesDir is not set"))
      .listFiles { f -> f.name.endsWith(".json") }!!
      .associate { it.name.removeSuffix(".json") to it.readText() }

  /** What a state's screen is given: the bindings of its first frame, and once its data arrived. */
  private class Opened(val route: AppRoute, val first: Any, val settled: Any, val misses: List<DebugHttpReplayMiss>)

  /** A screen's bindings as one line, for comparing every state at once. */
  private fun describe(bindings: Any): String = when (bindings) {
    is CatalogScreenBindings -> "catalog loading=${bindings.loading} title=${bindings.title} rows=${bindings.rows}"
    is ItemScreenBindings -> "item loading=${bindings.loading} title=${bindings.title} price=${bindings.price}"
    else -> error("not a screen's bindings: $bindings")
  }

  @Test fun everyStateOpensWithItsRouteItsPresenterAndItsFixtures() = runTest {
    val expected = mapOf(
      "catalog/loaded" to "catalog loading=false title=Catalog · 2 items rows=[Desk lamp · 24.00, Notebook · 3.50]",
      "catalog/empty" to "catalog loading=false title=Nothing for sale rows=[]",
      "item/detail" to "item loading=false title=Desk lamp price=24.00",
      // A recorded 404 is the state, not a replay miss.
      "item/not-found" to "item loading=false title=Item 404 not found price=null",
    )
    // A new state without an expectation here fails, rather than going untested.
    assertEquals(expected.keys, FixtureScreenStates.entries.map { "${it.screen}/${it.name}" }.toSet())

    for (entry in FixtureScreenStates.entries) {
      val key = "${entry.screen}/${entry.name}"
      val opened = open(entry, fixtureFiles)
      assertEquals(entry.state().route, opened.route, key)
      // The first frame: the presenter ran and asked for its data.
      assertTrue(describe(opened.first).contains("loading=true title=Loading…"), "$key: ${describe(opened.first)}")
      assertEquals(expected[key], describe(opened.settled), key)
      assertEquals(emptyList(), opened.misses, key)
    }
  }

  @Test fun theAppsFixtureSetsAreValidAndUnexpired() {
    val catalog = DebugHttpReplay(fixtureFiles, now = { NOW }).catalog()
    assertEquals(FixtureScreenStates.entries.map { it.fixtures }.toSet(), catalog.map { it.id }.toSet())
    catalog.forEach { assertTrue(it.valid && !it.expired, "${it.id}: ${it.error}") }
  }

  @Test fun aStateWhoseFixturesAreMissingFailsWithANamedError() = runTest {
    val e = assertFailsWith<MissingScreenStateFixturesException> {
      open(FixtureScreenStates["item", "detail"], fixtureFiles - "item-42")
    }
    assertEquals("item", e.screen)
    assertEquals("detail", e.state)
    assertEquals("item-42", e.fixtures)
    assertEquals(
      "item/detail replays fixture set 'item-42', which this host does not have " +
        "(it has catalog, catalog-empty, item-404); record it, or add item-42.json to the app's HTTP fixtures",
      e.message,
    )
  }

  /**
   * Opens [entry] as a debug device would: fail by name if the host lacks its fixture set, then
   * start the app at the state's route with `HostHttp` replaying the set.
   */
  private fun TestScope.open(entry: ScreenStateEntry, fixtures: Map<String, String>): Opened {
    val state = entry.open(fixtures.keys)
    val route = state.route as AppRoute
    val misses = mutableListOf<DebugHttpReplayMiss>()
    val http = DebugHttpReplay(fixtures, now = { NOW }).provider(state.fixtures, onMiss = { misses += it }).asHostHttp()

    val clock = BroadcastFrameClock()
    val recomposer = Recomposer(coroutineContext + clock)
    backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    val composition = Composition(NoNodes(), recomposer)
    val shown = mutableListOf<Any>()
    try {
      composition.setContent { FixtureApp(start = route, http = http) { shown += it } }
      val first = shown.last()
      var frame = 0L
      repeat(10) {
        // runCurrent, not advanceUntilIdle: the recomposer runs in backgroundScope.
        Snapshot.sendApplyNotifications()
        runCurrent()
        frame += 16_000_000L
        clock.sendFrame(frame)
        runCurrent()
      }
      return Opened(route, first, shown.last(), misses)
    } finally {
      composition.dispose()
      recomposer.cancel()
    }
  }

  /** The fixture draws no widgets: its screens are their bindings. */
  private class NoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
  }

  private companion object {
    /** The replay's clock: the fixture sets carry no expiry, so any time would do. */
    val NOW: Instant = Instant.parse("2026-11-27T00:00:00Z")
  }
}
