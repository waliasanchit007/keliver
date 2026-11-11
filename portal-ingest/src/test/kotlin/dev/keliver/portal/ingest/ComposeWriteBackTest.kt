package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.ingest.ComposeFixtures.OFFER_SCREEN
import dev.keliver.portal.ingest.ComposeFixtures.doc
import dev.keliver.portal.ingest.ComposeFixtures.ed0
import dev.keliver.portal.ingest.ComposeFixtures.widgets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ED0 spike (docs/ED0_SPIKE.md): byte-stable write-back of Compose-shaped code. An edit changes
 * only its own token (a value's text, one argument, one modifier element), and its inverse gives
 * the file back byte for byte — the imports the edit added or left unused included. Each merge is
 * checked by write-back's own safety net: the result reads back as the document.
 */
class ComposeWriteBackTest {
  private val file = "app/screens/OfferScreen.kt"

  private fun merge(text: String, target: UiDocument, base: UiDocument, asComponent: Boolean, fileName: String): String = ed0 {
    when (val o = WriteBack.attempt(text, target, fileName = fileName, base = base, asComponent = asComponent)) {
      is WriteBack.Outcome.Merged -> o.text
      is WriteBack.Outcome.NotSurgical -> throw AssertionError("the change is written surgically, not: ${o.reason}")
    }
  }

  /** Applies [ops] to [text]'s document, writes it back, then writes back their inverse: (mid, end). */
  private fun roundTrip(
    text: String,
    asComponent: Boolean = false,
    fileName: String = file,
    ops: (UiDocument) -> List<DocOp>,
  ): Pair<String, String> {
    val d0 = doc(text, fileName, asComponent)
    val applied = d0.applyBatch(ops(d0)).result ?: error(d0.applyBatch(ops(d0)).error!!)
    val mid = merge(text, applied.doc, d0, asComponent, fileName)
    val undone = applied.doc.replay(applied.inverseBatch)
    val end = merge(mid, undone, applied.doc, asComponent, fileName)
    return mid to end
  }

  /** [text] with [old] (which occurs once) replaced by [new]. */
  private fun splice(text: String, old: String, new: String): String {
    assertEquals(1, text.split(old).size - 1, "occurs once: $old")
    return text.replace(old, new)
  }

  private fun node(d: UiDocument, type: String, i: Int = 0): DocNode.Widget = widgets(d.root).filter { it.type.endsWith(type) }[i]

  @Test fun aPropertyChangeWritesOnlyItsToken() {
    val (mid, end) = roundTrip(OFFER_SCREEN) { d ->
      listOf(DocOp.SetProp(node(d, ".Text#6", 2).handle, "fontSize", PropValue.Lit("c", s = "\"18sp\"")))
    }
    assertEquals(splice(OFFER_SCREEN, "fontSize = 16.sp", "fontSize = 18.sp"), mid)
    assertEquals(OFFER_SCREEN, end)
  }

  @Test fun aModifierValueChangeWritesOnlyItsToken() {
    val (mid, end) = roundTrip(OFFER_SCREEN) { d ->
      listOf(DocOp.SetModifier(node(d, ".Row#3").handle, "2:padding#2.horizontal", PropValue.Lit("c", s = "20")))
    }
    assertEquals(splice(OFFER_SCREEN, ".padding(horizontal = 16.dp, vertical = 12.dp)", ".padding(horizontal = 20.dp, vertical = 12.dp)"), mid)
    assertEquals(OFFER_SCREEN, end)
  }

  @Test fun aPositionalArgumentKeepsItsPosition() {
    // `Column(Modifier.fillMaxWidth().padding(16.dp), …)`: the modifier is the first positional argument.
    val (mid, end) = roundTrip(OFFER_SCREEN) { d ->
      listOf(DocOp.SetModifier(node(d, ".Column#4").handle, "1:padding#1.all", PropValue.Lit("c", s = "24")))
    }
    assertEquals(splice(OFFER_SCREEN, "Column(Modifier.fillMaxWidth().padding(16.dp),", "Column(Modifier.fillMaxWidth().padding(24.dp),"), mid)
    assertEquals(OFFER_SCREEN, end)
  }

  @Test fun aThemeReferenceReplacesALiteralAndTheImportsFollow() {
    val (mid, end) = roundTrip(OFFER_SCREEN) { d ->
      listOf(DocOp.SetProp(node(d, ".Text#6", 2).handle, "color", PropValue.Lit("c", s = """{"ref":"colorScheme.primary"}""")))
    }
    // `Color` lost its last use, so its import goes; the undo brings both back where they were.
    val expected = splice(OFFER_SCREEN, "color = Color(0xFF1F1F1F)", "color = MaterialTheme.colorScheme.primary")
      .replace("import dev.keliver.compose.ui.graphics.Color\n", "")
    assertEquals(expected, mid)
    assertEquals(OFFER_SCREEN, end)
  }

  @Test fun aNewArgumentIsNamedAndLastAndItsRemovalGivesTheFileBack() {
    val (mid, end) = roundTrip(OFFER_SCREEN) { d ->
      listOf(DocOp.SetProp(node(d, ".Text#6", 1).handle, "maxLines", PropValue.Lit("i", i = 2)))
    }
    assertEquals(splice(OFFER_SCREEN, "fontWeight = FontWeight.Bold)", "fontWeight = FontWeight.Bold, maxLines = 2)"), mid)
    assertEquals(OFFER_SCREEN, end)
  }

  @Test fun aMultiLineCallGetsTheNewArgumentOnItsOwnLine() {
    val (mid, end) = roundTrip(OFFER_SCREEN) { d ->
      listOf(DocOp.SetProp(node(d, ".Row#3").handle, "horizontalArrangement", PropValue.Lit("c", s = "\"sb\"")))
    }
    assertEquals(
      splice(
        OFFER_SCREEN,
        "      verticalAlignment = Alignment.CenterVertically,\n",
        "      verticalAlignment = Alignment.CenterVertically,\n      horizontalArrangement = Arrangement.SpaceBetween,\n",
      ),
      mid,
    )
    assertEquals(OFFER_SCREEN, end)
  }

  @Test fun aModifierElementIsRemovedAndReinsertedAsItsOwnPiece() {
    // The Row's last element (`.padding(horizontal = 16.dp, vertical = 12.dp)`, on its own line).
    val (mid, end) = roundTrip(OFFER_SCREEN) { d ->
      val row = node(d, ".Row#3")
      row.modifiers.keys.filter { it.startsWith("2:") }.map { DocOp.RemoveModifier(row.handle, it) }
    }
    assertEquals(splice(OFFER_SCREEN, "\n        .padding(horizontal = 16.dp, vertical = 12.dp)", ""), mid)
    assertEquals(OFFER_SCREEN, end)
  }

  @Test fun removingAMiddleElementRenumbersTheRest() {
    val d0 = doc(OFFER_SCREEN)
    val row = node(d0, ".Row#3")
    val ops = row.modifiers.keys.filter { it.startsWith("1:") || it.startsWith("2:") }.map { DocOp.RemoveModifier(row.handle, it) } +
      row.modifiers.filterKeys { it.startsWith("2:") }.map { (k, v) -> DocOp.SetModifier(row.handle, "1:" + k.substringAfter(':'), v) }
    val target = d0.applyBatch(ops).result!!.doc
    val mid = merge(OFFER_SCREEN, target, d0, asComponent = false, fileName = file)
    // The background goes, with its imports; nothing else changes.
    val expected = splice(OFFER_SCREEN, "\n        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))", "")
      .replace("import dev.keliver.compose.foundation.background\n", "")
      .replace("import dev.keliver.compose.foundation.shape.RoundedCornerShape\n", "")
    assertEquals(expected, mid)
  }

  @Test fun anEditInsideANamedSlotIsSurgical() {
    val screen = OFFER_SCREEN.replace("import dev.keliver.compose.material3.MaterialTheme\n", "import dev.keliver.compose.material3.ListItem\nimport dev.keliver.compose.material3.MaterialTheme\n")
      .replace(
        "    Text(\"Fixed income\", fontSize = 16.sp, color = Color(0xFF1F1F1F), maxLines = 1)\n",
        "    Text(\"Fixed income\", fontSize = 16.sp, color = Color(0xFF1F1F1F), maxLines = 1)\n" +
          "    ListItem(\n      headlineContent = { Text(\"Bonds\") },\n      supportingContent = { Text(\"7.5% a year\") },\n    )\n",
      )
    val (mid, end) = roundTrip(screen) { d ->
      val item = node(d, ".ListItem#101")
      val supporting = (item.children[1] as DocNode.Widget).children.single()
      listOf(DocOp.SetProp(supporting.handle, "text", PropValue.Lit("s", s = "8% a year")))
    }
    assertEquals(splice(screen, "Text(\"7.5% a year\")", "Text(\"8% a year\")"), mid)
    assertEquals(screen, end)
  }

  @Test fun gateASampleEditsAreSurgical() {
    val sample = ComposeFixtures.sampleCard
    val name = "SampleCard.kt"
    // A property: the title's maxLines.
    val (mid, end) = roundTrip(sample, asComponent = true, fileName = name) { d ->
      listOf(DocOp.SetProp(node(d, ".Text#6").handle, "maxLines", PropValue.Lit("i", i = 2)))
    }
    assertEquals(splice(sample, "maxLines = 1,", "maxLines = 2,"), mid)
    assertEquals(sample, end)
    // A modifier value: the card's vertical padding.
    val (mid2, end2) = roundTrip(sample, asComponent = true, fileName = name) { d ->
      listOf(DocOp.SetModifier(node(d, ".Row#3").handle, "6:padding#2.vertical", PropValue.Lit("c", s = "14")))
    }
    assertEquals(splice(sample, ".padding(horizontal = 16.dp, vertical = 12.dp),", ".padding(horizontal = 16.dp, vertical = 14.dp),"), mid2)
    assertEquals(sample, end2)
    // A theme reference inside a Repeat's row: the tag chips' background.
    val (mid3, end3) = roundTrip(sample, asComponent = true, fileName = name) { d ->
      listOf(DocOp.SetModifier(node(d, ".Box#1", 1).handle, "1:background#18.color", PropValue.Lit("c", s = """{"ref":"colorScheme.tertiaryContainer"}""")))
    }
    assertEquals(splice(sample, ".background(MaterialTheme.colorScheme.secondaryContainer)", ".background(MaterialTheme.colorScheme.tertiaryContainer)"), mid3)
    assertEquals(sample, end3)
  }

  @Test fun aNoOpMergeIsByteIdentical() {
    for ((text, asComponent, name) in listOf(Triple(OFFER_SCREEN, false, file), Triple(ComposeFixtures.sampleCard, true, "SampleCard.kt"))) {
      val d = doc(text, name, asComponent)
      assertEquals(text, merge(text, d, d, asComponent, name))
    }
  }

  @Test fun aChangeThatIsNotSurgicalSaysWhy() {
    val d0 = doc(OFFER_SCREEN)
    val row = node(d0, ".Row#3")
    // Swap the first two elements: a reorder.
    val swapped = row.modifiers.mapKeys { (k, _) ->
      when {
        k.startsWith("0:") -> "1:" + k.substringAfter(':')
        k.startsWith("1:") -> "0:" + k.substringAfter(':')
        else -> k
      }
    }
    val ops = row.modifiers.keys.map { DocOp.RemoveModifier(row.handle, it) } + swapped.map { (k, v) -> DocOp.SetModifier(row.handle, k, v) }
    val target = d0.applyBatch(ops).result!!.doc
    val o = ed0 { WriteBack.attempt(OFFER_SCREEN, target, fileName = file, base = d0) }
    assertTrue(o is WriteBack.Outcome.NotSurgical && "reordering" in o.reason, o.toString())
  }
}
