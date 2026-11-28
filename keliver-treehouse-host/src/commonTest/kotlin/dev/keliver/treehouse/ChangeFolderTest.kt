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
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChildrenChange
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Create
import dev.keliver.protocol.FrameCacheTags
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierChange
import dev.keliver.protocol.ModifierElement
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.PropertyChange
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.SnapshotChangeList
import dev.keliver.protocol.WidgetTag
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun cachedFrameElement(content: String, ready: Boolean = true) = ModifierElement(
  ModifierTag(FrameCacheTags.CACHED_FRAME),
  buildJsonObject {
    put("content", content)
    put("maxAgeMillis", 43_200_000L)
    put("ready", ready)
    put("paneTitle", "Orders")
  },
)

fun marker(tag: Int) = ModifierElement(ModifierTag(tag))

fun create(id: Int, tag: Int) = Create(Id(id), WidgetTag(tag))

fun prop(id: Int, widget: Int, tag: Int, value: kotlinx.serialization.json.JsonElement) =
  PropertyChange(Id(id), WidgetTag(widget), PropertyTag(tag), value)

fun str(value: String) = JsonPrimitive(value)

fun add(parent: Int, tag: Int, child: Int, index: Int) =
  ChildrenChange.Add(Id(parent), ChildrenTag(tag), Id(child), index)

fun addRoot(child: Int, index: Int = 0) = ChildrenChange.Add(Id.Root, ChildrenTag.Root, Id(child), index)

fun modifier(id: Int, vararg elements: ModifierElement) = ModifierChange(Id(id), elements.toList())

private val json = Json

fun bytesOf(snapshot: SnapshotChangeList): String = json.encodeToString(SnapshotChangeList.serializer(), snapshot)

/** I11: raw batches on one host, the folded snapshot on another, the trees compared. */
fun roundTrip(
  batches: List<List<Change>>,
  rules: FrameFoldRules = TestSchema.rules,
  policy: HiddenEntryPolicy = HiddenEntryPolicy.NothingHidden,
  dumpRules: (FoldResult.Folded) -> DumpRules,
): FoldResult.Folded {
  val raw = TestHost()
  val folder = ChangeFolder(rules, policy)
  for (batch in batches) {
    raw.apply(batch)
    folder.apply(batch)
  }
  val result = folder.fold()
  check(result is FoldResult.Folded) { "blocked: ${(result as FoldResult.Blocked).reason}" }
  val replayed = TestHost()
  replayed.apply(result.snapshot.changes)
  val dr = dumpRules(result)
  assertThat(dump(replayed.top(), dr, raw = false)).isEqualTo(dump(raw.top(), dr, raw = true))
  return result
}

class ChangeFolderTest {
  private val secretText = "SECRET-balance 1,234.56"
  private val secretUrl = "https://secret.example/avatar-42.png"
  private val secretSpan = "SECRETSPAN-owes you"

  /** A screen: Box#1 (marked) with a Text, a Box holding a Text, a never-cached Text prop, a url. */
  private fun screen(content: String): List<List<Change>> = listOf(
    listOf(
      create(1, TestSchema.BOX),
      modifier(1, cachedFrameElement(content)),
      addRoot(1),
    ),
    listOf(
      create(2, TestSchema.TEXT),
      prop(2, 2, 1, str(secretText)),
      prop(
        2,
        2,
        2,
        JsonArray(
          listOf(
            buildJsonObject {
              put("t", secretSpan)
              put("size", 12)
              put("bold", true)
            },
          ),
        ),
      ),
      prop(2, 2, 3, str("icon-check")),
      prop(2, 2, 4, str(secretUrl)),
      prop(2, 2, 5, str("NEVER-pin-0000")),
      prop(2, 2, 6, JsonPrimitive(42)),
      modifier(
        2,
        ModifierElement(
          ModifierTag(7),
          buildJsonObject {
            put("desc", "SECRETDESC-a")
            put("tag", "KEY-a")
            put("secret", "NEVERMOD")
            put("w", 4)
          },
        ),
      ),
      add(1, 1, 2, 0),
      create(3, TestSchema.BOX),
      prop(3, 1, 1, str("KEY-box")),
      add(1, 1, 3, 1),
      create(4, TestSchema.TEXT),
      prop(4, 2, 1, str("SECRET-line one\nSECRET-line two\nSECRET-line three")),
      add(3, 2, 4, 0),
    ),
  )

  @Test fun wholeKeepsEverythingButNeverCachedAndDroppedSubtrees() {
    val result = roundTrip(screen("whole")) { DumpRules(shell = false) }
    assertThat(result.content).isEqualTo(FrameFoldContent.Whole)
    assertThat(result.paneTitle).isEqualTo("Orders")
    assertThat(result.maxAgeMillis).isEqualTo(43_200_000L)
    val bytes = bytesOf(result.snapshot)
    assertThat(bytes).contains(secretText)
    assertThat(bytes).contains(secretUrl)
    assertThat(bytes).contains(secretSpan)
    assertThat(bytes).doesNotContain("NEVER-pin")
    assertThat(bytes).doesNotContain("NEVERMOD")
    assertThat(bytes).doesNotContain("KEY-box")
    assertThat(bytes).doesNotContain("KEY-a")
  }

  @Test fun shellStoresNoOriginalString() {
    val result = roundTrip(screen("shell")) { DumpRules(shell = true) }
    assertThat(result.content).isEqualTo(FrameFoldContent.Shell)
    val bytes = bytesOf(result.snapshot)
    for (secret in listOf(
      "SECRET", "balance", "1,234", secretUrl, "secret.example", "avatar", "SECRETSPAN", "owes",
      "SECRETDESC", "KEY-", "NEVER", "line one", "line two", "line three",
    )) {
      assertThat(bytes).doesNotContain(secret)
    }
    // Style tokens and numbers survive.
    assertThat(bytes).contains("icon-check")
    assertThat(bytes).contains("\"size\":12")
    assertThat(bytes).contains("\"bold\":true")
  }

  @Test fun shellKeepsTheLineCountOfEveryText() {
    val folded = roundTrip(screen("shell")) { DumpRules(shell = true) }
    val host = TestHost().also { it.apply(folded.snapshot.changes) }
    val box = host.top().single().children.getValue(1).widgets.map { it as TestWidget }[1]
    val text = box.children.getValue(2).widgets.single() as TestWidget
    val placeholder = (text.props.getValue(1) as JsonPrimitive).content
    assertThat(placeholder.count { it == '\n' }).isEqualTo(2)
    assertThat(placeholder.all { it == ' ' || it == ' ' || it == '\n' }).isEqualTo(true)
  }

  @Test fun shellMakesEveryStringInAStructuredValueAPlaceholderAndKeepsTheRest() {
    val folded = roundTrip(screen("shell")) { DumpRules(shell = true) }
    val host = TestHost().also { it.apply(folded.snapshot.changes) }
    val text = host.top().single().children.getValue(1).widgets[0] as TestWidget
    val span = ((text.props.getValue(2) as JsonArray).single() as JsonObject)
    assertThat((span.getValue("t") as JsonPrimitive).isString).isEqualTo(true)
    assertThat((span.getValue("t") as JsonPrimitive).content.contains("SECRET")).isEqualTo(false)
    assertThat(span.getValue("size")).isEqualTo(JsonPrimitive(12))
    assertThat(span.getValue("bold")).isEqualTo(JsonPrimitive(true))
  }

  @Test fun aCachedNodeKeepsItsTextInShell() {
    val batches = screen("shell").toMutableList()
    batches += listOf(listOf(modifier(4, marker(FrameCacheTags.CACHED))))
    val folded = roundTrip(batches) { DumpRules(shell = true, cached = setOf(4)) }
    val bytes = bytesOf(folded.snapshot)
    assertThat(bytes).contains("SECRET-line one")
    assertThat(bytes).doesNotContain(secretText)
  }

  @Test fun aNotCachedSubtreeIsDroppedInBothLevels() {
    for (level in listOf("shell", "whole")) {
      val batches = screen(level).toMutableList()
      batches += listOf(listOf(modifier(3, marker(FrameCacheTags.NOT_CACHED))))
      val folded = roundTrip(batches) { DumpRules(shell = level == "shell", notCached = setOf(3)) }
      val bytes = bytesOf(folded.snapshot)
      assertThat(bytes).doesNotContain("line one")
      assertThat(bytes).doesNotContain("line two")
      val creates = folded.snapshot.changes.filterIsInstance<Create>().map { it.id.value }
      assertThat(creates).isEqualTo(listOf(1, 2))
    }
  }

  @Test fun aNotCachedRootBlocks() {
    val folder = ChangeFolder(TestSchema.rules)
    folder.apply(
      listOf(
        create(1, TestSchema.BOX),
        modifier(1, cachedFrameElement("whole"), marker(FrameCacheTags.NOT_CACHED)),
        addRoot(1),
      ),
    )
    assertThat((folder.fold() as FoldResult.Blocked).reason).isEqualTo("notCachedRoot")
  }

  @Test fun hiddenEntriesArePrunedByTheEntryPolicyWithTheirSubtrees() {
    val batches = screen("whole").toMutableList()
    batches += listOf(listOf(prop(3, 1, 2, JsonPrimitive(true))))
    val folded = roundTrip(batches, policy = TestSchema.hiddenWhenFlagged) {
      DumpRules(shell = false, hidden = { it.tag == TestSchema.BOX && it.props[2] == JsonPrimitive(true) })
    }
    val creates = folded.snapshot.changes.filterIsInstance<Create>().map { it.id.value }
    assertThat(creates).isEqualTo(listOf(1, 2))
    assertThat(bytesOf(folded.snapshot)).doesNotContain("line one")
  }

  @Test fun theFoldFollowsMoveRemoveDetachAndReAdd() {
    val batches = screen("whole").toMutableList()
    batches += listOf(
      listOf(
        create(5, TestSchema.TEXT),
        prop(5, 2, 1, str("five")),
        add(1, 1, 5, 2),
        create(6, TestSchema.TEXT),
        prop(6, 2, 1, str("six")),
        add(1, 1, 6, 3),
      ),
      listOf(ChildrenChange.Move(Id(1), ChildrenTag(1), 3, 0, 1)),
      listOf(ChildrenChange.Remove(Id(1), ChildrenTag(1), 1, true)), // detach #2's neighbour
      listOf(ChildrenChange.Remove(Id(1), ChildrenTag(1), 0, false)), // remove for good
    )
    val folded = roundTrip(batches) { DumpRules(shell = false) }
    assertThat(bytesOf(folded.snapshot)).contains("five")
  }

  @Test fun aDetachedNodeReAddedLandsWhereTheHostHasIt() {
    val batches = screen("whole").toMutableList()
    batches += listOf(
      listOf(ChildrenChange.Remove(Id(1), ChildrenTag(1), 1, true)),
      listOf(add(1, 2, 3, 0)),
    )
    roundTrip(batches) { DumpRules(shell = false) }
  }

  @Test fun reservedMarkersAndTheReuseModifierAreNotWritten() {
    val folded = roundTrip(
      listOf(
        listOf(
          create(1, TestSchema.BOX),
          modifier(1, cachedFrameElement("whole"), marker(FrameCacheTags.CACHED), ModifierElement(ModifierTag(8))),
          addRoot(1),
          create(2, TestSchema.TEXT),
          modifier(2, ModifierElement(ModifierTag(-4_543_827)), ModifierElement(ModifierTag(-4_600_050), JsonPrimitive(1))),
          add(1, 1, 2, 0),
        ),
      ),
    ) { DumpRules(shell = false) }
    val tags = folded.snapshot.changes.filterIsInstance<ModifierChange>().flatMap { it.elements }.map { it.tag.value }
    assertThat(tags).isEqualTo(listOf(8))
  }

  @Test fun positionKeysReplaceLazyKeysInBothLevels() {
    for (level in listOf("shell", "whole")) {
      val folded = roundTrip(screen(level)) { DumpRules(shell = level == "shell") }
      val bytes = bytesOf(folded.snapshot)
      assertThat(bytes).doesNotContain("KEY-box")
      assertThat(bytes).doesNotContain("KEY-a")
      assertThat(bytes).contains("\"tag\":\"0\"")
      val keyed = folded.snapshot.changes.filterIsInstance<PropertyChange>().single { it.propertyTag.value == 1 && it.id.value == 3 }
      assertThat(keyed.value).isEqualTo(JsonPrimitive("1"))
    }
  }

  @Test fun aNumericKeyStaysNumeric() {
    val folded = roundTrip(
      listOf(
        listOf(
          create(1, TestSchema.BOX),
          modifier(1, cachedFrameElement("whole")),
          addRoot(1),
          create(2, TestSchema.BOX),
          prop(2, 1, 1, JsonPrimitive(900_001)),
          add(1, 1, 2, 0),
        ),
      ),
    ) { DumpRules(shell = false) }
    val keyed = folded.snapshot.changes.filterIsInstance<PropertyChange>().single()
    assertThat(keyed.value).isEqualTo(JsonPrimitive(0))
  }

  @Test fun measuredLineLengthsSetThePlaceholder() {
    val folder = ChangeFolder(TestSchema.rules, textShapes = { _, _, _ -> listOf(9, 3) })
    for (batch in screen("shell")) folder.apply(batch)
    val folded = folder.fold() as FoldResult.Folded
    val text = folded.snapshot.changes.filterIsInstance<PropertyChange>().first { it.id.value == 2 && it.propertyTag.value == 1 }
    val value = (text.value as JsonPrimitive).content
    assertThat(value.split('\n').map { it.length }).isEqualTo(listOf(9, 3))
  }

  @Test fun anEmptyTextStaysEmptyAndLinesAreNeverLonger() {
    val folder = ChangeFolder(TestSchema.rules)
    folder.apply(
      listOf(
        create(1, TestSchema.BOX),
        modifier(1, cachedFrameElement("shell")),
        addRoot(1),
        create(2, TestSchema.TEXT),
        prop(2, 2, 1, str("")),
        add(1, 1, 2, 0),
        create(3, TestSchema.TEXT),
        prop(3, 2, 1, str("ab\n\ncdefghijkl")),
        add(1, 1, 3, 1),
      ),
    )
    val folded = folder.fold() as FoldResult.Folded
    val values = folded.snapshot.changes.filterIsInstance<PropertyChange>().associate { it.id.value to (it.value as JsonPrimitive).content }
    assertThat(values.getValue(2)).isEqualTo("")
    assertThat(values.getValue(3).split('\n').map { it.length }).isEqualTo(listOf(2, 0, 9)) // A trailing group separator is trimmed, so never longer than the line.
  }

  @Test fun anUnlistedWidgetOrModifierBlocksTheFold() {
    val widget = ChangeFolder(TestSchema.rules)
    widget.apply(
      listOf(
        create(1, TestSchema.BOX),
        modifier(1, cachedFrameElement("whole")),
        addRoot(1),
        create(2, TestSchema.OPAQUE),
        add(1, 1, 2, 0),
      ),
    )
    assertThat((widget.fold() as FoldResult.Blocked).reason).isEqualTo("notReplayable:widget:3")

    val mod = ChangeFolder(TestSchema.rules)
    mod.apply(
      listOf(
        create(1, TestSchema.BOX),
        modifier(1, cachedFrameElement("whole"), ModifierElement(ModifierTag(9))),
        addRoot(1),
      ),
    )
    assertThat((mod.fold() as FoldResult.Blocked).reason).isEqualTo("notReplayable:modifier:9")
  }

  @Test fun nothingIsReplayableByDefault() {
    val folder = ChangeFolder(FrameFoldRules())
    folder.apply(listOf(create(1, TestSchema.BOX), modifier(1, cachedFrameElement("whole")), addRoot(1)))
    assertThat((folder.fold() as FoldResult.Blocked).reason).isEqualTo("notReplayable:widget:1")
  }

  @Test fun markCountsAndPlacement() {
    val none = ChangeFolder(TestSchema.rules)
    none.apply(listOf(create(1, TestSchema.BOX), addRoot(1)))
    assertThat((none.fold() as FoldResult.Blocked).reason).isEqualTo("noMark")

    val two = ChangeFolder(TestSchema.rules)
    two.apply(
      listOf(
        create(1, TestSchema.BOX),
        modifier(1, cachedFrameElement("whole")),
        addRoot(1),
        create(2, TestSchema.BOX),
        modifier(2, cachedFrameElement("whole")),
        addRoot(2, 1),
      ),
    )
    assertThat((two.fold() as FoldResult.Blocked).reason).isEqualTo("multipleMarks")

    val nested = ChangeFolder(TestSchema.rules)
    nested.apply(
      listOf(
        create(1, TestSchema.BOX),
        addRoot(1),
        create(2, TestSchema.BOX),
        modifier(2, cachedFrameElement("whole")),
        add(1, 1, 2, 0),
      ),
    )
    assertThat((nested.fold() as FoldResult.Blocked).reason).isEqualTo("notRootChild")

    // A malformed marker is no marker (FrameMarks.read), the safe side.
    val malformed = ChangeFolder(TestSchema.rules)
    malformed.apply(
      listOf(
        create(1, TestSchema.BOX),
        modifier(1, ModifierElement(ModifierTag(FrameCacheTags.CACHED_FRAME), JsonPrimitive("nope"))),
        addRoot(1),
      ),
    )
    assertThat((malformed.fold() as FoldResult.Blocked).reason).isEqualTo("noMark")
  }

  @Test fun aBatchTheFoldCannotFollowBlocksForGood() {
    val folder = ChangeFolder(TestSchema.rules)
    folder.apply(listOf(create(1, TestSchema.BOX), modifier(1, cachedFrameElement("whole")), addRoot(1)))
    folder.apply(listOf(prop(99, 1, 1, str("x"))))
    assertThat((folder.fold() as FoldResult.Blocked).reason).isEqualTo("inconsistent")
    folder.apply(listOf(create(2, TestSchema.BOX)))
    assertThat((folder.fold() as FoldResult.Blocked).reason).isEqualTo("inconsistent")
  }

  @Test fun foldingTwiceAndAfterMoreBatchesTracksTheHost() {
    val raw = TestHost()
    val folder = ChangeFolder(TestSchema.rules)
    for (batch in screen("whole")) {
      raw.apply(batch)
      folder.apply(batch)
    }
    val first = (folder.fold() as FoldResult.Folded).snapshot
    assertThat(bytesOf((folder.fold() as FoldResult.Folded).snapshot)).isEqualTo(bytesOf(first))
    val more = listOf(create(7, TestSchema.TEXT), prop(7, 2, 1, str("later")), add(1, 1, 7, 0))
    raw.apply(more)
    folder.apply(more)
    val replayed = TestHost().also { it.apply((folder.fold() as FoldResult.Folded).snapshot.changes) }
    val d = DumpRules(shell = false)
    assertThat(dump(replayed.top(), d, raw = false)).isEqualTo(dump(raw.top(), d, raw = true))
    assertThat(bytesOf(first)).doesNotContain("later")
    assertThat(first).isInstanceOf(SnapshotChangeList::class)
  }
}
