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
package dev.keliver.compose.gatea

import dev.keliver.schema.Children
import dev.keliver.schema.Modifier
import dev.keliver.schema.Property
import dev.keliver.schema.Widget

/*
 * Keliver extras (docs/COMPOSE_GENERATOR_DESIGN.md §14): hand-written widgets that are not Compose
 * API, imported from dev.keliver.compose.extras so a reader sees they are not Compose. Tags from 500
 * up; the generated group's lock assigns from 1.
 */

/** An animated skeleton placeholder, drawn by the host (its renderer is the one hand-written one). */
@Widget(500)
public data class ExtraShimmer(
  @Property(1) val shape: dev.keliver.compose.ui.graphics.Shape,
  @Property(1000) val modifierEvent: ((kotlin.Int) -> Unit)? = null,
)

/**
 * One item of a generated lazy list's window (docs/COMPOSE_GENERATOR_DESIGN.md §16): the guest's
 * item key (typed, as a string) and the item's content, which the host renders in the real
 * `LazyItemScope`. Structure only; screens never name it.
 */
@Widget(501)
public data class ExtraLazyItem(
  @Property(1) val key: String?,
  @Children(1) val content: GenLazyItemScope.() -> Unit,
)

/**
 * An animated reference (docs/COMPOSE_GENERATOR_DESIGN.md §10, Gate B-e): the guest's
 * `animate*AsState` value for [param] of the generated element that follows this one, or, at the
 * end of a widget's chain, of the widget itself. The host wraps that argument in the real
 * `animate*AsState` ([ref] carries the id, spec and label). Structure only; screens never name it.
 * A host built before it ignores it (an unknown modifier) and renders the target with no animation.
 */
@Modifier(500)
public data class ExtraAnimated(
  val param: String,
  val ref: dev.keliver.compose.animation.core.AnimatedRef,
)

/**
 * A retained back stack whose back the host shows (NF1; keliver-nav's `KeliverNavHostFrame`).
 * [entries] are [ExtraRetainedEntry]s, bottom first, and the host shows the last one. The others
 * stay in the host's tree, so their native state (a list's scroll position) survives, but they are
 * not measured, placed or drawn, take no input, hold no focus and expose no semantics.
 *
 * On a system back press, when the shown entry is not the first and its
 * [ExtraRetainedEntry.hostBack] is set, the host shows the entry below at once, in the frame of the
 * press, and sends [onBack] with that entry's key. It keeps showing that entry until [backHandled]
 * reaches the number of [onBack] calls it has sent; from then on it shows the guest's last entry
 * again, so the guest's state always wins. Structure only; screens never name it.
 */
@Widget(502)
public data class ExtraRetained(
  /** How many [onBack] calls the guest has handled (confirmed or refused). */
  @Property(1) val backHandled: Int,
  /** The host went back on its own and now shows the entry with this key. */
  @Property(2) val onBack: (String) -> Unit,
  @Children(1) val entries: () -> Unit,
)

/** One entry of an [ExtraRetained] stack. Structure only; screens never name it. */
@Widget(503)
public data class ExtraRetainedEntry(
  /** The guest's key for this entry, unique in its stack. */
  @Property(1) val key: String,
  /**
   * Whether the host may go back from this entry on its own: the guest can pop it, and no
   * `BackHandler` in it was enabled when it was last shown.
   */
  @Property(2) val hostBack: Boolean,
  @Children(1) val content: GenBoxScope.() -> Unit,
)
