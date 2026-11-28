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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/*
 * The guest API of NF5 (A), the cached first frame (docs/SHOW_BEFORE_COMPUTE.md 2.2). A2 covers the
 * guest's markers and the host's reading of them; recording, storing and drawing a frame are later
 * steps.
 *
 * Every function here emits something only when the host declares the feature it needs (I8); on any
 * other host the guest's batches are exactly what they are without this file.
 */

/** How much of a screen a stored frame keeps (2.2). There is no default: the author chooses. */
@ExperimentalKeliverHostFeaturesApi
public enum class FrameContent {
  /**
   * Every text and URL value is replaced by a placeholder unless its node is marked
   * [cached]. Structure and every non-text value remain.
   */
  Shell,

  /** Every value is kept, except subtrees marked [notCached]. */
  Whole,
}

/** What may be stored of a screen, and for how long. */
@ExperimentalKeliverHostFeaturesApi
public class FrameCachePolicy(
  public val content: FrameContent,
  public val maxAge: Duration,
) {
  init {
    require(maxAge.isFinite()) { "FrameCachePolicy.maxAge must be finite, not Duration.INFINITE" }
    require(maxAge.isPositive()) { "FrameCachePolicy.maxAge must be positive, was $maxAge" }
    require(maxAge <= MAX_AGE) { "FrameCachePolicy.maxAge must be at most $MAX_AGE, was $maxAge" }
  }

  public companion object {
    /** The longest a stored frame may be kept: a year. The protocol carries the age as a JavaScript number. */
    public val MAX_AGE: Duration = 365.days
  }

  override fun equals(other: Any?): Boolean =
    other is FrameCachePolicy && content == other.content && maxAge == other.maxAge

  override fun hashCode(): Int = content.hashCode() * 31 + maxAge.hashCode()

  override fun toString(): String = "FrameCachePolicy(content=$content, maxAge=$maxAge)"
}

/**
 * A marker modifier element the guest protocol adapter sends in the reserved range of
 * `dev.keliver.protocol.FrameCacheTags`. [fields] holds `String`, `Long`, `Boolean` and `null`
 * values only. Only this library creates one (the constructor is internal).
 *
 * @suppress For the guest protocol adapters only.
 */
@ExperimentalKeliverHostFeaturesApi
public abstract class FrameCacheMarker internal constructor() : Modifier.UnscopedElement {
  public abstract val tag: Int
  public abstract val fields: Map<String, Any?>
}

/**
 * Marks this node as the screen that may be shown from storage. The host stores nothing and draws
 * nothing for a screen without it. Put it on the screen's root widget, and on nothing else: a host
 * records a frame only when exactly one node carries it and that node is the root's direct child.
 * Forgetting it simply means the screen does not opt in. On a host without `keliver.cachedFrame@1`
 * it returns this modifier unchanged, so nothing is emitted.
 *
 * @param ready the guest's "first meaningful" signal; the host records and swaps when it goes true.
 * @param paneTitle announced when the live screen replaces the stored frame; stored, so not data.
 */
@ExperimentalKeliverHostFeaturesApi
@Composable
public fun Modifier.cachedFrame(
  policy: FrameCachePolicy,
  ready: Boolean,
  paneTitle: String,
): Modifier =
  if (KeliverHostFeatures.CACHED_FRAME_1 in LocalHostFeatures.current) {
    then(CachedFrameMarker(policy, ready, paneTitle))
  } else {
    this
  }

/** Keeps this node's text and URL values in a [FrameContent.Shell] frame. */
@ExperimentalKeliverHostFeaturesApi
@Composable
public fun Modifier.cached(): Modifier = markedWith(CachedMarker)

/** Drops this node's whole subtree from any stored frame. */
@ExperimentalKeliverHostFeaturesApi
@Composable
public fun Modifier.notCached(): Modifier = markedWith(NotCachedMarker)

@ExperimentalKeliverHostFeaturesApi
@Composable
private fun Modifier.markedWith(marker: FrameCacheMarker): Modifier =
  if (KeliverHostFeatures.CACHED_FRAME_1 in LocalHostFeatures.current) then(marker) else this

// Keep in step with dev.keliver.protocol.FrameCacheTags (a test in keliver-protocol-guest compares them).
internal const val CACHED_FRAME_TAG: Int = -4_600_001
internal const val CACHED_TAG: Int = -4_600_002
internal const val NOT_CACHED_TAG: Int = -4_600_003

@OptIn(ExperimentalKeliverHostFeaturesApi::class)
internal class CachedFrameMarker(
  private val policy: FrameCachePolicy,
  private val ready: Boolean,
  private val paneTitle: String,
) : FrameCacheMarker() {
  override val tag: Int get() = CACHED_FRAME_TAG
  override val fields: Map<String, Any?>
    get() = linkedMapOf(
      "content" to policy.content.name.lowercase(),
      "maxAgeMillis" to policy.maxAge.inWholeMilliseconds,
      "ready" to ready,
      "paneTitle" to paneTitle,
    )

  override fun equals(other: Any?): Boolean =
    other is CachedFrameMarker && policy == other.policy && ready == other.ready && paneTitle == other.paneTitle

  override fun hashCode(): Int = (policy.hashCode() * 31 + ready.hashCode()) * 31 + paneTitle.hashCode()

  override fun toString(): String = "CachedFrame($policy, ready=$ready, paneTitle=$paneTitle)"
}

@OptIn(ExperimentalKeliverHostFeaturesApi::class)
internal object CachedMarker :FrameCacheMarker() {
  override val tag: Int get() = CACHED_TAG
  override val fields: Map<String, Any?> get() = emptyMap()
  override fun toString(): String = "Cached"
}

@OptIn(ExperimentalKeliverHostFeaturesApi::class)
internal object NotCachedMarker :FrameCacheMarker() {
  override val tag: Int get() = NOT_CACHED_TAG
  override val fields: Map<String, Any?> get() = emptyMap()
  override fun toString(): String = "NotCached"
}

/**
 * Where [FrameScope] sends session and variant changes. The treehouse guest provides it once the
 * host's channel exists (a later NF5 step); until then there is none and the calls do nothing.
 */
@ExperimentalKeliverHostFeaturesApi
public interface FrameScopeSink {
  public fun sessionChanged(id: String?)
  public fun variantChanged(id: String?)
}

/** The sink [FrameScope] sends to, or `null`. Provided by the treehouse guest only. */
@ExperimentalKeliverHostFeaturesApi
@RedwoodCodegenApi
public val LocalFrameScopeSink: ProvidableCompositionLocal<FrameScopeSink?> =
  staticCompositionLocalOf { null }

/**
 * Tells the host which session and which variant (an app's own theme or language, held in the
 * guest) the screen belongs to, so frames of another session or variant are never drawn (2.9).
 * Both calls do nothing unless the host declares `keliver.frameScope@1`.
 */
@ExperimentalKeliverHostFeaturesApi
public class FrameScope internal constructor(private val sink: FrameScopeSink?) {
  /** A guest that holds the login reports its session at every start (null: signed out). */
  public fun sessionChanged(id: String?) {
    sink?.sessionChanged(id)
  }

  public fun variantChanged(id: String?) {
    sink?.variantChanged(id)
  }
}

/** The [FrameScope] of this composition: inert on a host without `keliver.frameScope@1`. */
@ExperimentalKeliverHostFeaturesApi
@OptIn(RedwoodCodegenApi::class)
@Composable
public fun rememberFrameScope(): FrameScope {
  // frameScope@1 requires cachedFrame@1: a host declaring it alone is treated as not declaring it.
  val features = LocalHostFeatures.current
  val enabled = KeliverHostFeatures.FRAME_SCOPE_1 in features &&
    KeliverHostFeatures.CACHED_FRAME_1 in features
  val sink = LocalFrameScopeSink.current
  return remember(enabled, sink) { FrameScope(if (enabled) sink else null) }
}
