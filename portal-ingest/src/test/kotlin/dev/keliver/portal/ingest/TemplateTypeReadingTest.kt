package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K4: a template reads only a reference whose type the device (Kotlin/JS) and
 * the preview print alike — so ingest reads that type where it is written:
 * an item field's in the item type declared in the same file, a screen
 * field's in the Bindings interface (and its same-file supertypes). A type
 * it cannot read is unknown, and a template reading it stays RawCode.
 */
class TemplateTypeReadingTest {
  // § is `$` (kept out of the Kotlin raw string).
  private fun screen(stmt: String, rowStmt: String = "StyledText(text = \"x\")", tail: String = BINDINGS, param: String = "SBindings"): String = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.SegmentedButtonRow
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText

    @Composable
    fun S(b: $param) {
      Column {
        $stmt
        b.rows.forEach { row ->
          $rowStmt
        }
      }
    }
  """.trimIndent().replace('§', '$') + "\n\n" + tail.trimIndent() + "\n"

  private fun rec(src: String) = assertNotNull(Recognizer.recognize("app/screens/S.kt", src))
  private fun column(src: String) = rec(src).root
  private fun first(src: String): DocNode = column(src).children[0]
  private fun rowChild(src: String): DocNode = ((column(src).children[1] as DocNode.Widget).children.single())

  @Test fun anItemFieldIsTypedByTheItemTypeInTheFile() {
    for (row in listOf(ROW_INTERFACE, ROW_DATA_CLASS)) {
      val tail = BINDINGS + "\n" + row
      assertIs<DocNode.Widget>(rowChild(screen("StyledText(text = \"x\")", "StyledText(text = \"a §{row.name}\")", tail)), row)
      assertIs<DocNode.RawCode>(rowChild(screen("StyledText(text = \"x\")", "StyledText(text = \"a §{row.price}\")", tail)), row)
      assertIs<DocNode.RawCode>(rowChild(screen("StyledText(text = \"x\")", "StyledText(text = \"a §{row.nick}\")", tail)), row)
      assertIs<DocNode.RawCode>(rowChild(screen("StyledText(text = \"x\")", "StyledText(text = if (b.on) \"§{row.price}\" else \"-\")", tail)), row)
      // A bound list element is declared as the element type.
      assertIs<DocNode.RawCode>(rowChild(screen("StyledText(text = \"x\")", "SegmentedButtonRow(options = listOf(row.nick))", tail)), row)
      assertIs<DocNode.Widget>(rowChild(screen("StyledText(text = \"x\")", "SegmentedButtonRow(options = listOf(row.name))", tail)), row)
    }
    // A nested Repeat's item shadows the outer one only inside it.
    val nested = screen(
      "StyledText(text = \"x\")",
      "b.subs.forEach { row -> StyledText(text = \"§{row.price}\") }\n          StyledText(text = \"§{row.name}\")\n          StyledText(text = \"§{row.price}\")",
      BINDINGS.replace("val rows: List<Row>", "val rows: List<Row>\n  val subs: List<Sub>") + "\n" + ROW_INTERFACE + "\ninterface Sub {\n  val price: String\n}",
    )
    val rows = (column(nested).children[1] as DocNode.Widget).children
    assertIs<DocNode.Widget>(rows[0], rows.toString())
    assertIs<DocNode.Widget>(rows[1], rows.toString())
    assertIs<DocNode.RawCode>(rows[2], rows.toString())
  }

  @Test fun anItemTypeNotInTheFileKeepsTodaysReading() {
    // Documented limit: without the declaration, the type of `row.price` is not known here.
    val tail = BINDINGS.replace("List<Row>", "List<ExternalRow>")
    assertIs<DocNode.Widget>(rowChild(screen("StyledText(text = \"x\")", "StyledText(text = \"a §{row.price}\")", tail)))
  }

  @Test fun aBindingsFieldWithoutADeclaredTypeIsUnknown() {
    val tail = BINDINGS.replace("  val name: String\n", "  val name: String\n  val x get() = 1.5\n") + "\n" + ROW_INTERFACE
    assertIs<DocNode.RawCode>(first(screen("StyledText(text = \"a §{b.x}\")", tail = tail)))
    // The contract does not type it String: the document refuses a template reading it too.
    val r = rec(screen("StyledText(text = \"x\")", tail = tail))
    assertNotEquals("String", r.contract.fields["x"])
    val d = UiDocument("s", r.root, r.contract, 0, 1000)
    val err = d.tryApply(DocOp.SetProp(r.root.children[0].handle, "text", PropValue.Template(listOf(PropValue.Bind("x"))))).error
    assertTrue(err != null && "`x`" in err, err)
  }

  @Test fun anInheritedFieldIsReadInASameFileSupertypeElseUnknown() {
    val base = "interface Base {\n  val score: Double\n  val label: String\n}\n"
    val inFile = BINDINGS.replace("interface SBindings {", "interface SBindings : Base {") + "\n" + ROW_INTERFACE + "\n" + base
    assertIs<DocNode.RawCode>(first(screen("StyledText(text = \"a §{b.score}\")", tail = inFile)))
    assertIs<DocNode.Widget>(first(screen("StyledText(text = \"a §{b.label} §{b.name}\")", tail = inFile)))
    val contract = rec(screen("StyledText(text = \"x\")", tail = inFile)).contract
    assertEquals("Double", contract.fields["score"])
    assertEquals("String", contract.fields["label"])
    // A supertype declared elsewhere: a field the interface does not declare is unknown.
    val elsewhere = BINDINGS.replace("interface SBindings {", "interface SBindings : ExternalBase {") + "\n" + ROW_INTERFACE
    assertIs<DocNode.RawCode>(first(screen("StyledText(text = \"a §{b.score}\")", tail = elsewhere)))
    assertIs<DocNode.Widget>(first(screen("StyledText(text = \"a §{b.name}\")", tail = elsewhere)))
  }

  @Test fun withoutTheBindingsInterfaceInTheFileATemplateReadsNoBinding() {
    for ((tail, param) in listOf(ROW_INTERFACE to "SBindings", BINDINGS.replace("SBindings", "SModel") + "\n" + ROW_INTERFACE to "SModel")) {
      val src = screen("StyledText(text = \"a §{b.name}\")", tail = tail, param = param)
      assertIs<DocNode.RawCode>(first(src), param)
      assertTrue(rec(src).rawReasons.any { "no `" in it && "Bindings" in it && "b.name" in it }, rec(src).rawReasons.toString())
      // A plain binding is still grammar; only a template prints the value.
      assertIs<DocNode.Widget>(first(screen("StyledText(text = b.name)", tail = tail, param = param)), param)
    }
  }

  @Test fun aFullyQualifiedTypeIsTheSameType() {
    val tail = BINDINGS.replace("  val name: String\n", "  val name: String\n  val qs: kotlin.String\n  val qi: kotlin.Int\n  val ql: kotlin.Long\n  val qb: kotlin.Boolean\n") + "\n" + ROW_INTERFACE
    assertIs<DocNode.Widget>(first(screen("StyledText(text = \"§{b.qs} §{b.qi} §{b.ql} §{b.qb}\")", tail = tail)))
    assertIs<DocNode.Widget>(first(screen("SegmentedButtonRow(options = listOf(b.qs))", tail = tail)))
    assertIs<DocNode.Widget>(first(screen("StyledBox(gradientColorsArgb = listOf(b.qi))", tail = tail)))
  }

  private companion object {
    const val BINDINGS = """
interface SBindings {
  val name: String
  val on: Boolean
  val rows: List<Row>
}
"""
    const val ROW_INTERFACE = """
interface Row {
  val price: Double
  val nick: String?
  val name: String
}
"""
    const val ROW_DATA_CLASS = """
data class Row(val price: Double, val nick: String?, val name: String)
"""
  }
}
