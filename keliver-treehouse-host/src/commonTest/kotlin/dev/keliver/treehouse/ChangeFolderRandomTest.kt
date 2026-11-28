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
@file:OptIn(KeliverFrameFoldApi::class)

package dev.keliver.treehouse

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChildrenChange
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.FrameCacheTags
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierChange
import dev.keliver.protocol.ModifierElement
import dev.keliver.protocol.ModifierTag
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * I11's property test: random batch sequences (Add, Move, Remove with and without detach, re-add,
 * property and modifier changes, notCached, cached, hidden entries), applied to a real
 * `HostProtocolAdapter` and folded; the snapshot, applied to another host, must draw the same tree.
 * Seeds are fixed, so a failure names its seed and reproduces.
 */
class ChangeFolderRandomTest {
  @Test fun theFoldedSnapshotDrawsTheTreeTheHostDrew() {
    for (seed in 1..60) {
      run(seed, shell = seed % 2 == 0)
    }
  }

  private class M(val id: Int, val tag: Int) {
    var parent: M? = null
    var parentTag = 0
    val kids = mutableMapOf(1 to mutableListOf<M>(), 2 to mutableListOf<M>())
    var cachedMark = false
    var notCachedMark = false
  }

  private fun run(seed: Int, shell: Boolean) {
    val random = Random(seed)
    val top = M(1, TestSchema.BOX)
    val live = mutableMapOf(1 to top) // every node the host still holds
    val detached = mutableListOf<M>()
    var nextId = 2
    val secrets = mutableListOf<String>()
    val raw = TestHost()
    val folder = ChangeFolder(TestSchema.rules, TestSchema.hiddenWhenFlagged)

    fun send(batch: List<Change>) {
      raw.apply(batch)
      folder.apply(batch)
    }

    fun secret(kind: String): String = "SECRET$kind-$seed-$nextId-${random.nextInt(1000)}~".also { secrets += it }

    fun attached(): List<M> {
      val out = mutableListOf<M>()
      fun walk(m: M) {
        out += m
        for (list in m.kids.values) list.forEach(::walk)
      }
      walk(top)
      return out
    }

    fun propsFor(m: M): List<Change> = if (m.tag == TestSchema.TEXT) {
      listOf(
        prop(m.id, 2, 1, str(secret("T") + if (random.nextBoolean()) "\n" + secret("T2") else "")),
        prop(
          m.id,
          2,
          2,
          JsonArray(
            listOf(
              buildJsonObject {
                put("t", secret("S"))
                put("size", random.nextInt(20))
              },
            ),
          ),
        ),
        prop(m.id, 2, 3, str("icon-${random.nextInt(5)}")),
        prop(m.id, 2, 4, str("https://" + secret("U"))),
        prop(m.id, 2, 5, str(secret("N"))),
        prop(m.id, 2, 6, JsonPrimitive(random.nextInt(100))),
      )
    } else {
      listOf(
        prop(m.id, 1, 1, str(secret("K"))),
        prop(m.id, 1, 2, JsonPrimitive(random.nextInt(6) == 0)),
      )
    }

    fun modifierFor(m: M): ModifierChange {
      val elements = mutableListOf<ModifierElement>()
      if (random.nextBoolean()) {
        elements += ModifierElement(
          ModifierTag(7),
          buildJsonObject {
            put("desc", secret("D"))
            put("tag", secret("MK"))
            put("secret", secret("MN"))
            put("w", random.nextInt(9))
          },
        )
      }
      if (random.nextInt(8) == 0) elements += ModifierElement(ModifierTag(8))
      m.cachedMark = random.nextInt(5) == 0
      m.notCachedMark = random.nextInt(9) == 0
      if (m.cachedMark) elements += ModifierElement(ModifierTag(FrameCacheTags.CACHED))
      if (m.notCachedMark) elements += ModifierElement(ModifierTag(FrameCacheTags.NOT_CACHED))
      return modifier(m.id, *elements.toTypedArray())
    }

    fun addNode(): List<Change> {
      val parent = attached().filter { it.tag == TestSchema.BOX }.random(random)
      val childrenTag = 1 + random.nextInt(2)
      val m = M(nextId++, if (random.nextInt(5) < 2) TestSchema.BOX else TestSchema.TEXT)
      val list = parent.kids.getValue(childrenTag)
      val index = random.nextInt(list.size + 1)
      val out = mutableListOf<Change>(create(m.id, m.tag))
      out += propsFor(m)
      out += modifierFor(m)
      out += add(parent.id, childrenTag, m.id, index)
      list.add(index, m)
      m.parent = parent
      m.parentTag = childrenTag
      live[m.id] = m
      return out
    }

    fun forget(m: M) {
      live.remove(m.id)
      m.kids.values.forEach { it.forEach(::forget) }
    }

    fun removeNode(): List<Change>? {
      val candidates = attached().filter { it !== top }
      if (candidates.isEmpty()) return null
      val m = candidates.random(random)
      val parent = m.parent!!
      val list = parent.kids.getValue(m.parentTag)
      val index = list.indexOf(m)
      val detach = random.nextBoolean()
      list.removeAt(index)
      m.parent = null
      if (detach) detached += m else forget(m)
      return listOf(ChildrenChange.Remove(Id(parent.id), ChildrenTag(m.parentTag), index, detach))
    }

    fun reAdd(): List<Change>? {
      if (detached.isEmpty()) return null
      val m = detached.removeAt(random.nextInt(detached.size))
      val parent = attached().filter { it.tag == TestSchema.BOX }.random(random)
      val childrenTag = 1 + random.nextInt(2)
      val list = parent.kids.getValue(childrenTag)
      val index = random.nextInt(list.size + 1)
      list.add(index, m)
      m.parent = parent
      m.parentTag = childrenTag
      return listOf(add(parent.id, childrenTag, m.id, index))
    }

    fun moveNodes(): List<Change>? {
      val parents = attached().filter { p -> p.kids.values.any { it.size >= 2 } }
      if (parents.isEmpty()) return null
      val parent = parents.random(random)
      val childrenTag = parent.kids.entries.filter { it.value.size >= 2 }.random(random).key
      val list = parent.kids.getValue(childrenTag)
      val from = random.nextInt(list.size)
      val count = 1 + random.nextInt(minOf(3, list.size - from))
      val to = random.nextInt(list.size + 1)
      if (to in from..(from + count)) return null
      val dest = if (from > to) to else to - count
      val moved = list.subList(from, from + count).toList()
      if (count == 1 && (from == to + 1 || from == to - 1)) {
        val a = list[from]
        list[from] = list.set(to, a)
      } else {
        list.subList(from, from + count).clear()
        list.addAll(dest, moved)
      }
      return listOf(ChildrenChange.Move(Id(parent.id), ChildrenTag(childrenTag), from, to, count))
    }

    fun changeNode(): List<Change>? {
      val all = (attached() + detached.flatMap { d -> mutableListOf(d) }).filter { it !== top }
      if (all.isEmpty()) return null
      val m = all.random(random)
      val out = mutableListOf<Change>()
      out += propsFor(m).filter { random.nextBoolean() }
      out += modifierFor(m)
      return out
    }

    fun stringsOf(element: JsonElement, into: MutableList<String>) {
      when (element) {
        is JsonArray -> element.forEach { stringsOf(it, into) }
        is kotlinx.serialization.json.JsonObject -> element.values.forEach { stringsOf(it, into) }
        is JsonPrimitive -> if (element.isString) into += element.content
      }
    }

    fun verify() {
      val folded = folder.fold()
      check(folded is FoldResult.Folded) { "seed $seed: blocked ${(folded as FoldResult.Blocked).reason}" }
      val replayed = TestHost().also { it.apply(folded.snapshot.changes) }
      val cachedIds = live.values.filter { it.cachedMark }.map { it.id }.toSet()
      val rules = DumpRules(
        shell = shell,
        cached = cachedIds,
        notCached = live.values.filter { it.notCachedMark }.map { it.id }.toSet(),
        hidden = { it.tag == TestSchema.BOX && it.props[2] == JsonPrimitive(true) },
      )
      assertThat(dump(replayed.top(), rules, raw = false), "seed $seed").isEqualTo(dump(raw.top(), rules, raw = true))

      val bytes = bytesOf(folded.snapshot)
      // Never cached and lazy keys: absent in either level.
      for (s in secrets.filter { it.startsWith("SECRETN-") || it.startsWith("SECRETMN-") }) {
        assertThat(bytes.contains(s), "seed $seed never-cached $s").isFalse()
      }
      for (s in secrets.filter { it.startsWith("SECRETK-") || it.startsWith("SECRETMK-") }) {
        assertThat(bytes.contains(s), "seed $seed key $s").isFalse()
      }
      if (shell) {
        // I2: no original string of a node that is not cached() appears anywhere in the bytes.
        val allowed = mutableListOf<String>()
        fun scan(widgets: List<TestWidget>) {
          for (w in widgets) {
            if (w.id.value in cachedIds) {
              w.props.values.forEach { stringsOf(it, allowed) }
              w.elements().forEach { stringsOf(it.value, allowed) }
            }
            w.children.values.forEach { c -> scan(c.widgets.map { it as TestWidget }) }
          }
        }
        scan(raw.top())
        for (s in secrets) {
          if (allowed.any { s in it }) continue
          assertThat(bytes.contains(s), "seed $seed leaked $s").isFalse()
        }
      }
    }

    // The root.
    send(
      listOf(
        create(1, TestSchema.BOX),
        modifier(1, cachedFrameElement(if (shell) "shell" else "whole")),
        addRoot(1),
      ),
    )
    repeat(8) { send(addNode()) }

    repeat(25) { step ->
      val batch = mutableListOf<Change>()
      repeat(1 + random.nextInt(4)) {
        val ops: List<() -> List<Change>?> = listOf(::addNode, ::addNode, ::removeNode, ::reAdd, ::moveNodes, ::changeNode)
        ops.random(random)()?.let { batch += it }
      }
      send(batch)
      if (step % 5 == 4) verify()
    }
    verify()
  }
}
