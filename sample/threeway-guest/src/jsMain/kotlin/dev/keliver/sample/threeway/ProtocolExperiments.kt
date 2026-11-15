/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(RedwoodCodegenApi::class)

package dev.keliver.sample.threeway

import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.unit.TextUnit
import dev.keliver.protocol.ChangesSink
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Event
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.RedwoodVersion
import dev.keliver.protocol.WidgetTag
import dev.keliver.protocol.guest.GuestProtocolAdapter
import dev.keliver.protocol.guest.ProtocolMismatchHandler
import dev.keliver.protocol.guest.ProtocolWidget
import dev.keliver.protocol.guest.ProtocolWidgetSystemFactory
import dev.keliver.widget.Widget
import dev.keliver.widget.WidgetSystem
import kotlin.js.Date
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/*
 * The three-way benchmark's protocol experiments (docs/THREE_WAY_BENCHMARK.md, "Experiments"),
 * BENCHMARK-ONLY and off unless the route's `x` extra asks for them. Nothing here changes the
 * library: the guest's widget system is created through a factory that wraps the library's protocol
 * adapter (treehouse-guest's FastGuestProtocolAdapter on JS) in [ExperimentAdapter], which forwards
 * every call to it and
 *  - `split`: times every recording call (Date.now() before and after; each call is well under a
 *    millisecond, so the sum of the differences estimates the total, unbiased when the calls are
 *    not phase-locked to the clock's ticks) and counts them, per kind;
 *  - `skipdefaults`: does not forward a property of a widget that has never sent it when the value
 *    is the "not set" value the host also falls back to (null, Color.Unspecified,
 *    TextUnit.Unspecified), nor a new widget's empty modifier. Valid for these screens only: a few
 *    host properties start "unset" rather than null (contentDescription of an icon or an image, for
 *    example) and would need the value. A library version needs the generator, which writes both
 *    sides, to emit each property's host default.
 */

/** Totals since the last [reset]: counts and summed milliseconds of the adapter's recording calls. */
internal object ProtocolStats {
  var propertyChanges = 0
  var propertyMs = 0L
  var propertiesSkipped = 0
  var modifierChanges = 0
  var modifierElements = 0
  var modifierMs = 0L
  var modifiersSkipped = 0
  var structural = 0
  var structuralMs = 0L
  var creates = 0

  fun reset() {
    creates = 0
    propertyChanges = 0; propertyMs = 0; propertiesSkipped = 0
    modifierChanges = 0; modifierElements = 0; modifierMs = 0; modifiersSkipped = 0
    structural = 0; structuralMs = 0
  }

  /** As marks named `n_*`: the host logs them as values, not times. */
  fun asMarks(): Map<String, Long> = linkedMapOf(
    "n_props" to propertyChanges.toLong(),
    "n_propMs" to propertyMs,
    "n_propsSkipped" to propertiesSkipped.toLong(),
    "n_mods" to modifierChanges.toLong(),
    "n_modElems" to modifierElements.toLong(),
    "n_modMs" to modifierMs,
    "n_modsSkipped" to modifiersSkipped.toLong(),
    "n_creates" to creates.toLong(),
    "n_struct" to structural.toLong(),
    "n_structMs" to structuralMs,
  )
}

internal class ExperimentWidgetSystemFactory(
  private val delegate: ProtocolWidgetSystemFactory,
  private val experiments: () -> Set<String>,
) : ProtocolWidgetSystemFactory {
  override fun create(guestAdapter: GuestProtocolAdapter, mismatchHandler: ProtocolMismatchHandler): WidgetSystem<Unit> {
    val x = experiments()
    val time = "split" in x
    val skip = "skipdefaults" in x
    // As shipped: the library's adapter, unwrapped.
    if (!time && !skip) return delegate.create(guestAdapter, mismatchHandler)
    return delegate.create(ExperimentAdapter(guestAdapter, time, skip), mismatchHandler)
  }

  override fun <T : Modifier.Element> modifierTagAndSerializationStrategy(element: T): Pair<ModifierTag, SerializationStrategy<T>?> =
    delegate.modifierTagAndSerializationStrategy(element)
}

private class ExperimentAdapter(
  private val delegate: GuestProtocolAdapter,
  private val time: Boolean,
  private val skipDefaults: Boolean,
) : GuestProtocolAdapter(RedwoodVersion("0.0.0-unused")) {
  /** `id:tag` of every property sent, and ids whose modifier was sent (skipdefaults only). */
  private val sent = HashSet<String>()
  private val modifierSent = HashSet<Int>()

  override val json: Json get() = delegate.json
  override val widgetSystem: WidgetSystem<Unit> get() = delegate.widgetSystem
  override val root: Widget.Children<Unit> get() = delegate.root

  override fun initChangesSink(changesSink: ChangesSink) = delegate.initChangesSink(changesSink)
  override fun emitChanges() = delegate.emitChanges()
  override fun sendEvent(event: Event) = delegate.sendEvent(event)
  override fun nextId(): Id = delegate.nextId()

  private inline fun structural(block: () -> Unit) {
    if (!time) return block()
    val start = Date.now()
    block()
    ProtocolStats.structuralMs += (Date.now() - start).toLong()
    ProtocolStats.structural++
  }

  override fun appendCreate(id: Id, tag: WidgetTag) = structural {
    ProtocolStats.creates++
    delegate.appendCreate(id, tag)
  }

  override fun <T> appendPropertyChange(id: Id, widgetTag: WidgetTag, propertyTag: PropertyTag, serializer: KSerializer<T>, value: T) {
    if (skipDefaults) {
      val key = "${id.value}:${propertyTag.value}"
      if (key !in sent) {
        if (value == null || value == Color.Unspecified || value == TextUnit.Unspecified) {
          ProtocolStats.propertiesSkipped++
          return
        }
        sent += key
      }
    }
    if (!time) return delegate.appendPropertyChange(id, widgetTag, propertyTag, serializer, value)
    val start = Date.now()
    delegate.appendPropertyChange(id, widgetTag, propertyTag, serializer, value)
    ProtocolStats.propertyMs += (Date.now() - start).toLong()
    ProtocolStats.propertyChanges++
  }

  override fun appendPropertyChange(id: Id, widgetTag: WidgetTag, propertyTag: PropertyTag, value: Boolean) =
    structural { delegate.appendPropertyChange(id, widgetTag, propertyTag, value) }

  override fun appendPropertyChange(id: Id, widgetTag: WidgetTag, propertyTag: PropertyTag, value: UInt) =
    structural { delegate.appendPropertyChange(id, widgetTag, propertyTag, value) }

  override fun appendModifierChange(id: Id, value: Modifier) {
    if (skipDefaults && id.value !in modifierSent) {
      var empty = true
      value.forEach { empty = false }
      if (empty) {
        ProtocolStats.modifiersSkipped++
        return
      }
      modifierSent += id.value
    }
    if (!time) return delegate.appendModifierChange(id, value)
    var elements = 0
    value.forEach { elements++ }
    val start = Date.now()
    delegate.appendModifierChange(id, value)
    ProtocolStats.modifierMs += (Date.now() - start).toLong()
    ProtocolStats.modifierChanges++
    ProtocolStats.modifierElements += elements
  }

  override fun appendAdd(id: Id, tag: ChildrenTag, index: Int, child: ProtocolWidget) =
    structural { delegate.appendAdd(id, tag, index, child) }

  override fun appendMove(id: Id, tag: ChildrenTag, fromIndex: Int, toIndex: Int, count: Int) =
    structural { delegate.appendMove(id, tag, fromIndex, toIndex, count) }

  override fun appendRemove(id: Id, tag: ChildrenTag, index: Int, child: ProtocolWidget) =
    structural { delegate.appendRemove(id, tag, index, child) }
}
