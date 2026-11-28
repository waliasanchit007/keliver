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
package dev.keliver.compose.generator

import kotlinx.serialization.Serializable

/**
 * The shared `values` lock (docs/MS4_PLAN.md MS4-42, section 2.2): one append-only lock for every
 * value a guest builds and the host rebuilds by replaying the call that made it. Every widget group
 * depends on it, and it is "the one serial point every group appends to": appends are small and
 * mechanical, and parallel appends resolve by merging `origin/main` and regenerating (never by
 * hand-merging this file).
 *
 * Keyed by the Compose declaration ([ProducerKey]), so a value's tag does not depend on which group
 * first used it. A key never changes its tag, a parameter never changes when it started to travel.
 */
@Serializable
data class ValuesLock(
  val level: Int = 0,
  val nextTag: Int = 1,
  /** Producers (constructors, factories, constants) and receiver members, by [ProducerKey]. */
  val producers: Map<String, ValuesEntry> = emptyMap(),
)

@Serializable
data class ValuesEntry(
  /** The tag a call record carries on the wire (`{"call":<tag>,…}`). */
  val tag: Int,
  /** The level at which this entry was appended. */
  val since: Int,
  /**
   * The parameters that travel, each with the level it started travelling at (a parameter whose
   * type gained a mirror later is appended here at the new level, as in a widget's wire).
   */
  val wire: Map<String, Int> = emptyMap(),
)

class ValuesLockBuilder(private val old: ValuesLock) {
  private val producers = old.producers.toMutableMap()
  private var nextTag = old.nextTag
  private var appended = false
  private val newLevel = old.level + 1

  /** The entry for [key] travelling [wire] parameters: the existing one (plus any newly travelling parameter), or a new one. */
  fun producer(key: String, wire: List<String>): ValuesEntry {
    producers[key]?.let { existing ->
      val added = wire.filter { it !in existing.wire }
      if (added.isEmpty()) return existing
      appended = true
      return existing.copy(wire = existing.wire + added.associateWith { newLevel }).also { producers[key] = it }
    }
    appended = true
    return ValuesEntry(nextTag++, newLevel, wire.associateWith { newLevel }).also { producers[key] = it }
  }

  fun build(): ValuesLock = ValuesLock(
    level = if (appended) newLevel else old.level,
    nextTag = nextTag,
    producers = producers.toSortedMap(),
  )
}

/** Appends only: every old key keeps its tag and its parameters' levels. */
fun checkValuesAppendOnly(old: ValuesLock, new: ValuesLock): List<String> = buildList {
  for ((key, entry) in old.producers) {
    val now = new.producers[key]
    when {
      now == null -> add("producer $key was removed (retire it with a reserved tag instead)")
      now.tag != entry.tag -> add("producer $key changed its tag")
      now.since != entry.since -> add("producer $key changed its level")
      entry.wire.any { (p, level) -> now.wire[p] != level } -> add("producer $key stopped sending a parameter, or changed when it started")
    }
  }
  if (new.nextTag < old.nextTag) add("the next-tag counter went backwards")
  if (new.level < old.level) add("the level went backwards")
}
