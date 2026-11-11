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

/**
 * Marks a function as a named state of the screen declared in the same file.
 *
 * A named state is a typed route plus the name of an HTTP fixture set.
 *
 * Declare states next to the screen, one top-level function per state, taking
 * no parameters and returning a [NamedState] built from literals:
 *
 * ```
 * @ScreenState("review")
 * fun review() = NamedState(route = ItemDetail(id = "sample"), fixtures = "review")
 * ```
 *
 * [name] must be unique within the screen file.
 *
 * **What exists today:** the portal reads these declarations from source and
 * lists them per screen (`GET /states`); a declaration it can't read is listed
 * as a problem, not skipped.
 *
 * **Planned (not built yet):** opening a state, in the editor or on a debug
 * device, will run the screen's real presenter for [NamedState.route] with only
 * the `HostHttp` capability replaced, by replay of [NamedState.fixtures].
 *
 * **Retention is [AnnotationRetention.BINARY], on purpose.** Guest code runs as
 * Kotlin/JS, which has no runtime annotation reflection, so `RUNTIME` would give
 * a device nothing to look states up with. The route for a device is a
 * generated registry: a later codegen step reads these annotations (from source,
 * or from compiled class files and klibs, which `BINARY` keeps them in) and
 * emits the list of states as ordinary code. Changing to `RUNTIME` later would
 * not break callers; the reverse would.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class ScreenState(
  /** The state's name, unique per screen (for example `"review"` or `"empty-cart"`). */
  val name: String,
)

/**
 * The value a [ScreenState] function returns: the typed [route] that opens the
 * screen (D8: routes are data, typically a `@Serializable` class of the app's
 * route type) and the name of the HTTP fixture set the state replays.
 */
public class NamedState<R : Any>(
  /** The route the screen is opened with. */
  public val route: R,
  /** The name of the app's HTTP fixture set to replay; never empty. */
  public val fixtures: String,
) {
  init {
    require(fixtures.isNotBlank()) { "fixtures must name a fixture set" }
  }

  override fun equals(other: Any?): Boolean =
    other is NamedState<*> && route == other.route && fixtures == other.fixtures

  override fun hashCode(): Int = 31 * route.hashCode() + fixtures.hashCode()

  override fun toString(): String = "NamedState(route=$route, fixtures=$fixtures)"
}
