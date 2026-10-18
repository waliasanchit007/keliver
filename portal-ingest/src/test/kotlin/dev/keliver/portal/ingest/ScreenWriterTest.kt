package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * K3 last review round — the write-back guarantee: a change is written in
 * place when it can be; the whole file is rewritten from the document (the
 * full export) only when that loses nothing — the file IS the export of its
 * own tree — and the export reads back as the document; otherwise the change
 * is REFUSED with a reason and the file is left as it is.
 */
class ScreenWriterTest {
  private val file = "app/screens/HomeScreen.kt"

  private fun doc(text: String): UiDocument =
    Recognizer.recognize(file, text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun plan(onDisk: String?, target: UiDocument, base: UiDocument?) = ScreenWriter.plan(
    onDisk = onDisk, doc = target, base = base, fileName = file, functionName = "HomeScreen", packageName = "app.screens",
  )

  private fun <T> diverging(block: () -> T): T {
    // Forces the surgical merge not to read back (as a splice bug would).
    WriteBack.afterSpliceForTest.set { it.replace("StyledText(", "StyledTxt(") }
    try {
      return block()
    } finally {
      WriteBack.afterSpliceForTest.remove()
    }
  }

  private val reviewer = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(bindings: HomeScreenBindings) {
      Column {
        // keep me
        Legacy(bindings.title)
        StyledText(text = "x")
      }
    }

    private fun helper() = Unit

    interface HomeScreenBindings {
      val title: String
    }
  """.trimIndent() + "\n"

  private fun insertTitle(d: UiDocument) = d.apply(
    DocOp.InsertNode(d.root.handle, (d.root as DocNode.Widget).children[0].handle, DocNode.Widget(Handle(0), "StyledText", mapOf("text" to PropValue.Bind("title")))),
  ).doc

  @Test fun theReviewersChangeIsWrittenInPlace() {
    val d = doc(reviewer)
    val w = assertIs<ScreenWriter.Write>(plan(reviewer, insertTitle(d), d))
    assertEquals(ScreenWriter.How.SURGICAL, w.how)
    assertTrue("// keep me" in w.text && "private fun helper()" in w.text && "bindings.title,\n" in w.text, w.text)
  }

  @Test fun aFileWithAuthorCodeIsNeverReplacedByTheExport() {
    val d = doc(reviewer)
    val r = assertIs<ScreenWriter.Refuse>(diverging { plan(reviewer, insertTitle(d), d) })
    assertTrue("read back" in r.reason, r.reason)
  }

  /** A file exactly as the exporter writes [d]'s tree. */
  private fun exported(d: UiDocument): String =
    assertIs<ScreenWriter.Write>(plan(null, d, null)).also { assertEquals(ScreenWriter.How.CREATED, it.how) }.text

  private val simple = UiDocument(
    "home",
    DocNode.Widget(Handle(1), "Column", children = listOf(DocNode.Widget(Handle(2), "StyledText", mapOf("text" to lit("x"))))),
    dev.keliver.portal.document.Contract(), 0, 10,
  )

  @Test fun anExportShapedFileMayBeRewrittenByTheExport() {
    val text = exported(simple)
    val d = doc(text)
    val target = d.apply(DocOp.SetProp((d.root as DocNode.Widget).children[0].handle, "text", lit("y"))).doc
    val w = assertIs<ScreenWriter.Write>(diverging { plan(text, target, d) })
    assertEquals(ScreenWriter.How.EXPORT, w.how)
    assertEquals(text.replace("\"x\"", "\"y\""), w.text)
  }

  @Test fun anExportThatDoesNotReadBackIsRefused() {
    val text = exported(simple)
    val d = doc(text)
    // The exporter cannot write a RawCode node: its export does not read back.
    val target = d.apply(DocOp.InsertNode(d.root.handle, null, DocNode.RawCode(Handle(0), "Legacy(1)"))).doc
    val r = assertIs<ScreenWriter.Refuse>(diverging { plan(text, target, d) })
    assertTrue("export" in r.reason, r.reason)
  }

  @Test fun aChangeOutsideTheExportersShapeIsSurgicalNotExported() {
    val text = exported(simple)
    val d = doc(text)
    val target = d.apply(DocOp.InsertNode(d.root.handle, null, DocNode.RawCode(Handle(0), "Legacy(1)"))).doc
    val w = assertIs<ScreenWriter.Write>(plan(text, target, d))
    assertEquals(ScreenWriter.How.SURGICAL, w.how)
    assertTrue("  ) {\n    Legacy(1)\n" in w.text, w.text)
  }

  // ── 5: line endings ────────────────────────────────────────────────────────

  private val withRawString = "package app.screens\n\nimport androidx.compose.runtime.Composable\nimport dev.keliver.layout.compose.Column\n" +
    "import dev.keliver.material.compose.StyledText\n\n@Composable\nfun HomeScreen(b: HomeScreenBindings) {\n  Column {\n" +
    "    StyledText(text = \"top\")\n    Legacy(\"\"\"one\r\ntwo\"\"\")\n  }\n}\n\ninterface HomeScreenBindings\n"

  @Test fun anLfFileKeepsARawStringsCrlf() {
    val d = doc(withRawString)
    val target = d.apply(DocOp.SetProp((d.root as DocNode.Widget).children[0].handle, "text", lit("TOP"))).doc
    val w = assertIs<ScreenWriter.Write>(plan(withRawString, target, d))
    assertEquals(withRawString.replace("\"top\"", "\"TOP\""), w.text)
  }

  private val mixed = "package app.screens\r\n\r\nimport androidx.compose.runtime.Composable\nimport dev.keliver.layout.compose.Column\n" +
    "import dev.keliver.material.compose.StyledText\n\n@Composable\nfun HomeScreen(b: HomeScreenBindings) {\r\n  Column {\n" +
    "    StyledText(text = \"top\")\r\n    StyledText(text = \"mid\")\n  }\n}\n\ninterface HomeScreenBindings\n"

  @Test fun aMixedFileKeepsEveryLineEndingAnEditDoesNotTouch() {
    val d = doc(mixed)
    val target = d.apply(DocOp.SetProp((d.root as DocNode.Widget).children[1].handle, "text", lit("MID"))).doc
    val w = assertIs<ScreenWriter.Write>(plan(mixed, target, d))
    assertEquals(mixed.replace("\"mid\"", "\"MID\""), w.text)
  }

  @Test fun aMixedFileEditOfACrlfLineIsRefused() {
    val d = doc(mixed)
    val target = d.apply(DocOp.SetProp((d.root as DocNode.Widget).children[0].handle, "text", lit("TOP"))).doc
    val r = assertIs<ScreenWriter.Refuse>(plan(mixed, target, d))
    assertTrue("line endings" in r.reason, r.reason)
  }

  @Test fun aCrlfFileStaysCrlf() {
    val crlf = reviewer.replace("\n", "\r\n")
    val d = doc(crlf)
    val w = assertIs<ScreenWriter.Write>(plan(crlf, insertTitle(d), d))
    assertEquals(ScreenWriter.How.SURGICAL, w.how)
    assertEquals(w.text.count { it == '\n' }, Regex("\r\n").findAll(w.text).count(), w.text)
  }
}
