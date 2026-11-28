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
package dev.keliver.compose

/**
 * The names of the host features Keliver defines ([LocalHostFeatures]), in one place: guests check
 * these names, hosts declare them (`TreehouseApp.Spec.hostFeatures`), and the host templates'
 * declarations are tested against this list (docs/SHOW_BEFORE_COMPUTE.md, I8 and H0).
 *
 * A name is `<feature>@<level>`. A host declares a level only when it implements all of it; a
 * newer level is a new name, and a host that implements it declares the older levels it still
 * serves as well. Names outside `keliver.` are an app's own features, declared beside Keliver's in
 * the same set; Keliver neither defines nor checks them.
 *
 * Adding a feature: its constant here and its row in [requirements], side by side (a JVM test fails
 * when either is missing). Once the host templates implement it, the name goes into the declaration
 * of the Keliver line that has it, `src/line-<line>/kotlin/HostFeatures.kt` in
 * scripts/templates/production-host (one file for the Android and iOS hosts; the 0.4 line's
 * declares none).
 */
@ExperimentalKeliverHostFeaturesApi
public object KeliverHostFeatures {
  /**
   * keliver-nav's retained back stack on the host's container (NF1): the host's widget system
   * renders the retained container and wires its back dispatcher.
   */
  public const val NAV_RETAINED_CONTAINER_1: String = "keliver.nav.retainedContainer@1"

  /**
   * Gate A's press signal (NF3, prototype): the host renders the press modifier and sends its
   * events.
   */
  public const val PRESS_1: String = "keliver.press@1"

  /**
   * NF5 (A), the cached first frame: the host reads the marker modifiers of
   * `dev.keliver.protocol.FrameCacheTags` (consumed before widget dispatch, so they never reach a
   * widget or the mismatch handler). Required by [CachedFrame], [cached] and [notCached].
   */
  public const val CACHED_FRAME_1: String = "keliver.cachedFrame@1"

  /**
   * NF5 (A): the host accepts [FrameScope.sessionChanged] and [FrameScope.variantChanged] from the
   * guest and handles them as `KeliverHost.session()` does. Requires `keliver.cachedFrame@1`,
   * because only a host that stores frames has anything to delete.
   */
  public const val FRAME_SCOPE_1: String = "keliver.frameScope@1"

  /**
   * Schema-mismatch reports (MS4-04): the host reports each unknown widget, modifier, property,
   * children or event tag a loaded bundle uses once (keliver-treehouse-host's
   * `EventListener.schemaMismatch`), with the report reason `schema-mismatch`, instead of ignoring
   * it silently. It changes nothing a guest can do; a guest may read it to know its mismatches are
   * seen.
   */
  public const val SCHEMA_MISMATCH_REPORTS_1: String = "keliver.schemaMismatchReports@1"

  /**
   * Every feature Keliver defines, each with the features it requires (as `keliver.nav.reveal@1`
   * will require `keliver.nav.retainedContainer@2`). A host that declares a feature without all of
   * them is to be treated as not declaring it (docs/SHOW_BEFORE_COMPUTE.md 3.2), and Keliver's
   * template tests fail such a declaration ([missingRequirements]).
   */
  public val requirements: Map<String, Set<String>> = mapOf(
    NAV_RETAINED_CONTAINER_1 to emptySet(),
    PRESS_1 to emptySet(),
    CACHED_FRAME_1 to emptySet(),
    FRAME_SCOPE_1 to setOf(CACHED_FRAME_1),
    SCHEMA_MISMATCH_REPORTS_1 to emptySet(),
  )

  /** Every name Keliver defines. */
  public val all: Set<String> get() = requirements.keys

  /**
   * For each feature in [declared] whose requirements [declared] does not meet, the features it
   * lacks. Empty when every declared feature has what it requires. Names Keliver does not define
   * (an app's own features) are not checked.
   */
  public fun missingRequirements(declared: Set<String>): Map<String, Set<String>> =
    missingRequirements(declared, requirements)

  internal fun missingRequirements(
    declared: Set<String>,
    requirements: Map<String, Set<String>>,
  ): Map<String, Set<String>> = buildMap {
    for (feature in declared) {
      val missing = requirements[feature].orEmpty() - declared
      if (missing.isNotEmpty()) put(feature, missing)
    }
  }
}
