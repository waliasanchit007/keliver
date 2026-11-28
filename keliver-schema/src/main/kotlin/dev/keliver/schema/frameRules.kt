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
package dev.keliver.schema

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.PROPERTY

// The frame rules: what the host may store of a screen's first frame and draw again at the next cold
// start before the guest has run (NF5 (A), docs/SHOW_BEFORE_COMPUTE.md 2.4). The host records the
// marked subtree of a screen at its `ready` edge and folds it into a snapshot; codegen turns these
// annotations into the schema's generated `<Schema>FrameRules`, which the fold and the recorder read.
//
// Nothing is replayable by default. A subtree with a widget or modifier that is not [Replayable] is
// never recorded, so an unannotated schema fails closed: its screens get no stored frame.
//
// The two content levels a screen chooses (`FrameContent`, docs/SHOW_BEFORE_COMPUTE.md 2.2):
// - `Whole` keeps every value, except [NeverCached] ones (and subtrees the guest marks `notCached()`).
// - `Shell` also replaces every text- and URL-bearing value with a placeholder: every property whose
//   type is or contains a String, except [StyleToken] ones (and texts the guest marks `cached()`).
//   `Shell` removes strings only: the structure and every non-text value remain. Codegen therefore
//   lists every non-String value of a replayable widget or modifier that is neither [NotData] nor
//   [NeverCached] (`<Schema>FrameRules.review.txt`), so each one is a reviewed choice.
//
// A property carries at most one of [NeverCached], [BlocksFrameWhenTrue], [StyleToken] and [NotData].

/**
 * The [Widget] or [Modifier] may be replayed from a stored frame: the host may store it and draw it
 * again, inert, before the guest runs. A subtree that contains a widget or modifier without it is
 * never recorded (the recorder reports `notReplayable:<name>`).
 *
 * Leave it off anything whose replay would show something the live screen would not show at that
 * moment: a dialog, sheet, popup or snackbar (it opens a window the frame cannot make inert), a
 * text field (its value is live input), a skeleton or loading placeholder (a stored loading state),
 * and an animation that a first composition would not snap to its end state.
 *
 * ```
 * @Replayable
 * @Widget(1)
 * data class Text(
 *   @Property(1) val text: String,
 * )
 * ```
 */
@Retention(RUNTIME)
@Target(CLASS)
public annotation class Replayable

/**
 * The [Widget] scrolls, or the [Modifier] makes the node it is applied to scroll. A frame is
 * recorded only while every such node in the recorded subtree is at its origin (its first item at
 * offset 0, or offset 0, or its first page), as its host widget reports through `ScrollOrigin`. A
 * node marked here whose host widget does not report its origin blocks the recording (fail closed),
 * so a scroller cannot be recorded scrolled by mistake.
 */
@Retention(RUNTIME)
@Target(CLASS)
public annotation class Scrolls

/**
 * The property's value is never stored, at either content level: the fold leaves it out, so a
 * replayed widget draws with the property's default. Use it for a value that must not reach the disk
 * (a secret, an input's current value) or that is wrong to draw again (a one-shot request).
 */
@Retention(RUNTIME)
@Target(PROPERTY)
public annotation class NeverCached

/**
 * Only on a `Boolean` property: while it is true, nothing is recorded (the recorder reports
 * `refreshing`). Use it for a transient state the stored frame must never show, such as a
 * pull-to-refresh in progress. A recorded value is therefore always `false`, so the property needs no
 * other classification.
 */
@Retention(RUNTIME)
@Target(PROPERTY)
public annotation class BlocksFrameWhenTrue

/**
 * The property's strings name a style or an asset from a fixed set (an icon's name, a font family,
 * a shape or an arrangement written as a code), not text, so `Shell` keeps the value as it is. Only
 * on a property whose type is or contains a String.
 *
 * Like every non-text value, a style token can still say something about the data (a status icon
 * chosen by the guest); a screen where that is sensitive keeps that part under `notCached()`.
 */
@Retention(RUNTIME)
@Target(PROPERTY)
public annotation class StyleToken

/**
 * The property is layout or style, not data (a size, a padding, an alignment, a colour constant, a
 * flag that changes how a value is drawn), so it is not on the review list of the values `Shell`
 * keeps. Only on a property whose type does not contain a String: one that does is either text
 * (replaced in `Shell`) or a [StyleToken].
 */
@Retention(RUNTIME)
@Target(PROPERTY)
public annotation class NotData
