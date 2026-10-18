package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.UiDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * K3 fourth review: moves and inserts at the edges of a block — the only
 * statement of a lambda moved out (and back), a statement placed first in a
 * block whose first statement has comments above it.
 * Each case asserts the whole file and that it re-ingests to the target.
 */
class WriteBackMoveEdgesTest {
  private val file = "app/screens/HomeScreen.kt"

  private fun doc(text: String): UiDocument =
    Recognizer.recognize(file, text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun attempt(text: String, target: UiDocument, base: UiDocument) =
    WriteBack.attempt(text, target, fileName = file, base = base)

  private fun merge(text: String, target: UiDocument, base: UiDocument): String =
    when (val o = attempt(text, target, base)) {
      is WriteBack.Outcome.Merged -> o.text
      is WriteBack.Outcome.NotSurgical -> throw AssertionError("the change is written surgically, not: ${o.reason}")
    }

  private fun column(d: UiDocument) = d.root as DocNode.Widget
  private fun child(d: UiDocument, i: Int) = column(d).children[i]
  private fun widget(d: UiDocument, i: Int) = child(d, i) as DocNode.Widget

  private fun strip(n: DocNode): DocNode = when (n) {
    is DocNode.RawCode -> n.copy(handle = Handle(0), kindHint = null)
    is DocNode.Widget -> n.copy(handle = Handle(0), children = n.children.map(::strip))
  }

  private fun assertReingests(target: UiDocument, out: String) {
    assertEquals(strip(target.root), strip(doc(out).root), "the result re-ingests to the target document")
    val again = doc(out)
    assertEquals(out, WriteBack.merge(out, again, fileName = file, base = again), "a no-op merge of the result is byte-identical")
  }

  // ── S1: the only statement of a lambda block ───────────────────────────────

  private val onlyChildren = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        Row {
          StyledText(text = "only")
        }
        b.rows.forEach { row ->
          StyledText(text = "each")
        }
        StyledText(text = "z")
      }
    }

    interface HomeScreenBindings {
      val rows: List<String>
    }
  """.trimIndent() + "\n"

  @Test fun theOnlyChildOfARowMovesToTheTopLevelAndBack() {
    val d = doc(onlyChildren)
    val row = widget(d, 0)
    val out = merge(onlyChildren, d.apply(DocOp.MoveNode(row.children[0].handle, d.root.handle, child(d, 2).handle)).doc, d)
    assertEquals(
      onlyChildren
        .replace("    Row {\n      StyledText(text = \"only\")\n    }\n", "    Row {\n    }\n")
        .replace("    StyledText(text = \"z\")\n", "    StyledText(text = \"z\")\n    StyledText(text = \"only\")\n"),
      out,
    )
    assertReingests(d.apply(DocOp.MoveNode(row.children[0].handle, d.root.handle, child(d, 2).handle)).doc, out)
    // And back in.
    val d2 = doc(out)
    val back = d2.apply(DocOp.MoveNode(child(d2, 3).handle, widget(d2, 0).handle, null)).doc
    val out2 = merge(out, back, d2)
    assertEquals(onlyChildren, out2)
    assertReingests(back, out2)
  }

  @Test fun theOnlyChildOfAForEachMovesToTheTopLevelAndBack() {
    val d = doc(onlyChildren)
    val each = widget(d, 1)
    val target = d.apply(DocOp.MoveNode(each.children[0].handle, d.root.handle, null)).doc
    val out = merge(onlyChildren, target, d)
    assertEquals(
      onlyChildren
        .replace("    b.rows.forEach { row ->\n      StyledText(text = \"each\")\n    }\n", "    b.rows.forEach { row ->\n    }\n")
        .replace("  Column {\n", "  Column {\n    StyledText(text = \"each\")\n"),
      out,
    )
    assertReingests(target, out)
    val d2 = doc(out)
    val back = d2.apply(DocOp.MoveNode(child(d2, 0).handle, widget(d2, 2).handle, null)).doc
    val out2 = merge(out, back, d2)
    assertEquals(onlyChildren, out2)
    assertReingests(back, out2)
  }

  // ── S2: first in a block whose first statement has comments above it ──────

  private val commented = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        if (b.open) {
          // then comment
          StyledText(text = "t")
        } else {
          StyledText(text = "e")
        }
        Row {
          // section A header

          // own comment
          Legacy(1)
        }
      }
    }

    interface HomeScreenBindings {
      val open: Boolean
    }
  """.trimIndent() + "\n"

  private val newText = DocNode.Widget(Handle(0), "StyledText", mapOf("text" to dev.keliver.portal.document.lit("n")))

  private fun elseOf(d: UiDocument) = widget(d, 0).children.last() as DocNode.Widget

  @Test fun anElseChildMovedFirstIntoTheThenBlockGoesAboveItsComment() {
    val d = doc(commented)
    val target = d.apply(DocOp.MoveNode(elseOf(d).children[0].handle, widget(d, 0).handle, null)).doc
    val out = merge(commented, target, d)
    assertEquals(
      commented
        .replace("    if (b.open) {\n      // then comment\n", "    if (b.open) {\n      StyledText(text = \"e\")\n      // then comment\n")
        .replace("    } else {\n      StyledText(text = \"e\")\n    }\n", "    } else {\n    }\n"),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun anInsertFirstIntoAThenBlockGoesAboveItsComment() {
    val d = doc(commented)
    val target = d.apply(DocOp.InsertNode(widget(d, 0).handle, null, newText)).doc
    val out = merge(commented, target, d)
    assertEquals(
      commented.replace(
        "    if (b.open) {\n      // then comment\n",
        "    if (b.open) {\n      StyledText(\n        text = \"n\",\n      )\n      // then comment\n",
      ),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun anInsertFirstIntoALambdaGoesAboveASectionHeaderAndABlankLine() {
    val d = doc(commented)
    val target = d.apply(DocOp.InsertNode(widget(d, 1).handle, null, newText)).doc
    val out = merge(commented, target, d)
    assertEquals(
      commented.replace(
        "    Row {\n      // section A header\n",
        "    Row {\n      StyledText(\n        text = \"n\",\n      )\n      // section A header\n",
      ),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun aMoveFirstIntoALambdaGoesAboveASectionHeaderAndABlankLine() {
    val d = doc(commented)
    val target = d.apply(DocOp.MoveNode(elseOf(d).children[0].handle, widget(d, 1).handle, null)).doc
    val out = merge(commented, target, d)
    assertEquals(
      commented
        .replace("    } else {\n      StyledText(text = \"e\")\n    }\n", "    } else {\n    }\n")
        .replace("    Row {\n      // section A header\n", "    Row {\n      StyledText(text = \"e\")\n      // section A header\n"),
      out,
    )
    assertReingests(target, out)
  }

  // ── S3: multi-line code moved to another depth ─────────────────────────────

  private val multiLine = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        Legacy(
          1,
          2,
        )
        Legacy(""${'"'}
      keep
          this""${'"'})
        Row {
          StyledText(text = "r")
        }
        Column {
          Legacy(
            3,
          )
        }
      }
    }

    interface HomeScreenBindings
  """.trimIndent() + "\n"

  @Test fun multiLineCodeMovesDeeperReindentedOutsideItsStrings() {
    val d = doc(multiLine)
    val row = widget(d, 2)
    val target = d.apply(DocOp.MoveNode(child(d, 0).handle, row.handle, row.children[0].handle)).doc
      .let { t -> t.apply(DocOp.MoveNode(child(t, 0).handle, widget(t, 1).handle, widget(t, 1).children[1].handle)).doc }
    val out = merge(multiLine, target, d)
    assertEquals(
      multiLine
        .replace("    Legacy(\n      1,\n      2,\n    )\n", "")
        .replace("    Legacy(\"\"\"\n  keep\n      this\"\"\")\n", "")
        .replace(
          "      StyledText(text = \"r\")\n",
          "      StyledText(text = \"r\")\n      Legacy(\n        1,\n        2,\n      )\n      Legacy(\"\"\"\n  keep\n      this\"\"\")\n",
        ),
      out,
    )
    // The document keeps the code as it was spelled; the next write leaves the re-indented lines alone.
    assertEquals(out, WriteBack.merge(out, target, fileName = file, base = target))
  }

  @Test fun aWidgetHoldingMultiLineCodeMovesShallower() {
    val d = doc(multiLine)
    val inner = widget(d, 3)
    val target = d.apply(DocOp.MoveNode(inner.children[0].handle, d.root.handle, null)).doc
    val out = merge(multiLine, target, d)
    assertEquals(
      multiLine
        .replace("      Legacy(\n        3,\n      )\n", "")
        .replace("  Column {\n    Legacy(\n      1,", "  Column {\n    Legacy(\n      3,\n    )\n    Legacy(\n      1,"),
      out,
    )
    // The whole inner Column (holding multi-line code) to the top as well.
    val t2 = d.apply(DocOp.MoveNode(inner.handle, d.root.handle, null)).doc
    val out2 = merge(multiLine, t2, d)
    assertEquals(
      multiLine
        .replace("    Column {\n      Legacy(\n        3,\n      )\n    }\n", "")
        .replace("  Column {\n    Legacy(\n      1,", "  Column {\n    Column {\n      Legacy(\n        3,\n      )\n    }\n    Legacy(\n      1,"),
      out2,
    )
  }

  @Test fun aWhitespaceOnlyDifferenceIsSaidAsSuch() {
    val diff = WriteBack.firstDifference(DocNode.RawCode(Handle(1), "Legacy(1,  2)"), DocNode.RawCode(Handle(2), "Legacy(1, 2)"))
    kotlin.test.assertNotNull(diff)
    kotlin.test.assertTrue("whitespace" in diff && "1,  2" in diff, diff)
    // Indentation of code lines (outside strings) is not a difference; inside a string it is.
    kotlin.test.assertNull(WriteBack.firstDifference(DocNode.RawCode(Handle(1), "A(\n  1)"), DocNode.RawCode(Handle(2), "A(\n      1)")))
    kotlin.test.assertNotNull(WriteBack.firstDifference(DocNode.RawCode(Handle(1), "A(\"\"\"\n  1\"\"\")"), DocNode.RawCode(Handle(2), "A(\"\"\"\n      1\"\"\")")))
  }

  // ── a moved statement leaves the blank-line structure around it ────────────

  private val sections = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        // section header

        Legacy(1)
        StyledText(text = "x")

        StyledText(text = "y")
      }
    }

    interface HomeScreenBindings
  """.trimIndent() + "\n"

  @Test fun aStatementMovedFromUnderASectionHeaderKeepsTheBlankLineBelowIt() {
    val d = doc(sections)
    val target = d.apply(DocOp.MoveNode(child(d, 0).handle, d.root.handle, child(d, 1).handle)).doc
    val out = merge(sections, target, d)
    assertEquals(
      sections.replace("    Legacy(1)\n    StyledText(text = \"x\")\n", "    StyledText(text = \"x\")\n    Legacy(1)\n"),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun aStatementWithBlankLinesOnBothSidesLeavesOne() {
    val d = doc(sections)
    val target = d.apply(DocOp.MoveNode(child(d, 2).handle, d.root.handle, null)).doc
    val out = merge(sections, target, d)
    // `y` had a blank line above (and the block end below): its place closes up; it goes first.
    assertEquals(
      sections
        .replace("    StyledText(text = \"x\")\n\n    StyledText(text = \"y\")\n", "    StyledText(text = \"x\")\n")
        .replace("  Column {\n    // section header\n", "  Column {\n    StyledText(text = \"y\")\n    // section header\n"),
      out,
    )
    assertReingests(target, out)
  }

  // ── K3 fifth review, S-b: a `{` line that ends with a comment ──────────────

  private val braceComments = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column { // header
        StyledText(text = "a")
        b.rows.forEach { row -> // c
          StyledText(text = row)
        }
        Row { // nothing yet
        }
      }
    }

    interface HomeScreenBindings {
      val rows: List<String>
    }
  """.trimIndent() + "\n"

  private fun styled(text: String) = DocNode.Widget(Handle(900), "StyledText", mapOf("text" to dev.keliver.portal.document.lit(text)))

  @Test fun aStatementInsertedFirstUnderABraceLineCommentGoesBelowIt() {
    val d = doc(braceComments)
    val target = d.apply(DocOp.InsertNode(d.root.handle, null, styled("new"))).doc
    val out = merge(braceComments, target, d)
    assertEquals(
      braceComments.replace(
        "  Column { // header\n",
        "  Column { // header\n    StyledText(\n      text = \"new\",\n    )\n",
      ),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun aStatementMovedFirstUnderABraceLineCommentGoesBelowIt() {
    val d = doc(braceComments)
    val each = widget(d, 1)
    val target = d.apply(DocOp.MoveNode(child(d, 0).handle, d.root.handle, child(d, 1).handle)).doc
      .let { it.apply(DocOp.MoveNode(child(d, 0).handle, each.handle, null)).doc }
    val out = merge(braceComments, target, d)
    assertEquals(
      braceComments
        .replace("    StyledText(text = \"a\")\n    b.rows", "    b.rows")
        .replace("forEach { row -> // c\n", "forEach { row -> // c\n      StyledText(text = \"a\")\n"),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun aStatementInsertedFirstInAForEachUnderABraceLineCommentGoesBelowIt() {
    val d = doc(braceComments)
    val target = d.apply(DocOp.InsertNode(widget(d, 1).handle, null, styled("new"))).doc
    val out = merge(braceComments, target, d)
    assertEquals(
      braceComments.replace(
        "forEach { row -> // c\n",
        "forEach { row -> // c\n      StyledText(\n        text = \"new\",\n      )\n",
      ),
      out,
    )
    assertReingests(target, out)
  }

  @Test fun aStatementPlacedInABlockWithOnlyABraceLineCommentGoesBelowIt() {
    val d = doc(braceComments)
    val row = widget(d, 2)
    val inserted = d.apply(DocOp.InsertNode(row.handle, null, styled("new"))).doc
    val out = merge(braceComments, inserted, d)
    assertEquals(
      braceComments.replace(
        "    Row { // nothing yet\n    }\n",
        "    Row { // nothing yet\n      StyledText(\n        text = \"new\",\n      )\n    }\n",
      ),
      out,
    )
    assertReingests(inserted, out)
    val moved = d.apply(DocOp.MoveNode(child(d, 0).handle, row.handle, null)).doc
    val out2 = merge(braceComments, moved, d)
    assertEquals(
      braceComments
        .replace("    StyledText(text = \"a\")\n    b.rows", "    b.rows")
        .replace("    Row { // nothing yet\n    }\n", "    Row { // nothing yet\n      StyledText(text = \"a\")\n    }\n"),
      out2,
    )
    assertReingests(moved, out2)
  }
}
