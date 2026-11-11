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
package dev.keliver.compose.generator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The append-only lock (docs/COMPOSE_GENERATOR_DESIGN.md §3). A key (a declaration's canonical
 * signature) never changes its tag or class name, a parameter never changes its tag, and the
 * group's [level] grows by one whenever a generation appends anything.
 */
@Serializable
data class Lock(
  val group: String,
  val level: Int = 0,
  val nextWidgetTag: Int = 1,
  val nextModifierTag: Int = 1,
  val widgets: Map<String, LockEntry> = emptyMap(),
  val modifiers: Map<String, LockEntry> = emptyMap(),
  val nextFactoryTag: Int = 1,
  /** `*Defaults` factories (`ButtonDefaults.buttonColors(…)`): a call record carries the tag. */
  val factories: Map<String, LockEntry> = emptyMap(),
)

@Serializable
data class LockEntry(
  val tag: Int,
  /** The generated schema class (`GenText1`). */
  val name: String,
  /**
   * Parameter name → property or children tag: Compose's parameters by position at first
   * generation, then synthetic ones (`content.before` for a lazy list's window) appended with the
   * next free tag.
   */
  val params: Map<String, Int> = emptyMap(),
  /** The level at which this entry was appended. */
  val since: Int,
  /**
   * The parameters that travel (a dropped parameter has a tag but no wire property), each with the
   * level it started travelling at: a parameter that becomes supported later (its type gained a
   * codec) is appended here at the new level, so a host built before it knows not to expect it.
   * Null in a lock written before Gate B; filled at the entry's own level on the next generation.
   */
  val wire: Map<String, Int>? = null,
)

val lockJson = Json {
  prettyPrint = true
  prettyPrintIndent = "  "
  encodeDefaults = true
}

/** The tag reserved on every generated widget for the modifier-event channel (§8). */
const val MODIFIER_EVENT_TAG = 1000

class LockBuilder(private val old: Lock) {
  private val widgets = old.widgets.toMutableMap()
  private val modifiers = old.modifiers.toMutableMap()
  private val factories = old.factories.toMutableMap()
  private var nextWidgetTag = old.nextWidgetTag
  private var nextModifierTag = old.nextModifierTag
  private var nextFactoryTag = old.nextFactoryTag

  /** A `*Defaults` factory overload's entry (tag 0 is the explicit-null marker, so tags start at 1). */
  fun factory(decl: DeclInfo, wire: List<String>): LockEntry {
    factories[decl.key]?.let { existing ->
      val oldWire = existing.wire ?: wire.associateWith { existing.since }
      val newWire = wire.filter { it !in oldWire }
      if (newWire.isEmpty() && existing.wire != null) return existing
      if (newWire.isNotEmpty()) appended = true
      return existing.copy(wire = oldWire + newWire.associateWith { newLevel }).also { factories[decl.key] = it }
    }
    appended = true
    val base = "${decl.owner}.${decl.name}"
    val name = base + (factories.values.count { it.name.trimEnd { c -> c.isDigit() } == base } + 1)
    val params = decl.params.withIndex().associate { (i, p) -> p.name to i + 1 }
    return LockEntry(nextFactoryTag++, name, params, since = newLevel, wire = wire.associateWith { newLevel })
      .also { factories[decl.key] = it }
  }
  private var appended = false
  private val newLevel = old.level + 1

  /**
   * The entry for [decl] with [wire] travelling parameters and [synthetic] extra tagged ones: the
   * existing entry (appending any synthetic parameter or newly travelling one), or a new one.
   */
  fun widget(decl: DeclInfo, wire: List<String> = decl.params.map { it.name }, synthetic: List<String> = emptyList()): LockEntry =
    entry(decl, widgets, isWidget = true, wire, synthetic)

  fun modifier(decl: DeclInfo, wire: List<String> = decl.params.map { it.name }): LockEntry =
    entry(decl, modifiers, isWidget = false, wire, emptyList())

  /** The entry a declaration already has, without appending one. */
  fun existing(decl: DeclInfo): LockEntry? = (if (decl.kind == DeclKind.WIDGET) widgets else modifiers)[decl.key]

  private fun entry(decl: DeclInfo, map: MutableMap<String, LockEntry>, isWidget: Boolean, wire: List<String>, synthetic: List<String>): LockEntry {
    map[decl.key]?.let { existing ->
      // A parameter the entry has not seen (never happens for a fixed signature, kept for safety).
      val missing = decl.params.map { it.name }.filter { it !in existing.params }
      if (missing.isNotEmpty()) error("${decl.spec}: the lock entry ${existing.name} lacks $missing; a changed signature is a new key")
      var next = (existing.params.values.maxOrNull() ?: 0) + 1
      val newSynthetic = synthetic.filter { it !in existing.params }
      val params = existing.params + newSynthetic.associateWith { next++ }
      // A lock from before wire tracking: the entry's current wire shape is the one it was created with.
      val oldWire = existing.wire ?: wire.associateWith { existing.since }
      val newWire = (wire + synthetic).filter { it !in oldWire }
      if (newSynthetic.isNotEmpty() || newWire.isNotEmpty()) appended = true
      val updated = existing.copy(params = params, wire = oldWire + newWire.associateWith { newLevel })
      map[decl.key] = updated
      return updated
    }
    appended = true
    val base = (decl.owner ?: "") + decl.name.replaceFirstChar { it.uppercase() }
    val siblings = (widgets.values + modifiers.values).count { it.name.removePrefix("Gen").trimEnd { c -> c.isDigit() } == base }
    val name = "Gen$base${siblings + 1}"
    val params = decl.params.withIndex().associate { (i, p) -> p.name to i + 1 } +
      synthetic.withIndex().associate { (i, s) -> s to decl.params.size + i + 1 }
    val tag = if (isWidget) nextWidgetTag++ else nextModifierTag++
    return LockEntry(tag, name, params, since = newLevel, wire = (wire + synthetic).associateWith { newLevel })
      .also { map[decl.key] = it }
  }

  /** Fills a hidden entry's wire shape (from before wire tracking) without appending. */
  fun keep(decl: DeclInfo, wire: List<String>): LockEntry {
    val map = if (decl.kind == DeclKind.WIDGET) widgets else modifiers
    val existing = map.getValue(decl.key)
    if (existing.wire != null) return existing
    return existing.copy(wire = wire.associateWith { existing.since }).also { map[decl.key] = it }
  }

  fun build(): Lock = Lock(
    group = old.group,
    level = if (appended) newLevel else old.level,
    nextWidgetTag = nextWidgetTag,
    nextModifierTag = nextModifierTag,
    widgets = widgets.toSortedMap(),
    modifiers = modifiers.toSortedMap(),
    nextFactoryTag = nextFactoryTag,
    factories = factories.toSortedMap(),
  )
}

/** Appends only: every old key keeps its tag, name and parameter tags. */
fun checkAppendOnly(old: Lock, new: Lock): List<String> = buildList {
  val kinds = listOf(
    Triple("widget", old.widgets, new.widgets),
    Triple("modifier", old.modifiers, new.modifiers),
    Triple("factory", old.factories, new.factories),
  )
  for ((kind, o, n) in kinds) {
    for ((key, entry) in o) {
      val now = n[key]
      when {
        now == null -> add("$kind $key was removed (retire it with reserved tags instead)")
        now.tag != entry.tag || now.name != entry.name -> add("$kind $key changed its tag or name")
        entry.params.any { (p, t) -> now.params[p] != t } -> add("$kind $key changed a parameter tag")
        entry.wire.orEmpty().any { (p, level) -> now.wire?.get(p) != level } -> add("$kind $key stopped sending a parameter, or changed when it started")
        now.since != entry.since -> add("$kind $key changed its level")
      }
    }
  }
  if (new.nextWidgetTag < old.nextWidgetTag || new.nextModifierTag < old.nextModifierTag || new.nextFactoryTag < old.nextFactoryTag) {
    add("a next-tag counter went backwards")
  }
  if (new.level < old.level) add("the level went backwards")
}
