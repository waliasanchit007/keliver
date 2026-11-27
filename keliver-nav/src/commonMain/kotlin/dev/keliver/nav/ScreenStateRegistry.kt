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

/**
 * Every [ScreenState] of an app's screens, as ordinary code: a device, the editor or a test looks a
 * state up by screen and name and runs its declaring function, with no reflection (Kotlin/JS has
 * none for annotations).
 *
 * An app does not write this. The portal's generator writes it from the screen files, with the
 * same reader as the portal's `GET /states`, so both list the same states, in the same order, with
 * the same [problems] (docs/PORTAL_USAGE.md, "Named states"). A freshness check fails when a screen
 * file changed and the registry was not regenerated.
 *
 * Opening a state: [open] (or [ScreenStateEntry.open]) gives the [NamedState] to start the
 * screen with. The host then binds `HostHttp` to a replay of [NamedState.fixtures] and opens
 * [NamedState.route] the way the app opens any route (D8: routes are data). The device route that
 * does this from a link is planned (docs/PLAN_2026-11.md ED3).
 */
@ExperimentalKeliverNavScreenStateApi
public class ScreenStateRegistry(
  /** Every state, by screen file name and then in source order. */
  public val entries: List<ScreenStateEntry>,
  /**
   * By screen: why a `@ScreenState` function there is not in [entries] (a private function, a
   * body that is not one `NamedState(…)` call, a repeated name). The same text `GET /states`
   * reports.
   */
  public val problems: Map<String, List<String>> = emptyMap(),
) {
  init {
    val seen = HashSet<Pair<String, String>>()
    for (entry in entries) {
      require(seen.add(entry.screen to entry.name)) {
        "screen '${entry.screen}' declares state '${entry.name}' twice"
      }
    }
  }

  /** The screens that declare at least one state, in [entries]' order. */
  public val screens: List<String> = entries.map { it.screen }.distinct()

  /** [screen]'s states in source order; empty when it declares none. */
  public fun states(screen: String): List<ScreenStateEntry> = entries.filter { it.screen == screen }

  /** [screen]'s state called [name], or null. */
  public fun find(screen: String, name: String): ScreenStateEntry? =
    entries.firstOrNull { it.screen == screen && it.name == name }

  /**
   * [screen]'s state called [name].
   *
   * @throws UnknownScreenStateException when there is none (a link to a state since renamed, or
   *   a registry older than the screen file).
   */
  public operator fun get(screen: String, name: String): ScreenStateEntry =
    find(screen, name) ?: throw UnknownScreenStateException(screen, name, this)

  /**
   * Looks up [screen]'s state [name] and opens it with the fixture sets the host can replay
   * ([ScreenStateEntry.open]).
   *
   * @throws UnknownScreenStateException when there is no such state.
   * @throws MissingScreenStateFixturesException when [fixtureSets] lacks the state's set.
   * @throws StaleScreenStateRegistryException when the registry is older than the screen file.
   */
  public fun open(screen: String, name: String, fixtureSets: Collection<String>): NamedState<*> =
    get(screen, name).open(fixtureSets)

  override fun toString(): String = "ScreenStateRegistry(${entries.size} states on ${screens.size} screens)"
}

/**
 * One [ScreenState]: where it is declared, what `GET /states` says about it, and [state], which
 * runs its declaring function.
 *
 * The fields other than [state] are read from source by the generator and are what the portal
 * lists, so a host can show and filter states without running app code.
 */
@ExperimentalKeliverNavScreenStateApi
public class ScreenStateEntry(
  /** The screen: its file's name without `.kt`, as `GET /states` names it. */
  public val screen: String,
  /** The state's name, from `@ScreenState(name)`; unique within [screen]. */
  public val name: String,
  /** The name of the function that declares the state. */
  public val function: String,
  /**
   * The route argument as written in source, for showing (`GET /states` calls this text `route`);
   * [state] gives the value.
   */
  public val routeSource: String,
  /**
   * The route's class as written (`ItemDetail` for `ItemDetail(id = "x")`, `Route.Home` for
   * `Route.Home`), or null when the route is neither a constructor call nor a name.
   */
  public val routeType: String?,
  /** The HTTP fixture set the state replays. */
  public val fixtures: String,
  private val declaration: () -> NamedState<*>,
) {
  init {
    require(screen.isNotBlank()) { "screen must not be blank" }
    require(name.isNotBlank()) { "name must not be blank" }
    require(fixtures.isNotBlank()) { "fixtures must name a fixture set" }
  }

  /**
   * Runs the declaring function: the route the screen opens with, and its fixture set. Each call
   * runs it again, so a route is never shared between two openings.
   *
   * @throws StaleScreenStateRegistryException when the function names a fixture set other than
   *   [fixtures]: the registry is older than the screen file, and must be regenerated.
   */
  public fun state(): NamedState<*> {
    val state = declaration()
    if (state.fixtures != fixtures) throw StaleScreenStateRegistryException(this, state.fixtures)
    return state
  }

  /**
   * [state], once the host has the state's fixture set: [fixtureSets] are the ids of the sets the
   * host can replay (a debug host's valid, unexpired sets; the relay's `httpFixturesDir`). A state
   * whose set is missing fails here, by name, before its presenter runs, rather than as a replay
   * miss the presenter might swallow.
   *
   * @throws MissingScreenStateFixturesException when [fixtures] is not in [fixtureSets].
   * @throws StaleScreenStateRegistryException as [state] does.
   */
  public fun open(fixtureSets: Collection<String>): NamedState<*> {
    if (fixtures !in fixtureSets) throw MissingScreenStateFixturesException(this, fixtureSets)
    return state()
  }

  override fun toString(): String =
    "ScreenStateEntry(screen=$screen, name=$name, function=$function, route=$routeSource, fixtures=$fixtures)"
}

/**
 * Why a named state could not be opened: the state is unknown ([UnknownScreenStateException]),
 * the host lacks its fixture set ([MissingScreenStateFixturesException]), or the registry is
 * older than the screen file ([StaleScreenStateRegistryException]). A host catches this one type
 * and shows the message; the subtypes say which case it was. Each names the [screen] and
 * [state] asked for.
 */
@ExperimentalKeliverNavScreenStateApi
public sealed class ScreenStateException(
  /** The screen asked for. */
  public val screen: String,
  /** The state asked for. */
  public val state: String,
  message: String,
) : RuntimeException(message)

/** A [ScreenStateRegistry] lookup for a state it does not have. */
@ExperimentalKeliverNavScreenStateApi
public class UnknownScreenStateException internal constructor(
  screen: String,
  state: String,
  registry: ScreenStateRegistry,
) : ScreenStateException(screen, state, unknownMessage(screen, state, registry))

/** A state opened on a host that does not have its fixture set ([ScreenStateEntry.open]). */
@ExperimentalKeliverNavScreenStateApi
public class MissingScreenStateFixturesException internal constructor(
  entry: ScreenStateEntry,
  available: Collection<String>,
) : ScreenStateException(
  entry.screen,
  entry.name,
  "${entry.screen}/${entry.name} replays fixture set '${entry.fixtures}', which this host does not have " +
    "(it has ${available.sorted().ifEmpty { listOf("none") }.joinToString()}); record it, " +
    "or add ${entry.fixtures}.json to the app's HTTP fixtures",
) {
  /** The fixture set the state replays. */
  public val fixtures: String = entry.fixtures
}

/**
 * A state whose declaring function no longer returns what the registry says ([ScreenStateEntry.state]):
 * the screen file changed and the registry was not regenerated.
 */
@ExperimentalKeliverNavScreenStateApi
public class StaleScreenStateRegistryException internal constructor(
  entry: ScreenStateEntry,
  /** The fixture set the declaring function returns now. */
  public val declaredFixtures: String,
) : ScreenStateException(
  entry.screen,
  entry.name,
  "${entry.screen}/${entry.name}: ${entry.function}() replays '$declaredFixtures' but the registry says " +
    "'${entry.fixtures}'; the registry is older than the screen file: regenerate it",
) {
  /** The fixture set the registry lists for the state. */
  public val registryFixtures: String = entry.fixtures
}

@OptIn(ExperimentalKeliverNavScreenStateApi::class)
private fun unknownMessage(screen: String, state: String, registry: ScreenStateRegistry): String {
  val states = registry.states(screen)
  return if (states.isEmpty()) {
    val screens = registry.screens.ifEmpty { listOf("none") }.joinToString()
    "no screen '$screen' declares states (screens with states: $screens)"
  } else {
    "screen '$screen' has no state '$state' (its states: ${states.joinToString { it.name }})"
  }
}
