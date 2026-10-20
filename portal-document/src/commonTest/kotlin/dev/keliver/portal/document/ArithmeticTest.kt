package dev.keliver.portal.document

import dev.keliver.portal.Arith
import dev.keliver.portal.ArithOp
import dev.keliver.portal.Bind
import dev.keliver.portal.IntDp
import dev.keliver.portal.PropKind
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.arithKotlin
import dev.keliver.portal.arithValue
import dev.keliver.portal.collectContract
import dev.keliver.portal.dbl
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.int
import dev.keliver.portal.serializeTree
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K7: Int arithmetic values and Ints used as Dps — Kotlin's 32-bit semantics
 * on every target this test runs on (jvm, js, wasm), the canonical form, both
 * wires, the exporter, the contract, and what the document refuses.
 */
class ArithmeticTest {
  private fun a(op: String, l: PropValue, r: PropValue) = PropValue.Arith(op, l, r)
  private fun b(f: String) = PropValue.Bind(f)
  private val gap = PropValue.Token("C.Gap", lit(8), "app.components.C")

  private fun doc(contract: Contract = Contract(), vararg children: DocNode): UiDocument =
    UiDocument("t", DocNode.Widget(Handle(1), "Column", children = children.toList()), contract, 0, 100)

  private fun text(h: Long, vararg props: Pair<String, PropValue>) =
    DocNode.Widget(Handle(h), "StyledText", props = mapOf("text" to lit("x")) + props)
  private fun spacer(h: Long) = DocNode.Widget(Handle(h), "Spacer")

  private fun refused(d: UiDocument, op: DocOp): String = assertNotNull(d.tryApply(op).error, "expected a refusal: $op")
  private fun accepted(d: UiDocument, op: DocOp): UiDocument {
    val t = d.tryApply(op)
    assertNull(t.error, "expected acceptance: $op")
    return t.result!!.doc
  }
  private fun set(h: Long, k: String, v: PropValue) = DocOp.SetProp(Handle(h), k, v)

  // ── Kotlin's Int semantics, on this target ────────────────────────────

  @Test fun theInterpreterComputesWhatKotlinComputesAtTheBoundaries() {
    fun e(op: ArithOp, x: Int, y: Int) = arithValue(Arith(op, x, y))
    assertEquals(Int.MIN_VALUE, e(ArithOp.Plus, Int.MAX_VALUE, 1))
    assertEquals(Int.MAX_VALUE, e(ArithOp.Minus, Int.MIN_VALUE, 1))
    assertEquals(Int.MIN_VALUE, e(ArithOp.Div, Int.MIN_VALUE, -1))
    assertEquals(0, e(ArithOp.Rem, Int.MIN_VALUE, -1))
    assertEquals(0, e(ArithOp.Times, 65536, 65536))
    assertEquals(-67153019, e(ArithOp.Times, 123456789, 987654321))
    assertEquals(-3, e(ArithOp.Div, -7, 2)) // toward zero
    assertEquals(-1, e(ArithOp.Rem, -7, 3)) // the dividend's sign
    assertEquals(1, e(ArithOp.Rem, 7, -3))
    assertEquals(-2, e(ArithOp.Div, 7, -3))
    assertNull(e(ArithOp.Div, 1, 0))
    assertNull(e(ArithOp.Rem, 1, 0))
    // An operand nobody knows makes the whole expression unknown.
    assertNull(arithValue(Arith(ArithOp.Plus, Bind("x"), 1)))
    assertEquals(4, arithValue(Arith(ArithOp.Plus, Bind("x"), 1)) { (it as? Bind)?.let { 3 } })
    assertEquals(9, arithValue(Arith(ArithOp.Plus, Token("C.Gap", 8), 1)))
  }

  @Test fun sourceKeepsTheTreesShapeWithTheParenthesesItNeeds() {
    val ref = { f: String -> "b.$f" }
    val x = Bind("x")
    assertEquals("b.x * 2 + 8", arithKotlin(Arith(ArithOp.Plus, Arith(ArithOp.Times, x, 2), 8), ref))
    assertEquals("b.x * (2 + 8)", arithKotlin(Arith(ArithOp.Times, x, Arith(ArithOp.Plus, 2, 8)), ref))
    assertEquals("b.x - (2 - 8)", arithKotlin(Arith(ArithOp.Minus, x, Arith(ArithOp.Minus, 2, 8)), ref))
    assertEquals("b.x - 2 - 8", arithKotlin(Arith(ArithOp.Minus, Arith(ArithOp.Minus, x, 2), 8), ref))
    assertEquals("b.x * (2 / 5)", arithKotlin(Arith(ArithOp.Times, x, Arith(ArithOp.Div, 2, 5)), ref))
    assertEquals("b.x * 2 / 5", arithKotlin(Arith(ArithOp.Div, Arith(ArithOp.Times, x, 2), 5), ref))
    assertEquals("b.x + (2 + 8)", arithKotlin(Arith(ArithOp.Plus, x, Arith(ArithOp.Plus, 2, 8)), ref))
    assertEquals("b.x - -3", arithKotlin(Arith(ArithOp.Minus, x, -3), ref))
    assertEquals("C.Gap % 3", arithKotlin(Arith(ArithOp.Rem, Token("C.Gap", 8), 3), ref))
    assertEquals("b.x.dp", arithKotlin(IntDp(x), ref))
    assertEquals("(b.x / 2).dp", arithKotlin(IntDp(Arith(ArithOp.Div, x, 2)), ref))
    assertEquals("Dp(b.x.toDouble())", arithKotlin(IntDp(x, ctor = true), ref))
    assertEquals("Dp((b.x + 4).toDouble())", arithKotlin(IntDp(Arith(ArithOp.Plus, x, 4), ctor = true), ref))
  }

  // ── Canonical form ────────────────────────────────────────────────────

  @Test fun anExpressionOfLiteralsIsTheLiteralItComputesButOperandsAreNeverFolded() {
    assertEquals(lit(8), canonicalValue(a("*", lit(4), lit(2))))
    assertEquals(lit(Int.MIN_VALUE), canonicalValue(a("+", lit(Int.MAX_VALUE), lit(1))))
    val kept = a("*", b("size"), a("+", lit(2), lit(3)))
    assertEquals(kept, canonicalValue(kept))
    // A token is a reference: not folded.
    assertEquals(a("+", gap, lit(1)), canonicalValue(a("+", gap, lit(1))))
    // An Int used as a Dp of a literal is the Dp literal.
    assertEquals(lit(8.0), canonicalValue(PropValue.IntDp(a("*", lit(4), lit(2)))))
    assertEquals(lit(3.0), canonicalValue(PropValue.IntDp(lit(3), DP_CTOR_FORM)))
    // An invalid expression stays, for the document to refuse.
    assertEquals(a("/", lit(1), lit(0)), canonicalValue(a("/", lit(1), lit(0))))
    // Mapping the atoms (the relay's token pass) keeps the operand tree.
    assertEquals(kept, kept.mapAtoms { it })
    assertEquals(lit(9), a("+", gap, lit(1)).mapAtoms { if (it is PropValue.Token) it.value else it })
  }

  // ── Wires ─────────────────────────────────────────────────────────────

  @Test fun bothRoundTripOnTheDocumentWireWithDefaultsLeftOut() {
    val node: DocNode = DocNode.Widget(
      Handle(2), "Spacer",
      props = mapOf("height" to PropValue.IntDp(a("/", b("gap"), lit(2))), "width" to PropValue.IntDp(b("w"), DP_CTOR_FORM)),
    )
    val json = DocJson.encodeToString(node)
    assertEquals(node, DocJson.decodeFromString<DocNode>(json))
    assertFalse("\"form\":null" in json, json)
    assertTrue("\"form\":\"Dp\"" in json, json)
    val batch = OpBatch(1, OpEnvelope("s", 0), listOf(set(2, "fontSize", a("+", gap, b("size")))))
    assertEquals(batch, DocJson.decodeFromString<OpBatch>(DocJson.encodeToString(batch)))
  }

  @Test fun theDeviceWireCarriesTheValueForOlderReadersWhereItCanBeComputed() {
    val d = doc(
      Contract(),
      text(2, "fontSize" to a("*", gap, lit(2))),
      text(3, "fontSize" to a("+", b("size"), lit(1))),
      DocNode.Widget(Handle(4), "Spacer", props = mapOf("height" to PropValue.IntDp(a("-", gap, lit(2)), DP_CTOR_FORM))),
      text(5, "fontSize" to PropValue.Conditional("on", whenTrue = a("+", b("size"), lit(1)), whenFalse = lit(3))),
    )
    val tree = d.toWidgetTree()
    assertEquals(Arith(ArithOp.Times, Token("C.Gap", 8, "app.components.C"), 2), tree.children[0].props["fontSize"])
    val json = serializeTree(tree)
    // Computable: the value under "i" (or "d") — what a reader that predates K7 renders.
    assertTrue("\"fontSize\":{\"i\":16,\"arith\":" in json, json)
    assertTrue(Regex("\"height\":\\{\"d\":6(\\.0)?,\"intDp\":").containsMatchIn(json), json) // Kotlin/JS prints 6.0 as 6
    assertTrue("\"dpForm\":\"Dp\"" in json, json)
    // Not computable (an unmocked binding): no plain key — such a reader renders the default.
    assertTrue("\"fontSize\":{\"arith\":" in json, json)
    assertEquals(tree, deserializeTree(json))
    assertEquals(16, tree.children[0].int("fontSize"))
    assertEquals(7, tree.children[1].int("fontSize", 7))
    assertEquals(6.0, tree.children[2].dbl("height"))
    // A mocked binding is computed on the wire (a mock is a decimal).
    val mocked = serializeTree(d.toWidgetTree(mocks = mapOf("size" to "41")))
    assertTrue("\"fontSize\":{\"i\":42,\"arith\":" in mocked, mocked)
    // Editor -> server lifts it back unchanged.
    assertEquals(a("*", gap, lit(2)), tree.children[0].toDocNode().props["fontSize"])
    assertEquals(PropValue.IntDp(a("-", gap, lit(2)), DP_CTOR_FORM), tree.children[2].toDocNode().props["height"])
  }

  @Test fun anOlderEditorGetsTheComputedLiteralOrNothing() {
    val d = doc(
      Contract(),
      text(2, "fontSize" to a("*", gap, lit(2))),
      text(3, "fontSize" to a("+", b("size"), lit(1))),
      DocNode.Widget(Handle(4), "Spacer", props = mapOf("height" to PropValue.IntDp(a("-", lit(2), gap)))),
      text(5, "fontSize" to PropValue.Conditional("on", whenTrue = a("*", gap, lit(2)), whenFalse = lit(3))),
      text(6, "fontSize" to PropValue.Conditional("on", whenTrue = a("*", b("size"), lit(2)), whenFalse = lit(3))),
    ).withoutArithmetic()
    fun prop(i: Int) = ((d.root as DocNode.Widget).children[i] as DocNode.Widget).props
    assertEquals(lit(16), prop(0)["fontSize"])
    assertNull(prop(1)["fontSize"])
    assertEquals(lit(0.0), prop(2)["height"]) // a Dp is never negative
    assertEquals(PropValue.Conditional("on", whenTrue = lit(16), whenFalse = lit(3)), prop(3)["fontSize"])
    assertNull(prop(4)["fontSize"])
  }

  @Test fun theExporterWritesEveryFormAndImportsWhatItNeeds() {
    val d = doc(
      Contract(),
      text(2, "fontSize" to a("/", a("*", b("size"), lit(2)), lit(5)), "colorArgb" to a("+", gap, lit(1))),
      DocNode.Widget(Handle(3), "Spacer", props = mapOf("height" to PropValue.IntDp(a("+", b("gap"), lit(4))))),
      DocNode.Widget(Handle(4), "Spacer", props = mapOf("width" to PropValue.IntDp(b("gap"), DP_CTOR_FORM))),
    )
    val out = exportKotlin(d.toWidgetTree(), "Home")
    assertTrue("fontSize = b.size * 2 / 5," in out, out)
    assertTrue("colorArgb = C.Gap + 1," in out, out)
    assertTrue("height = (b.gap + 4).dp," in out, out)
    assertTrue("width = Dp(b.gap.toDouble())," in out, out)
    assertTrue("import app.components.C\n" in out, out)
    assertTrue("import dev.keliver.ui.dp\n" in out, out)
    assertTrue("import dev.keliver.ui.Dp\n" in out, out)
    // The derived contract types every operand Int.
    assertTrue("val size: Int" in out, out)
    assertTrue("val gap: Int" in out, out)
  }

  @Test fun theDerivedContractTypesOperandsInt() {
    val tree = doc(Contract(), text(2, "fontSize" to a("+", b("size"), lit(1))), DocNode.Widget(Handle(3), "Spacer", props = mapOf("height" to PropValue.IntDp(b("gap"))))).toWidgetTree()
    val c = collectContract(tree)
    assertEquals(PropKind.Int, c.fields["size"])
    assertEquals(PropKind.Int, c.fields["gap"])
  }

  // ── What the document refuses ─────────────────────────────────────────

  @Test fun whereAnArithmeticValueSits() {
    val d = doc(Contract(), text(2), spacer(3))
    val sum = a("+", b("size"), lit(1))
    accepted(d, set(2, "fontSize", sum))
    accepted(d, set(2, "colorArgb", a("-", gap, lit(1))))
    accepted(d, set(2, "fontSize", PropValue.Conditional("on", whenTrue = sum, whenFalse = lit(12))))
    assertTrue("Int prop" in refused(d, set(2, "text", sum)))
    assertTrue("modifier" in refused(d, DocOp.SetModifier(Handle(2), "Padding.allDp", sum)))
    accepted(d, set(3, "height", PropValue.IntDp(sum)))
    accepted(d, set(3, "width", PropValue.IntDp(b("gap"), DP_CTOR_FORM)))
    accepted(d, set(3, "width", PropValue.IntDp(gap)))
    assertTrue("Dp prop" in refused(d, set(2, "fontSize", PropValue.IntDp(sum))))
    assertTrue("Int prop" in refused(d, set(3, "height", sum)))
    assertTrue("spelling" in refused(d, set(3, "height", PropValue.IntDp(sum, "dp()"))))
    // Never a template part, a list element or an equality operand.
    assertNotNull(d.tryApply(set(2, "text", PropValue.Template(listOf(lit("n="), sum)))).error)
    assertNotNull(d.tryApply(set(2, "fontSize", PropValue.Conditional("k", test = "eq", operand = sum, whenTrue = lit(1), whenFalse = lit(2)))).error)
    // Stored canonical: an expression of literals is the literal.
    val folded = accepted(d, set(2, "fontSize", a("*", lit(4), lit(3))))
    assertEquals(lit(12), (folded.find(Handle(2)) as DocNode.Widget).props["fontSize"])
    val dp = accepted(d, set(3, "height", PropValue.IntDp(a("-", lit(2), lit(5)))))
    assertEquals(lit(0.0), (dp.find(Handle(3)) as DocNode.Widget).props["height"]) // clamped as a Dp
  }

  @Test fun theShapeOfAnExpression() {
    val d = doc(Contract(), text(2))
    assertTrue("non-zero Int literal" in refused(d, set(2, "fontSize", a("/", b("size"), lit(0)))))
    assertTrue("non-zero Int literal" in refused(d, set(2, "fontSize", a("%", b("size"), gap))))
    assertTrue("non-zero Int literal" in refused(d, set(2, "fontSize", a("/", b("size"), b("n")))))
    assertTrue("non-zero Int literal" in refused(d, set(2, "fontSize", a("/", b("size"), a("+", lit(1), lit(1))))))
    accepted(d, set(2, "fontSize", a("/", b("size"), lit(-1))))
    assertTrue("Long" in refused(d, set(2, "fontSize", a("+", b("size"), lit(Int.MIN_VALUE)))))
    assertTrue("operator" in refused(d, set(2, "fontSize", a("^", b("size"), lit(2)))))
    assertTrue("an Int" in refused(d, set(2, "fontSize", a("+", b("size"), PropValue.Lit("d", d = 1.5)))))
    assertTrue("C.Name" in refused(d, set(2, "fontSize", a("+", b("size"), PropValue.Token("C.Name", lit("n"))))))
    assertTrue("field name" in refused(d, set(2, "fontSize", a("+", b("1x"), lit(1)))))
    assertNotNull(d.tryApply(set(2, "fontSize", a("+", b("size"), PropValue.Action("go")))).error)
  }

  @Test fun aDeclaredOperandIsANonNullInt() {
    val d = doc(Contract(fields = mapOf("size" to "Int", "q" to "kotlin.Int", "name" to "String", "n" to "Int?", "l" to "Long")), text(2))
    accepted(d, set(2, "fontSize", a("+", b("size"), b("q"))))
    assertTrue("declared String" in refused(d, set(2, "fontSize", a("+", b("name"), lit(1)))))
    assertTrue("non-null" in refused(d, set(2, "fontSize", a("+", b("n"), lit(1)))))
    assertTrue("declared Long" in refused(d, set(2, "fontSize", a("+", b("l"), lit(1)))))
  }

  @Test fun aDerivedOperandIsReadAsAnIntEverywhere() {
    val withText = doc(Contract(), text(2, "text" to b("size")), text(3))
    assertTrue("String prop" in refused(withText, set(3, "fontSize", a("+", b("size"), lit(1)))))
    val tested = doc(Contract(), DocNode.Widget(Handle(2), "Condition", props = mapOf("field" to lit("size"))), text(3))
    assertNotNull(tested.tryApply(set(3, "fontSize", a("+", b("size"), lit(1)))).error)
    val empty = doc(Contract(), DocNode.Widget(Handle(2), "Condition", props = mapOf("field" to lit("size"), "test" to lit("empty"))), text(3))
    assertTrue("String" in refused(empty, set(3, "fontSize", a("+", b("size"), lit(1)))))
    val compared = doc(Contract(), text(2, "colorArgb" to PropValue.Conditional("size", test = "eq", operand = lit("big"), whenTrue = lit(1), whenFalse = lit(2))), text(3))
    assertTrue("String" in refused(compared, set(3, "fontSize", a("+", b("size"), lit(1)))))
    val repeat = doc(Contract(), DocNode.Widget(Handle(2), "Repeat", props = mapOf("items" to lit("size"), "item" to lit("it"))), text(3))
    assertNotNull(repeat.tryApply(set(3, "fontSize", a("+", b("size"), lit(1)))).error)
    // An Int read elsewhere is fine — a binding on an Int prop, an Int comparison, a template.
    val fine = doc(Contract(), text(2, "colorArgb" to b("size")), text(4, "text" to PropValue.Template(listOf(lit("n="), b("size")))), text(3))
    accepted(fine, set(3, "fontSize", a("+", b("size"), lit(1))))
  }

  @Test fun aConditionOnAnOperandIsRefusedTheOtherWayRound() {
    val d = doc(Contract(), text(2, "fontSize" to a("+", b("size"), lit(1))), text(3))
    assertNotNull(d.tryApply(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Condition", props = mapOf("field" to lit("size"))))).error)
    assertNotNull(d.tryApply(set(3, "colorArgb", PropValue.Conditional("size", test = "empty", whenTrue = lit(1), whenFalse = lit(2)))).error)
    assertNotNull(d.tryApply(set(3, "colorArgb", PropValue.Conditional("size", test = "eq", operand = lit("x"), whenTrue = lit(1), whenFalse = lit(2)))).error)
    accepted(d, set(3, "colorArgb", PropValue.Conditional("size", test = "eq", operand = lit(3), whenTrue = lit(1), whenFalse = lit(2))))
  }

  @Test fun anOperandWhoseTypeIsNotReadableNeedsEvidence() {
    val elsewhere = "the Bindings interface extends one declared in another file"
    val open = Contract(fields = mapOf("x" to "<unknown>"), unlistedFieldsUnknown = elsewhere)
    val d = doc(open, text(2), text(3))
    assertTrue("make this change in code" in refused(d, set(2, "fontSize", a("+", b("x"), lit(1)))))
    assertTrue("not readable" in refused(d, set(2, "fontSize", a("+", b("fresh"), lit(1)))))
    // Another operand reading it vouches (the file compiled it, or an op placed it with evidence).
    val vouched = accepted(doc(open, text(2, "colorArgb" to a("-", b("fresh"), lit(1))), text(3)), set(3, "fontSize", a("+", b("fresh"), lit(1))))
    accepted(vouched, set(2, "fontSize", a("*", b("fresh"), lit(2))))
    // The value there before vouches for an edit of another operand.
    val before = doc(open, text(2, "fontSize" to a("+", b("x"), lit(1))))
    accepted(before, set(2, "fontSize", a("+", b("x"), lit(2))))
    // An inserted subtree never vouches for itself.
    val pair = DocNode.Widget(Handle(0), "Column", children = listOf(
      DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to lit("a"), "fontSize" to a("+", b("x"), lit(1)))),
      DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to lit("b"), "fontSize" to a("+", b("x"), lit(2)))),
    ))
    assertNotNull(d.tryApply(DocOp.InsertNode(Handle(1), null, pair)).error)
  }

  // Review MINOR 1: a plain binding is not type-checked where the document cannot read the field's
  // type (`fontSize = b.label` is accepted, the compile types it), so it never vouches for an operand.
  @Test fun aPlainBindingNeverVouchesForAnOperand() {
    val open = Contract(fields = emptyMap(), unlistedFieldsUnknown = "the Bindings interface extends one declared in another file")
    val d = doc(open, text(2), text(3))
    val bound = accepted(d, set(2, "fontSize", b("label")))
    assertTrue("make this change in code" in refused(bound, set(2, "fontSize", a("*", b("label"), lit(2)))))
    assertTrue("make this change in code" in refused(bound, set(3, "fontSize", a("*", b("label"), lit(2)))))
    // Not even one the file holds: the document cannot tell a binding the file compiled from one an op placed.
    val fromFile = doc(open, text(2, "colorArgb" to b("label")), text(3))
    assertTrue("make this change in code" in refused(fromFile, set(3, "fontSize", a("*", b("label"), lit(2)))))
    // In a Repeat too.
    fun repeat(vararg kids: DocNode) = DocNode.Widget(Handle(5), "Repeat", props = mapOf("items" to lit("rows"), "item" to lit("row")), children = kids.toList())
    val rows = doc(Contract(), repeat(text(6, "colorArgb" to b("row.count")), text(7)))
    assertTrue("item field" in refused(rows, set(7, "fontSize", a("*", b("row.count"), lit(2)))))
  }

  // Review NIT: as ingest reads it, an Int used as a Dp reads only a field KNOWN to be an Int (`b.n.dp` compiles for a
  // Double too), whatever else the screen does with it.
  @Test fun anIntUsedAsADpNeverReadsAFieldWhoseTypeIsNotReadable() {
    val open = Contract(fields = mapOf("n" to "<unknown>", "k" to "Int"), unlistedFieldsUnknown = "the Bindings interface extends one declared in another file")
    val d = doc(open, text(2, "fontSize" to a("*", b("n"), lit(2))), spacer(3))
    val why = refused(d, set(3, "height", PropValue.IntDp(b("n"))))
    assertTrue("declared Int" in why, why)
    assertTrue("declared Int" in refused(d, set(3, "height", PropValue.IntDp(a("+", b("n"), lit(1)), DP_CTOR_FORM))))
    assertTrue("declared Int" in refused(d, DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Spacer", props = mapOf("width" to PropValue.IntDp(b("n")))))))
    // A field the contract declares Int, or a new one (typed Int by the derived contract), is fine.
    accepted(d, set(3, "height", PropValue.IntDp(b("k"))))
    accepted(doc(Contract(), spacer(3)), set(3, "height", PropValue.IntDp(b("fresh"))))
    // The same expression as an Int (evidenced) is still grammar.
    accepted(d, set(2, "colorArgb", a("+", b("n"), lit(1))))
  }

  @Test fun anItemOperandStaysInsideItsRepeat() {
    fun repeat(vararg kids: DocNode) = DocNode.Widget(Handle(5), "Repeat", props = mapOf("items" to lit("rows"), "item" to lit("row")), children = kids.toList())
    val d = doc(Contract(), repeat(text(6, "colorArgb" to a("+", b("row.count"), lit(1))), text(7)), text(3))
    accepted(d, set(7, "fontSize", a("*", b("row.count"), lit(2))))
    assertTrue("outside a Repeat" in refused(d, set(3, "fontSize", a("*", b("row.count"), lit(2)))))
    // Nothing vouches for an item field's type: made in code.
    assertTrue("item field" in refused(d, set(7, "fontSize", a("*", b("row.other"), lit(2)))))
    // A move out of its Repeat is refused too.
    val placed = accepted(d, set(7, "fontSize", a("*", b("row.count"), lit(2))))
    assertNotNull(placed.tryApply(DocOp.MoveNode(Handle(7), Handle(1), null)).error)
  }

  @Test fun anInsertedNodeIsCheckedAndUndoRestoresTheExpression() {
    val d = doc(Contract(), text(2))
    assertNotNull(d.tryApply(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to a("+", b("n"), lit(1)))))).error)
    val r = d.apply(set(2, "fontSize", a("+", b("n"), lit(1))))
    val back = r.doc.apply(r.inverse!!).doc
    assertNull((back.find(Handle(2)) as DocNode.Widget).props["fontSize"])
  }

  @Test fun previewKeysIncludeOperands() {
    val tree: WidgetNode = doc(Contract(), text(2, "fontSize" to a("+", b("size"), lit(1)))).toWidgetTree()
    assertTrue("size" in dev.keliver.portal.collectPreviewBindingKeys(tree))
  }
}
