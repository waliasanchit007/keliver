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
package dev.keliver.treehouse

import dev.drewhamilton.poko.Poko
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.EventTag
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.WidgetTag
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.ObjCName

/**
 * Opt-in for [SchemaMismatch] and [EventListener.schemaMismatch] (MS4-04): new in 0.5.0, and later
 * features add kinds, so their shape may change in any release.
 */
@RequiresOptIn("Keliver's schema-mismatch reports are experimental and may change in any release.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class ExperimentalKeliverSchemaMismatchApi

/**
 * A tag the guest's code used that this host's schema does not have: the guest was built against
 * a newer schema than the host's, or uses a group the host was built without. The host ignores
 * it, as it always has: the widget is not created, the modifier or property has no effect, and
 * the children or the event are dropped. [EventListener.schemaMismatch] receives each distinct
 * mismatch once per code session (docs/COMPOSE_GENERATOR_DESIGN.md section 12, "inside the gate").
 *
 * Two mismatches are the same one when they are equal: [Widget] and [Modifier] by their tag;
 * [Property], [Children] and [Event] by their tag and their widget's tag. [toString] names the
 * mismatch by kind and tags only (`unknown property 3 of widget 12`), so it can go into a report.
 *
 * Experimental: new kinds may be added in any release (later Keliver features report failures that
 * are not tags through this channel), so a `when` over it needs an `else`.
 */
@ExperimentalKeliverSchemaMismatchApi
@ObjCName("SchemaMismatch", exact = true)
public sealed class SchemaMismatch {
  /** The guest created a widget whose [tag] this host does not know. */
  @Poko
  @ExperimentalKeliverSchemaMismatchApi
  public class Widget(public val tag: WidgetTag) : SchemaMismatch() {
    override fun toString(): String = "unknown widget ${tag.value}"
  }

  /** The guest set a modifier whose [tag] this host does not know. */
  @Poko
  @ExperimentalKeliverSchemaMismatchApi
  public class Modifier(public val tag: ModifierTag) : SchemaMismatch() {
    override fun toString(): String = "unknown modifier ${tag.value}"
  }

  /** The guest set a property [tag] that this host's widget [widgetTag] does not have. */
  @Poko
  @ExperimentalKeliverSchemaMismatchApi
  public class Property(public val widgetTag: WidgetTag, public val tag: PropertyTag) : SchemaMismatch() {
    override fun toString(): String = "unknown property ${tag.value} of widget ${widgetTag.value}"
  }

  /** The guest changed children [tag] that this host's widget [widgetTag] does not have. */
  @Poko
  @ExperimentalKeliverSchemaMismatchApi
  public class Children(public val widgetTag: WidgetTag, public val tag: ChildrenTag) : SchemaMismatch() {
    override fun toString(): String = "unknown children ${tag.value} of widget ${widgetTag.value}"
  }

  /**
   * The host sent an event [tag] for widget [widgetTag] that the guest's schema does not have (the
   * host is the newer one). An event for a widget that no longer exists is not a mismatch
   * ([EventListener.unknownEventNode]).
   */
  @Poko
  @ExperimentalKeliverSchemaMismatchApi
  public class Event(public val widgetTag: WidgetTag, public val tag: EventTag) : SchemaMismatch() {
    override fun toString(): String = "unknown event ${tag.value} of widget ${widgetTag.value}"
  }
}

/**
 * The mismatches one code session has met, each once. They arrive on any thread (Zipline's or the
 * UI thread), so the set is swapped atomically. It holds at most the distinct tags of one guest's
 * schema.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalKeliverSchemaMismatchApi::class)
internal class SchemaMismatches {
  private val seen = AtomicReference(emptySet<SchemaMismatch>())

  /** True the first time [mismatch] is added, false every time after. */
  fun add(mismatch: SchemaMismatch): Boolean {
    while (true) {
      val before = seen.load()
      if (mismatch in before) return false
      if (seen.compareAndSet(before, before + mismatch)) return true
    }
  }
}
