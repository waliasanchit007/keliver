package dev.keliver.portal.ingest

import dev.keliver.portal.document.Contract
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
    // The interface is the one the screen takes: another one in the file is not it (K4 last review, case C).
    // A `…Bindings` name not declared here names another interface: the one here is not taken in its place.
    for ((tail, param) in listOf(ROW_INTERFACE to "SBindings", BINDINGS + "\n" + ROW_INTERFACE to "OtherBindings")) {
      val src = screen("StyledText(text = \"a §{b.name}\")", tail = tail, param = param)
      assertIs<DocNode.RawCode>(first(src), param)
      assertTrue(rec(src).rawReasons.any { "the screen takes `$param`, which is not declared in this file" in it && "b.name" in it }, rec(src).rawReasons.toString())
      // A plain binding is still grammar; only a template prints the value.
      assertIs<DocNode.Widget>(first(screen("StyledText(text = b.name)", tail = tail, param = param)), param)
    }
  }

  @Test fun anEditTheDocumentCannotCheckIsRefusedAtWriteBack() {
    // The contract carries no item types: the document lets a template read `row.price`
    // (a Double), but the written file would not read back as the document — refused.
    val src = screen("StyledText(text = \"x\")", "StyledText(text = \"a §{row.name}\")", BINDINGS + "\n" + ROW_INTERFACE)
    val r = rec(src)
    val base = UiDocument("s", r.root, r.contract, 0, 1000)
    val text = (r.root.children[1] as DocNode.Widget).children.single().handle
    val edited = base.apply(DocOp.SetProp(text, "text", PropValue.Template(listOf(PropValue.Bind("row.price"))))).doc
    val plan = ScreenWriter.plan(src, edited, base, src, fileName = "app/screens/S.kt", functionName = "S", packageName = "app.screens")
    assertIs<ScreenWriter.Refuse>(plan, plan.toString())
  }

  @Test fun aFullyQualifiedTypeIsTheSameType() {
    val tail = BINDINGS.replace("  val name: String\n", "  val name: String\n  val qs: kotlin.String\n  val qi: kotlin.Int\n  val ql: kotlin.Long\n  val qb: kotlin.Boolean\n") + "\n" + ROW_INTERFACE
    assertIs<DocNode.Widget>(first(screen("StyledText(text = \"§{b.qs} §{b.qi} §{b.ql} §{b.qb}\")", tail = tail)))
    assertIs<DocNode.Widget>(first(screen("SegmentedButtonRow(options = listOf(b.qs))", tail = tail)))
    assertIs<DocNode.Widget>(first(screen("StyledBox(gradientColorsArgb = listOf(b.qi))", tail = tail)))
  }

  // ── K4 last review: what the document accepts, write-back writes (a refusal there blocks the screen). ──

  private fun doc(src: String): UiDocument = rec(src).let { UiDocument("s", it.root, it.contract, 0, 1000) }

  /** The document's verdict on `StyledText(text = "Hi ${b.<field>}")` as the first statement — null = accepted. */
  private fun templateVerdict(src: String, field: String): String? =
    doc(src).let { d -> d.tryApply(DocOp.SetProp((d.root as DocNode.Widget).children[0].handle, "text", hi(field))).error }

  /**
   * What write-back makes of that edit, made whatever the document says (its
   * checks bypassed) — so a refusal there is what op time must prevent.
   */
  private fun writeBack(src: String, value: PropValue): ScreenWriter.Plan {
    val base = doc(src)
    val edited = base.copy(contract = Contract()).apply(DocOp.SetProp((base.root as DocNode.Widget).children[0].handle, "text", value)).doc
      .copy(contract = base.contract)
    return ScreenWriter.plan(src, edited, base, src, fileName = "app/screens/S.kt", functionName = "S", packageName = "app.screens")
  }

  private fun hi(field: String) = PropValue.Template(listOf(PropValue.Lit("s", s = "Hi "), PropValue.Bind(field)))

  /** Refused by the document exactly when write-back would refuse it. */
  private fun assertConsistent(src: String, field: String, accepted: Boolean) {
    val verdict = templateVerdict(src, field)
    val plan = writeBack(src, hi(field))
    if (accepted) {
      assertEquals(null, verdict, field)
      assertIs<ScreenWriter.Write>(plan, "$field: $plan")
    } else {
      assertTrue(verdict != null && "`$field`" in verdict && "not readable here" in verdict, "$field: $verdict")
      assertIs<ScreenWriter.Refuse>(plan, "$field: $plan")
    }
  }

  private val stmt = "StyledText(text = \"x\")"

  @Test fun caseA_noBindingsInterfaceInTheFile() {
    val src = screen(stmt, tail = ROW_INTERFACE)
    val contract = rec(src).contract
    val why = assertNotNull(contract.unlistedFieldsUnknown)
    assertTrue("`SBindings`" in why && "not declared in this file" in why, why)
    assertConsistent(src, "name", accepted = false)
    // U37: a plain binding of a field the file does not read is refused (the editor cannot tell
    // whether the interface declared elsewhere has it); one of a field the file reads is written.
    assertIs<ScreenWriter.Refuse>(writeBack(src, PropValue.Bind("name")))
    assertIs<ScreenWriter.Write>(writeBack(screen(stmt, rowStmt = "StyledText(text = b.name)", tail = ROW_INTERFACE), PropValue.Bind("name")))
  }

  @Test fun caseB_aFieldInheritedFromAnotherFile() {
    val src = screen(stmt, tail = BINDINGS.replace("interface SBindings {", "interface SBindings : ExternalBase {") + "\n" + ROW_INTERFACE)
    val why = assertNotNull(rec(src).contract.unlistedFieldsUnknown)
    assertTrue("`ExternalBase`" in why && "another file" in why, why)
    assertConsistent(src, "title", accepted = false)
    // A field the interface declares in the file keeps its type.
    assertConsistent(src, "name", accepted = true)
    // U37: a plain binding of an inherited field the file does not read yet is refused (it may be
    // inherited or new); one the file reads is written, and not declared again.
    assertIs<ScreenWriter.Refuse>(writeBack(src, PropValue.Bind("title")))
    val reads = screen(stmt, rowStmt = "StyledText(text = b.title)", tail = BINDINGS.replace("interface SBindings {", "interface SBindings : ExternalBase {") + "\n" + ROW_INTERFACE)
    val written = assertIs<ScreenWriter.Write>(writeBack(reads, PropValue.Bind("title"))).text
    assertTrue("val title" !in written, written)
  }

  @Test fun caseE_aNewFieldBesideASupertypeFromAnotherFile() {
    // Contract write-back would declare `fresh`, but the read-back before it cannot type it.
    val src = screen(stmt, tail = BINDINGS.replace("interface SBindings {", "interface SBindings : ExternalBase {") + "\n" + ROW_INTERFACE)
    assertConsistent(src, "fresh", accepted = false)
  }

  @Test fun caseD_aNewFieldOfAnInterfaceReadWholeIsWrittenAndDeclared() {
    val src = screen(stmt, tail = BINDINGS + "\n" + ROW_INTERFACE)
    assertEquals(null, rec(src).contract.unlistedFieldsUnknown)
    assertConsistent(src, "fresh", accepted = true)
    val written = (writeBack(src, hi("fresh")) as ScreenWriter.Write).text
    assertTrue("val fresh: String" in written.substringAfter("interface SBindings").substringBefore("}"), written)
    // A same-file supertype is read too: the interface is still read whole.
    val sameFile = screen(stmt, tail = BINDINGS.replace("interface SBindings {", "interface SBindings : Base {") + "\n" + ROW_INTERFACE + "\ninterface Base {\n  val label: String\n}\n")
    assertEquals(null, rec(sameFile).contract.unlistedFieldsUnknown)
    assertConsistent(sameFile, "label", accepted = true)
    assertConsistent(sameFile, "fresh", accepted = true)
  }

  @Test fun caseC_theInterfaceIsTheOneTheScreenTakesNotTheFirstInTheFile() {
    val other = "interface AOtherBindings {\n  val name: Double\n  val extra: String\n}\n"
    val src = screen("StyledText(text = \"a §{b.name}\")", tail = other + BINDINGS + "\n" + ROW_INTERFACE)
    val r = rec(src)
    assertIs<DocNode.Widget>(r.root.children[0], r.rawReasons.toString())
    assertEquals(mapOf("name" to "String", "on" to "Boolean", "rows" to "List<Row>"), r.contract.fields)
    assertEquals("SBindings", r.bindingsInterface?.name)
    assertEquals(null, r.contract.unlistedFieldsUnknown)
    // A new field is declared in the screen's interface, not the first one.
    val written = (writeBack(src, hi("fresh")) as ScreenWriter.Write).text
    assertTrue("val fresh: String" in written.substringAfter("interface SBindings").substringBefore("}"), written)
    assertTrue("fresh" !in written.substringAfter("interface AOtherBindings").substringBefore("}"), written)
    assertConsistent(src, "fresh", accepted = true)
  }

  @Test fun caseH_aNullableParameterTakesTheSameInterface() {
    val src = screen("StyledText(text = \"a §{b.name}\")", tail = BINDINGS + "\n" + ROW_INTERFACE, param = "SBindings?")
    val r = rec(src)
    assertIs<DocNode.Widget>(r.root.children[0], r.rawReasons.toString())
    assertEquals("SBindings", r.bindingsInterface?.name)
    assertEquals("String", r.contract.fields["name"])
    assertEquals(null, r.contract.unlistedFieldsUnknown)
    assertEquals(null, templateVerdict(src, "name"))
  }

  // ── K4 final review: the parameter's type as Kotlin reads it, not its text. ──

  private fun why(param: String, tail: String): String? = rec(screen(stmt, tail = tail, param = param)).contract.unlistedFieldsUnknown
  private fun ifaceOf(param: String, tail: String): String? = rec(screen(stmt, tail = tail, param = param)).bindingsInterface?.name

  @Test fun typealiasesAnnotationsAndNestingResolveToTheInterface() {
    val decoy = "interface AOtherBindings {\n  val name: Double\n}\n"
    val tail = decoy + BINDINGS + "\n" + ROW_INTERFACE
    for (param in listOf("SBindings", "@Suppress(\"x\") SBindings", "@Suppress(\"x\") SBindings?", "app.screens.SBindings", "SBindings<Int>")) {
      assertEquals("SBindings", ifaceOf(param, tail), param)
      assertEquals(null, why(param, tail), param)
    }
    assertEquals("SBindings", ifaceOf("HB", "typealias HB = SBindings\n$tail"))
    assertEquals("SBindings", ifaceOf("HB?", "typealias HB = HB2\ntypealias HB2 = SBindings?\n$tail"))
    val aliased = screen(stmt, tail = tail, param = "HB").replace("import androidx", "import app.screens.SBindings as HB\nimport androidx")
    assertEquals("SBindings", rec(aliased).bindingsInterface?.name, "an import alias")
    val nested = decoy + "object Screens {\n  interface SBindings {\n    val name: String\n  }\n}\n" + ROW_INTERFACE
    assertEquals("SBindings", ifaceOf("Screens.SBindings", nested))
    assertEquals(mapOf("name" to "String"), rec(screen(stmt, tail = nested, param = "Screens.SBindings")).contract.fields)
    assertEquals(null, why("Screens.SBindings", nested))
  }

  @Test fun whatTheParameterNamesIsSaidAccurately() {
    val nested = "object Screens {\n  interface Other\n}\n" + ROW_INTERFACE
    val w1 = assertNotNull(why("Screens.SBindings", nested))
    assertTrue("`Screens.SBindings`" in w1 && "`Screens` in this file declares no `SBindings`" in w1 && "not declared in this file" !in w1, w1)
    val w2 = assertNotNull(why("@Suppress(\"x\") SBindings?", ROW_INTERFACE))
    assertEquals("the screen takes `SBindings`, which is not declared in this file", w2)
    val w3 = assertNotNull(why("HB", "typealias HB = () -> Unit\n" + BINDINGS + ROW_INTERFACE))
    assertTrue("`HB`" in w3 && "not an interface" in w3, w3)
    val w4 = assertNotNull(why("HB", "typealias HB = other.SBindings\n" + ROW_INTERFACE))
    assertTrue("`HB`" in w4 && "typealias of `other.SBindings`" in w4 && "not declared in this file" in w4, w4)
    val w5 = assertNotNull(why("HB", "typealias HB = HB2\ntypealias HB2 = HB\n" + BINDINGS + ROW_INTERFACE))
    assertTrue("cycle" in w5, w5)
  }

  @Test fun aNameNotDeclaredHereFallsBackToTheOnlyBindingsInterface() {
    // `SModel` may be a typealias in another file: with one `…Bindings` interface here it is that one (the 0.3.x rule).
    assertEquals("SBindings", ifaceOf("SModel", BINDINGS + ROW_INTERFACE))
    assertEquals(null, why("SModel", BINDINGS + ROW_INTERFACE))
    // Two of them: ambiguous — none is taken, and the reason says so.
    val two = "interface AOtherBindings {\n  val name: Double\n}\n" + BINDINGS + ROW_INTERFACE
    assertEquals(null, ifaceOf("SModel", two))
    val w = assertNotNull(why("SModel", two))
    assertTrue("`SModel`" in w && "not declared in this file" in w && "`AOtherBindings`" in w && "`SBindings`" in w, w)
    // A `…Bindings` name, or a qualified one from another package, names an interface elsewhere: no fallback.
    assertEquals(null, ifaceOf("OtherBindings", BINDINGS + ROW_INTERFACE))
    assertEquals(null, ifaceOf("other.SModel", BINDINGS + ROW_INTERFACE))
  }

  @Test fun aScreenWithoutAParameterSaysSo() {
    val src = """
      package app.screens

      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.StyledText

      @Composable
      fun S() {
        Column {
          StyledText(text = "x")
        }
      }
    """.trimIndent() + "\n\n" + BINDINGS.trimIndent() + "\n"
    val r = rec(src)
    assertEquals("the screen has no Bindings parameter", r.contract.unlistedFieldsUnknown)
    assertConsistent(src, "name", accepted = false)
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
