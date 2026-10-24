package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An edit and its inverse (an undo, or the opposite edit) give back the file byte for byte: the
 * imports the edit added go when its inverse removes their last use, and arguments the author
 * never touched stay where they were. Before, undoing an insert left the widget's import behind,
 * and adding then removing a modifier left two imports and reordered an untouched argument.
 */
class WriteBackRoundTripTest {
  private val file = "app/screens/HomeScreen.kt"

  private fun doc(text: String): UiDocument =
    Recognizer.recognize(file, text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun merge(text: String, target: UiDocument, base: UiDocument): String =
    when (val o = WriteBack.attempt(text, target, fileName = file, base = base)) {
      is WriteBack.Outcome.Merged -> o.text
      is WriteBack.Outcome.NotSurgical -> throw AssertionError("the change is written surgically, not: ${o.reason}")
    }

  /** Applies [op] to the file, then its inverse to the result, and returns both texts. */
  private fun roundTrip(text: String, op: (UiDocument) -> DocOp): Pair<String, String> {
    val d0 = doc(text)
    val applied = d0.apply(op(d0))
    val d1 = applied.doc
    val mid = merge(text, d1, d0)
    // The relay undoes on the document it holds (the file was written from it).
    val undone = d1.apply(applied.inverse!!)
    val end = merge(mid, undone.doc, d1)
    return mid to end
  }

  private val screen = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(
          text = "Monthly",
          fontSize = 24,
          weight = 700,
          colorArgb = -16777216,
          align = 1,
        )
      }
    }

    interface HomeScreenBindings
  """.trimIndent() + "\n"

  private fun column(d: UiDocument) = d.root as DocNode.Widget

  @Test fun undoingAnInsertRemovesTheImportItAdded() {
    val (mid, end) = roundTrip(screen) { d ->
      DocOp.InsertNode(d.root.handle, column(d).children[0].handle, DocNode.Widget(Handle(0), "Divider"))
    }
    assertTrue("import dev.keliver.material.compose.Divider" in mid, mid)
    // F20: a widget with no arguments is written `Divider()`, not with its parentheses on two lines.
    assertTrue(Regex("""\n\s*Divider\(\)\n""").containsMatchIn(mid), mid)
    assertEquals(screen, end)
  }

  @Test fun aNewContainerWithoutArgumentsIsWrittenWithoutParentheses() {
    val inserted = doc(screen).let { d ->
      merge(screen, d.apply(DocOp.InsertNode(d.root.handle, null, DocNode.Widget(Handle(0), "Column", children = listOf(DocNode.Widget(Handle(0), "Divider"))))).doc, d)
    }
    assertTrue(Regex("""\n\s*Column \{\n\s*Divider\(\)\n\s*\}""").containsMatchIn(inserted), inserted)
  }

  @Test fun addingThenRemovingAModifierGivesTheFileBack() {
    val (mid, end) = roundTrip(screen) { d ->
      DocOp.SetModifier(column(d).children[0].handle, "Padding.allDp", lit(8))
    }
    assertTrue("padding" in mid, mid)
    assertEquals(screen, end)
  }

  @Test fun anImportTheAuthorLeftUnusedIsKept() {
    val withUnused = screen.replace(
      "import dev.keliver.material.compose.StyledText\n",
      "import dev.keliver.material.compose.Divider\nimport dev.keliver.material.compose.StyledText\n",
    )
    val (_, end) = roundTrip(withUnused) { d ->
      DocOp.SetProp(column(d).children[0].handle, "text", lit("Yearly"))
    }
    assertEquals(withUnused, end)
  }

  @Test fun pruningRemovesOnlyImportsTheEditLeftUnused() {
    val before = "import androidx.compose.runtime.getValue\nimport a.Gone\nimport a.Kept\nimport a.Idle\n\nval x by y; val z = Gone() + Kept()\n"
    val after = "import androidx.compose.runtime.getValue\nimport a.Gone\nimport a.Kept\nimport a.Idle\n\nval x by y; val z = Kept()\n"
    assertEquals(
      "import androidx.compose.runtime.getValue\nimport a.Kept\nimport a.Idle\n\nval x by y; val z = Kept()\n",
      WriteBack.pruneImports(before, after),
      "Gone lost its last use; Kept is used; Idle was already unused; getValue is an operator convention",
    )
  }

  @Test fun anActionPassedByReferenceKeepsItsSpellingThroughAWholeListEditAndItsUndo() {
    val withRef = screen.replace(
      "  Column {\n",
      "  Column {\n    Button(text = \"Go\", onClick = b::go)\n",
    ).replace("import dev.keliver.layout.compose.Column\n", "import dev.keliver.layout.compose.Column\nimport dev.keliver.material.compose.Button\n")
      .replace("interface HomeScreenBindings", "interface HomeScreenBindings {\n  fun go()\n}\n\ninterface Unused")
    val (mid, end) = roundTrip(withRef) { d -> DocOp.SetModifier(column(d).children[0].handle, "Padding.allDp", lit(8)) }
    assertTrue("onClick = b::go" in mid, mid)
    assertEquals(withRef, end)
  }

  @Test fun aFirstArgumentGoesInANewListAfterTheNameAndItsUndoRemovesIt() {
    val (mid, end) = roundTrip(screen) { d -> DocOp.SetProp(d.root.handle, "width", lit(1)) }
    assertTrue(Regex("""\n  Column\(width = Constraint\.Fill\) \{\n""").containsMatchIn(mid), mid)
    assertEquals(screen, end)
  }

  @Test fun aSecondArgumentAddedToAOneLineListStaysOneLineWithoutATrailingComma() {
    val (first, _) = roundTrip(screen) { d -> DocOp.SetProp(d.root.handle, "width", lit(1)) }
    val (mid, end) = roundTrip(first) { d -> DocOp.SetProp(d.root.handle, "height", lit(1)) }
    // Round 5 (L4): not `Column(width = Constraint.Fill, height = Constraint.Fill, ) {`.
    assertTrue(Regex("""\n  Column\(width = Constraint\.Fill, height = Constraint\.Fill\) \{\n""").containsMatchIn(mid), mid)
    assertEquals(first, end)
  }
}
