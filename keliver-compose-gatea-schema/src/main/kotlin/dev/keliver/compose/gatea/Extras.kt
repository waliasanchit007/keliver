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
package dev.keliver.compose.gatea

import dev.keliver.schema.Children
import dev.keliver.schema.Modifier
import dev.keliver.schema.NotData
import dev.keliver.schema.Property
import dev.keliver.schema.Replayable
import dev.keliver.schema.StyleToken
import dev.keliver.schema.Widget

/*
 * Keliver extras (docs/COMPOSE_GENERATOR_DESIGN.md §14): hand-written widgets that are not Compose
 * API, imported from dev.keliver.compose.extras so a reader sees they are not Compose. Tags from 500
 * up; the generated group's lock assigns from 1.
 *
 * NF5's frame rules (docs/SHOW_BEFORE_COMPUTE.md 2.4) are written here by hand, as the generator
 * writes them on the generated members (keliver-compose-generator's FrameRules.kt): every extra is
 * replayable except the skeleton.
 */

/**
 * An animated skeleton placeholder, drawn by the host (its renderer is the one hand-written one).
 * Not replayable: a skeleton on screen at `ready` means part of the screen is still loading, so a
 * stored frame would store a loading state (docs/SHOW_BEFORE_COMPUTE.md 2.4).
 */
@Widget(500)
public data class ExtraShimmer(
  @Property(1) val shape: dev.keliver.compose.ui.graphics.Shape,
  @Property(1000) val modifierEvent: ((kotlin.Int) -> Unit)? = null,
)

/**
 * One item of a generated lazy list's window (docs/COMPOSE_GENERATOR_DESIGN.md §16): the guest's
 * item key (typed, as a string) and the item's content, which the host renders in the real
 * `LazyItemScope`. Structure only; screens never name it. Its key is a String, so `Shell` replaces it
 * (the fold stores keys by position in both levels, docs/SHOW_BEFORE_COMPUTE.md 2.4).
 */
@Replayable
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
 * Replayable: a first composition's `animate*AsState` starts at its target, so a replay snaps.
 */
@Replayable
@Modifier(500)
public data class ExtraAnimated(
  /** A parameter's name: a code. */
  @StyleToken val param: String,
  /** An id, a spec and a debug label: codes. */
  @StyleToken val ref: dev.keliver.compose.animation.core.AnimatedRef,
)

/**
 * The host's press signal (NF3; keliver-nav's pre-composition on touch-down): the host sends
 * [onPress] (a modifier event handle, as a clickable's) when a finger goes down on the element
 * (inside a scroll container, once the finger has stayed still for a moment), and [onCancel] when
 * that gesture ends, whatever ended it: after the click if there was one, or when the finger moved
 * out, a scroll took the gesture, another element took the click, or the gesture was cancelled.
 * Both are sent on the event path a click takes, so the press reaches the guest before the click
 * and without waiting for it. Structure only; screens never name it.
 *
 * D2: a host declares it as the host feature `keliver.press@1` and a guest sends it only to a host
 * that does. A host built before it would ignore it as an unknown modifier, and a guest then falls
 * back to the click.
 */
@Replayable
@Modifier(501)
public data class ExtraPress(
  /** Event handles: wire bookkeeping. */
  @NotData val onPress: Int,
  @NotData val onCancel: Int,
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
 *
 * D2: a host without tags 502/503 THROWS while applying the batch that creates them (the parent's
 * children change names a node it skipped), which takes down the whole content. Extras are outside
 * the lock and nothing gates them yet; gate A is prototype-only and unpublished, so no shipped host
 * meets such a bundle. Before it leaves the prototype, extras must be in the versioned surface, the
 * index's widgetVersion gate must read that number, and a test must show an older host refusing the
 * bundle at selection.
 *
 * Replayable: a frame is recorded only while it shows its first entry, and the fold stores the
 * shown entry only (docs/SHOW_BEFORE_COMPUTE.md 2.3).
 */
@Replayable
@Widget(502)
public data class ExtraRetained(
  /** How many [onBack] calls the guest has handled (confirmed or refused). Back bookkeeping. */
  @Property(1) @NotData val backHandled: Int,
  /** The host went back on its own and now shows the entry with this key. */
  @Property(2) val onBack: (String) -> Unit,
  @Children(1) val entries: () -> Unit,
)

/** One entry of an [ExtraRetained] stack. Structure only; screens never name it. */
@Replayable
@Widget(503)
public data class ExtraRetainedEntry(
  /** The guest's key for this entry, unique in its stack. */
  @Property(1) val key: String,
  /**
   * Whether the host may go back from this entry on its own: the guest can pop it, and no
   * `BackHandler` in it was enabled when it was last shown. Back bookkeeping.
   */
  @Property(2) @NotData val hostBack: Boolean,
  @Children(1) val content: GenBoxScope.() -> Unit,
)
