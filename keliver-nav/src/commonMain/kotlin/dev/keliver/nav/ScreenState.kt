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
 * A named state is a typed route plus the name of an HTTP fixture set. Opening
 * it runs the screen's real presenter for [NamedState.route]; only the
 * `HostHttp` capability is replaced, by replay of [NamedState.fixtures].
 *
 * Declare states next to the screen, one function per state, each returning a
 * [NamedState] built from literals:
 *
 * ```
 * @ScreenState("review")
 * fun review() = NamedState(route = ItemDetail(id = "sample"), fixtures = "review")
 * ```
 *
 * The portal reads these declarations from source: it lists them per screen,
 * and the editor previews a state with [NamedState.fixtures] as the persona's
 * HTTP fixture set. [name] must be unique within the screen file.
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
