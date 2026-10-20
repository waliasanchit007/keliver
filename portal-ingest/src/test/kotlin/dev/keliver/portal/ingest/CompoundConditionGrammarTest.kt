package dev.keliver.portal.ingest

import dev.keliver.portal.WidgetNode
import dev.keliver.portal.document.CondTerm
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.serializeTree
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K6: equality terms (`b.kind == "card"`, `b.tier != C.Top`) and compound
 * conditions (terms joined by `&&` only or `||` only) are grammar in screens
 * wherever K3 conditions are — conditional values (props, events, modifier
 * arguments, Margin sides) and conditional children — recognized, written
 * back surgically (a no-op is byte-identical; an edit touches only the
 * changed branch or term) and exported as compiling Kotlin. Everything else
 * stays RawCode, and says why.
 */
class CompoundConditionGrammarTest {
  private val tokens = TokenTable.scan(
    listOf("Tokens.kt" to "package app.components\n\nobject C {\n  const val White = -1\n  const val Ink = -14737633\n  const val Top = 3\n  const val Card = \"card\"\n}\n"),
  )

  private val src = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.C
    import dev.keliver.Modifier
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.Button
    import dev.keliver.material.compose.Icon
    import dev.keliver.material.compose.StyledText
    import dev.keliver.ui.Margin
    import dev.keliver.ui.dp

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        // a comment that must survive
        StyledText(text = "Title", colorArgb = if (b.kind == "card" && b.open) C.Ink else C.White)
        Icon(name = if (b.count != 0 || b.flagged) "Star" else "Info")
        StyledText(text = "x", modifier = Modifier.margin(Margin(top = if (b.tier == C.Top) 8.dp else 0.dp)))
        Row(margin = Margin(start = if (b.kind != C.Card && !b.open) 16.dp else 0.dp, top = 4.dp)) {
          StyledText(text = if (b.count == -1) "none" else "some")
        }
        Button(text = "Go", onClick = if (b.enabled && !b.busy) b::tap else null)
        if (b.kind != "cash" &&
          b.note.isNotBlank()) {
          StyledText(text = "shown")
        } else {
          StyledText(text = "hidden")
        }
        if (b.flagged == false) {
          StyledText(text = "unflagged")
        }
        b.rows.forEach { row ->
          StyledText(text = if (row.kind == "a" || b.open) "A" else "B")
        }
      }
    }

    interface HomeScreenBindings {
      val kind: String
      val open: Boolean
      val count: Int
      val flagged: Boolean
      val tier: Int
      val enabled: Boolean
      val busy: Boolean
      val note: String
      val rows: List<Row>
      fun tap()
    }

    interface Row {
      val kind: String
    }
  """.trimIndent()

  private val file = "app/screens/HomeScreen.kt"

  private fun recognize(text: String = src) = Recognizer.recognize(file, text, tokens = tokens)!!

  private fun doc(text: String = src): UiDocument = recognize(text).let { UiDocument("home", it.root, it.contract, 0, 0) }

  private fun merge(target: UiDocument, text: String = src): String =
    assertNotNull(WriteBack.merge(text, target, tokens = tokens, fileName = file))

  private fun column(d: UiDocument) = d.root as DocNode.Widget
  private fun child(d: UiDocument, i: Int) = column(d).children[i] as DocNode.Widget

  private fun containsRaw(n: DocNode): Boolean = when (n) {
    is DocNode.RawCode -> true
    is DocNode.Widget -> n.children.any(::containsRaw)
  }

  private fun assertOnly(edited: String, old: String, new: String, base: String = src) {
    assertEquals(1, base.split(old).size - 1, "the edit's anchor occurs once: $old")
    assertEquals(base.replace(old, new), edited)
  }

  private val ink = PropValue.Token("C.Ink", lit(-14737633), "app.components.C")
  private val top = PropValue.Token("C.Top", lit(3), "app.components.C")
  private val card = PropValue.Token("C.Card", lit("card"), "app.components.C")

  @Test fun everyFormIsRecognizedWithoutRawCode() {
    val d = doc()
    assertTrue(!containsRaw(d.root), recognize().rawReasons.toString())
    assertEquals(
      PropValue.Conditional("kind", "eq", whenTrue = ink, whenFalse = PropValue.Token("C.White", lit(-1), "app.components.C"), operand = lit("card"), more = listOf(CondTerm("open"))),
      child(d, 0).props["colorArgb"],
    )
    assertEquals(
      PropValue.Conditional("count", "eq", negate = true, whenTrue = lit("Star"), whenFalse = lit("Info"), operand = lit(0), more = listOf(CondTerm("flagged")), join = "or"),
      child(d, 1).props["name"],
    )
    assertEquals(
      PropValue.Conditional("tier", "eq", whenTrue = lit(8.0), whenFalse = lit(0.0), operand = top),
      child(d, 2).modifiers["Margin.margin.top"],
    )
    val row = child(d, 3)
    assertEquals(
      PropValue.Conditional("kind", "eq", negate = true, whenTrue = lit(16.0), whenFalse = lit(0.0), operand = card, more = listOf(CondTerm("open", negate = true))),
      row.props["margin.start"],
    )
    assertEquals(PropValue.Conditional("count", "eq", whenTrue = lit("none"), whenFalse = lit("some"), operand = lit(-1)), (row.children.single() as DocNode.Widget).props["text"])
    assertEquals(
      PropValue.Conditional("enabled", whenTrue = PropValue.Action("tap"), whenFalse = null, more = listOf(CondTerm("busy", negate = true))),
      child(d, 4).props["onClick"],
    )
    val shown = child(d, 5)
    assertEquals("Condition", shown.type)
    assertEquals(
      mapOf("field" to lit("kind"), "test" to lit("eq"), "negate" to lit(true), "operand" to lit("cash"), "field.1" to lit("note"), "test.1" to lit("blank"), "negate.1" to lit(true)),
      shown.props,
    )
    assertEquals(mapOf("field" to lit("flagged"), "test" to lit("eq"), "operand" to lit(false)), child(d, 6).props)
    val item = (child(d, 7).children.single() as DocNode.Widget).props["text"]
    assertEquals(PropValue.Conditional("row.kind", "eq", whenTrue = lit("A"), whenFalse = lit("B"), operand = lit("a"), more = listOf(CondTerm("open")), join = "or"), item)
  }

  @Test fun aNoOpWriteBackIsByteIdenticalAlsoInACrlfFile() {
    assertEquals(src, merge(doc()))
    val crlf = src.replace("\n", "\r\n")
    assertEquals(crlf, merge(doc(crlf), crlf))
  }

  @Test fun editingABranchOfACompoundConditionalRewritesOnlyThatBranch() {
    val d = doc()
    val color = child(d, 0).props["colorArgb"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 0).handle, "colorArgb", color.copy(whenFalse = lit(-16777216)))).doc),
      "else C.White)", "else -16777216)",
    )
    val onClick = child(d, 4).props["onClick"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 4).handle, "onClick", onClick.copy(whenTrue = PropValue.Action("go")))).doc),
      "if (b.enabled && !b.busy) b::tap", "if (b.enabled && !b.busy) b::go",
    )
    val start = child(d, 3).props["margin.start"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 3).handle, "margin.start", start.copy(whenTrue = lit(12.0)))).doc),
      "!b.open) 16.dp else", "!b.open) 12.dp else",
    )
    // In a CRLF file too, the rest keeps its CRLF.
    val crlf = src.replace("\n", "\r\n")
    val c = doc(crlf)
    val icon = child(c, 1).props["name"] as PropValue.Conditional
    assertEquals(
      crlf.replace("|| b.flagged) \"Star\"", "|| b.flagged) \"Up\""),
      merge(c.apply(DocOp.SetProp(child(c, 1).handle, "name", icon.copy(whenTrue = lit("Up")))).doc, crlf),
    )
  }

  @Test fun editingOneTermRewritesOnlyThatTermAndKeepsTheAuthorsLayout() {
    val d = doc()
    // A Condition's equality operand, in a condition the author split over two lines.
    assertOnly(merge(d.apply(DocOp.SetProp(child(d, 5).handle, "operand", lit("card"))).doc), "b.kind != \"cash\" &&\n", "b.kind != \"card\" &&\n")
    assertOnly(merge(d.apply(DocOp.SetProp(child(d, 5).handle, "negate.1", lit(false))).doc), "  b.note.isNotBlank()) {", "  b.note.isBlank()) {")
    // A conditional value's term: only that term.
    val color = child(d, 0).props["colorArgb"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 0).handle, "colorArgb", color.copy(operand = card))).doc),
      "if (b.kind == \"card\" && b.open)", "if (b.kind == C.Card && b.open)",
    )
    // Another shape (a term more, another join) rewrites the condition, and only it.
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 0).handle, "colorArgb", color.copy(more = color.more + CondTerm("busy", negate = true)))).doc),
      "if (b.kind == \"card\" && b.open)", "if (b.kind == \"card\" && b.open && !b.busy)",
    )
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 0).handle, "colorArgb", color.copy(join = "or"))).doc),
      "if (b.kind == \"card\" && b.open)", "if (b.kind == \"card\" || b.open)",
    )
  }

  @Test fun aComparedTokenAddsItsImport() {
    val plain = src.replace("import app.components.C\n", "")
      .replace("colorArgb = if (b.kind == \"card\" && b.open) C.Ink else C.White", "colorArgb = if (b.kind == \"card\" && b.open) 1 else 2")
      .replace("if (b.tier == C.Top) 8.dp else 0.dp", "if (b.tier == 3) 8.dp else 0.dp")
      .replace("if (b.kind != C.Card && !b.open) 16.dp", "if (b.kind != \"card\" && !b.open) 16.dp")
    val d = doc(plain)
    assertTrue(!containsRaw(d.root), recognize(plain).rawReasons.toString())
    val margin = child(d, 2).modifiers["Margin.margin.top"] as PropValue.Conditional
    val out = merge(d.apply(DocOp.SetModifier(child(d, 2).handle, "Margin.margin.top", margin.copy(operand = top))).doc, plain)
    assertTrue("import app.components.C\n" in out, out)
    assertTrue("Margin(top = if (b.tier == C.Top) 8.dp else 0.dp)" in out, out)
  }

  @Test fun theFullExportCompilesAndReIngestsToTheSameTree() {
    val d = doc()
    val exported = exportKotlin(d.toWidgetTree(), functionName = "HomeScreen")
    val parsed = PsiEnv.parse("Exported.kt", exported)
    assertTrue(PsiTreeUtil.findChildrenOfType(parsed, PsiErrorElement::class.java).isEmpty(), exported)
    assertTrue("colorArgb = if (b.kind == \"card\" && b.open) C.Ink else C.White," in exported, exported)
    assertTrue("if (b.kind != \"cash\" && b.note.isNotBlank()) {" in exported, exported)
    assertTrue("Modifier.margin(Margin(top = if (b.tier == C.Top) 8.dp else 0.dp))" in exported, exported)
    for (member in listOf("val kind: String", "val count: Int", "val flagged: Boolean", "val tier: Int", "val busy: Boolean", "fun tap()")) {
      assertTrue("  $member\n" in exported, "$member in\n$exported")
    }
    val again = Recognizer.recognize(file, "package app.screens\n$exported", tokens = tokens)!!
    fun canonical(t: WidgetNode): WidgetNode = t.copy(id = 0, children = t.children.map(::canonical))
    assertEquals(
      serializeTree(canonical(d.toWidgetTree())),
      serializeTree(canonical(UiDocument("home", again.root, again.contract, 0, 0).toWidgetTree())),
    )
  }

  @Test fun formsOutsideTheGrammarStayRawCodeAndSayWhy() {
    fun reasonOf(body: String): String {
      val text = src.replace(Regex("(?s)  Column \\{.*\\n  }\\n}")) { "  Column {\n    $body\n  }\n}" }
        .replace("  val note: String\n", "  val note: String\n  val ratio: Double\n  val maybe: String?\n  val other: String\n")
      val r = recognize(text)
      assertIs<DocNode.RawCode>((r.root as DocNode.Widget).children.single(), body)
      return r.rawReasons.single()
    }
    fun refused(cond: String, why: String) {
      val asChild = reasonOf("if ($cond) {\n      StyledText(text = \"x\")\n    }")
      assertTrue(why in asChild, "`$cond` as a Condition: $asChild")
      val asValue = reasonOf("StyledText(text = if ($cond) \"a\" else \"b\")")
      assertTrue(why in asValue, "`$cond` as a value: $asValue")
    }
    refused("b.open && b.flagged || b.busy", "mixes `&&` and `||`")
    refused("b.open && (b.flagged || b.busy)", "parenthes")
    refused("!(b.open && b.busy)", "parenthes")
    refused("b.ratio == 1.5", "Double")
    refused("b.kind == b.other", "two fields")
    refused("\"card\" == b.kind", "on the left")
    refused("b.count == \"3\"", "declared Int")
    refused("b.maybe == \"x\"", "nullable")
    refused("b.kind === \"card\"", "===")
    refused("b.count == 3L", "Int, String or Boolean")
    refused("b.count == -2147483648", "Long")
    refused("b.count == 0x10", "Int, String or Boolean")
    refused("b.kind == \"${'$'}{b.other}\"", "Int, String or Boolean")
    refused("b.open && /* why */ b.busy", "comment")
    refused("b.count > 0", "outside the portal grammar")
    // Screens have no derived locals: a `val` stays code, and so does a condition naming it.
    val text = src.replace("\n  Column {\n", "\n  Column {\n    val ready = b.enabled && !b.busy\n")
    val r = recognize(text)
    assertIs<DocNode.RawCode>((r.root as DocNode.Widget).children.first())
    // Its text is kept byte for byte when something else is edited.
    val d = UiDocument("home", r.root, r.contract, 0, 0)
    val title = (d.root as DocNode.Widget).children[1] as DocNode.Widget
    val edited = d.apply(DocOp.SetProp(title.handle, "text", lit("T"))).doc
    assertOnly(merge(edited, text), "StyledText(text = \"Title\",", "StyledText(text = \"T\",", base = text)
  }

  @Test fun aNewConditionWithEveryFormIsInsertedAndReadsBack() {
    val d = doc()
    val node = DocNode.Widget(
      Handle(0), "Condition",
      mapOf("field" to lit("tier"), "test" to lit("eq"), "operand" to top, "field.1" to lit("open"), "join" to lit("or")),
      children = listOf(DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("new")))),
    )
    val out = merge(d.apply(DocOp.InsertNode(column(d).handle, child(d, 6).handle, node)).doc)
    assertTrue("    if (b.tier == C.Top || b.open) {\n      StyledText(\n        text = \"new\",\n      )\n    }\n" in out, out)
  }
}
