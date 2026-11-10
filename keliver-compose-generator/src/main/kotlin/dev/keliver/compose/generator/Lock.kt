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
)

@Serializable
data class LockEntry(
  val tag: Int,
  /** The generated schema class (`GenText1`). */
  val name: String,
  /** Parameter name → property or children tag. */
  val params: Map<String, Int> = emptyMap(),
  /** The level at which this entry was appended. */
  val since: Int,
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
  private var nextWidgetTag = old.nextWidgetTag
  private var nextModifierTag = old.nextModifierTag
  private var appended = false
  private val newLevel = old.level + 1

  fun widget(decl: DeclInfo): LockEntry = entry(decl, widgets, isWidget = true)
  fun modifier(decl: DeclInfo): LockEntry = entry(decl, modifiers, isWidget = false)

  private fun entry(decl: DeclInfo, map: MutableMap<String, LockEntry>, isWidget: Boolean): LockEntry {
    map[decl.key]?.let { existing ->
      // A parameter the entry has not seen (never happens for a fixed signature, kept for safety).
      val missing = decl.params.map { it.name }.filter { it !in existing.params }
      if (missing.isEmpty()) return existing
      error("${decl.spec}: the lock entry ${existing.name} lacks $missing; a changed signature is a new key")
    }
    appended = true
    val base = (decl.owner ?: "") + decl.name.replaceFirstChar { it.uppercase() }
    val siblings = (widgets.values + modifiers.values).count { it.name.removePrefix("Gen").trimEnd { c -> c.isDigit() } == base }
    val name = "Gen$base${siblings + 1}"
    val params = decl.params.withIndex().associate { (i, p) -> p.name to i + 1 }
    val tag = if (isWidget) nextWidgetTag++ else nextModifierTag++
    return LockEntry(tag, name, params, since = newLevel).also { map[decl.key] = it }
  }

  fun build(): Lock = Lock(
    group = old.group,
    level = if (appended) newLevel else old.level,
    nextWidgetTag = nextWidgetTag,
    nextModifierTag = nextModifierTag,
    widgets = widgets.toSortedMap(),
    modifiers = modifiers.toSortedMap(),
  )
}

/** Appends only: every old key keeps its tag, name and parameter tags. */
fun checkAppendOnly(old: Lock, new: Lock): List<String> = buildList {
  for ((kind, o, n) in listOf(Triple("widget", old.widgets, new.widgets), Triple("modifier", old.modifiers, new.modifiers))) {
    for ((key, entry) in o) {
      val now = n[key]
      when {
        now == null -> add("$kind $key was removed (retire it with reserved tags instead)")
        now.tag != entry.tag || now.name != entry.name -> add("$kind $key changed its tag or name")
        entry.params.any { (p, t) -> now.params[p] != t } -> add("$kind $key changed a parameter tag")
      }
    }
  }
  if (new.nextWidgetTag < old.nextWidgetTag || new.nextModifierTag < old.nextModifierTag) add("a next-tag counter went backwards")
  if (new.level < old.level) add("the level went backwards")
}
