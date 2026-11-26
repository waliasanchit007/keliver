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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.keliver.compose.ExperimentalKeliverHostFeaturesApi
import dev.keliver.compose.KeliverHostFeatures
import dev.keliver.compose.LocalHostFeatures

/**
 * The frames a retained back stack may use, provided once at the root of an app by code that knows
 * its schema ([LocalKeliverNavDefaults]). With them, [KeliverNavHost] retains by default: keliver-nav
 * knows no widgets, so without them it cannot hide a screen and nothing is retained.
 *
 * Which frame is used is decided at run time, per host (D2: one bundle runs on old and new hosts):
 * 1. [hostFrame], when the host says it has the container ([hostFrameFeature] is in the host's
 *    features, `dev.keliver.compose.LocalHostFeatures`): back is shown by the host in the frame of
 *    the press;
 * 2. otherwise [frame], a frame of plain widgets every host of the schema has: back goes through
 *    the guest but composes nothing;
 * 3. otherwise no retention, as before. A host that does not declare features (any host older than
 *    0.5.0) never receives [hostFrame]'s widgets, so it cannot fail on them.
 *
 * Providing them changes every top-level `KeliverNavHost(controller)` under them, app-wide:
 * - A covered screen stays composed. Code that assumed a screen is composed again when shown no
 *   longer runs on back: a `DisposableEffect` or `LaunchedEffect` that sets a title or a status-bar
 *   style, subscribes to something on the host, or logs "screen viewed" ran once, when the screen
 *   was first shown, and the screen that was popped may have left its own value in place. Move such
 *   work to `LaunchedWhileVisible` (runs on every show, cancelled on hide) or `LaunchedOnReturn`
 *   (each return, not the first show), or opt the route out with `entryRetention`.
 * - Memory: up to [depth] hidden screens per host are kept (and, with [hostFrame], their native
 *   views on the host).
 * - A host nested in a screen (tabs, a flow) does not inherit them: each entry of a host sees
 *   [LocalKeliverNavDefaults] as null, so a nested host retains only where a screen provides
 *   defaults again (keep a reference where you provide them) or passes a retention itself. Nested
 *   retention multiplies: depth 2 at two levels can keep 3 × 3 = 9 screens composed.
 */
@ExperimentalKeliverNavRetentionApi
public class KeliverNavDefaults(
  public val hostFrame: KeliverNavHostFrame? = null,
  public val frame: KeliverNavFrame? = null,
  public val depth: Int = KeliverNavRetention.DEFAULT_DEPTH,
  public val hostFrameFeature: String = RETAINED_CONTAINER_FEATURE,
) {
  init {
    require(depth >= 0) { "depth must not be negative: $depth" }
  }

  /** The retention for a host with [hostFeatures], or null for none. */
  public fun retentionFor(hostFeatures: Set<String>): KeliverNavRetention? {
    if (depth == 0) return null
    if (hostFrame != null && hostFrameFeature in hostFeatures) return KeliverNavRetention(hostFrame, depth)
    if (frame != null) return KeliverNavRetention(frame, depth)
    return null
  }

  override fun equals(other: Any?): Boolean =
    other is KeliverNavDefaults &&
      hostFrame == other.hostFrame &&
      frame == other.frame &&
      depth == other.depth &&
      hostFrameFeature == other.hostFrameFeature

  override fun hashCode(): Int {
    var result = hostFrame.hashCode()
    result = 31 * result + frame.hashCode()
    result = 31 * result + depth
    return 31 * result + hostFrameFeature.hashCode()
  }

  override fun toString(): String =
    "KeliverNavDefaults(hostFrame=$hostFrame, frame=$frame, depth=$depth, hostFrameFeature=$hostFrameFeature)"

  public companion object {
    /**
     * The host feature of a container that implements [KeliverNavHostFrame]'s protocol (gate A's
     * `ExtraRetained`). A host declares it (`TreehouseApp.Spec.hostFeatures`) when its widget system
     * renders that container and its back dispatcher is wired. The name is Keliver's
     * `KeliverHostFeatures.NAV_RETAINED_CONTAINER_1` (`dev.keliver.compose`), its one place.
     */
    @OptIn(ExperimentalKeliverHostFeaturesApi::class)
    public const val RETAINED_CONTAINER_FEATURE: String = KeliverHostFeatures.NAV_RETAINED_CONTAINER_1
  }
}

/** The app's [KeliverNavDefaults]; null (the default) means [KeliverNavHost] retains nothing. */
@ExperimentalKeliverNavRetentionApi
public val LocalKeliverNavDefaults: ProvidableCompositionLocal<KeliverNavDefaults?> =
  staticCompositionLocalOf { null }

/**
 * The retention [KeliverNavHost] uses by default here: from [LocalKeliverNavDefaults], for this
 * host's features (see [KeliverNavDefaults]); null when there are no defaults.
 */
@OptIn(ExperimentalKeliverHostFeaturesApi::class)
@ExperimentalKeliverNavRetentionApi
@Composable
public fun defaultKeliverNavRetention(): KeliverNavRetention? {
  val defaults = LocalKeliverNavDefaults.current ?: return null
  val features = LocalHostFeatures.current
  return remember(defaults, features) { defaults.retentionFor(features) }
}

/**
 * How one route's entry is kept when another screen covers it (see the [KeliverNavHost] overload
 * that takes `entryRetention`).
 */
@ExperimentalKeliverNavRetentionApi
public enum class KeliverNavEntryRetention {
  /** Kept composed and hidden, within the retention's depth: back composes nothing. */
  Retain,

  /**
   * Leaves the composition when covered, as without retention, keeping its `rememberSaveable` state
   * for when it is shown again. For memory-heavy screens (a map, a camera, a large list).
   */
  Dispose,

  /**
   * Leaves the composition when covered and its saved state is dropped too: back shows it as new.
   * For sensitive screens (a PIN, card details) and forms that must start again empty.
   */
  Discard,
}

/** Counts [trimKeliverNavRetention] calls; every retained stack reads it. */
internal val retentionTrims = mutableIntStateOf(0)

/**
 * Memory pressure: every retained stack in this guest disposes its hidden entries at its next
 * composition, keeping only the screen on top. Their `rememberSaveable` state is kept (under
 * [KeliverNavHost]), and back composes them again, as without retention. Later screens are retained
 * again as usual.
 *
 * Call it when the host reports memory pressure (Android's `onTrimMemory` at
 * `TRIM_MEMORY_RUNNING_LOW` or above, iOS's memory warning), forwarded to the guest through one of
 * the app's host services.
 */
@ExperimentalKeliverNavRetentionApi
public fun trimKeliverNavRetention() {
  retentionTrims.intValue++
}
