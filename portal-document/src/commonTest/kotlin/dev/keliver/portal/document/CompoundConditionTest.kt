package dev.keliver.portal.document

import dev.keliver.portal.Bind
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.Cond
import dev.keliver.portal.CondJoin
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.Expansion
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.collectPreviewBindingKeys
import dev.keliver.portal.condOf
import dev.keliver.portal.condOfTerms
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.expandForPreview
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.resolveItemRow
import dev.keliver.portal.serializeTree
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K6: equality terms (`x == <literal or token>`), compound conditions (terms
 * joined by `&&` only or `||` only) — the core model (source, deciding,
 * De Morgan, partial decisions), both wires (K3 JSON decodes unchanged), the
 * exporter, the document's refusals and the view an older editor is served.
 */
class CompoundConditionTest {
  private val accent = Token("C.Accent", "XY", "app.components.C")
  private val accentDoc = PropValue.Token("C.Accent", lit("XY"), "app.components.C")

  private fun doc(vararg children: DocNode, contract: Contract = Contract()): UiDocument =
    UiDocument("t", DocNode.Widget(Handle(1), "Column", children = children.toList()), contract, 0, 20)

  private fun text(h: Long, v: PropValue = lit("x")) = DocNode.Widget(Handle(h), "StyledText", props = mapOf("text" to v))

  // ── core ──────────────────────────────────────────────────────────────

  @Test fun eachFormPrintsItsOneSpelling() {
    fun ref(f: String) = "b.$f"
    assertEquals("b.kind == \"card\"", Cond("kind", CondTest.Eq, operand = "card").kotlin(::ref))
    assertEquals("b.count != -3", Cond("count", CondTest.Eq, negate = true, operand = -3).kotlin(::ref))
    assertEquals("b.on == false", Cond("on", CondTest.Eq, operand = false).kotlin(::ref))
    assertEquals("b.code == C.Accent", Cond("code", CondTest.Eq, operand = accent).kotlin(::ref))
    assertEquals("b.q == \"a\\\"\\\$b\"", Cond("q", CondTest.Eq, operand = "a\"\$b").kotlin(::ref))
    val and = condOfTerms(listOf(Cond("enabled"), Cond("loading", negate = true)))
    assertEquals("b.enabled && !b.loading", and.kotlin(::ref))
    val or = condOfTerms(listOf(Cond("a"), Cond("s", CondTest.Empty, negate = true), Cond("k", CondTest.Eq, operand = 2)), CondJoin.Or)
    assertEquals("b.a || b.s.isNotEmpty() || b.k == 2", or.kotlin(::ref))
    assertEquals(3, or.terms.size)
    assertTrue(or.isExtended)
    assertFalse(Cond("a", CondTest.Empty).isExtended)
  }

  @Test fun anEqualityComparesByTheOperandsKind() {
    val s = Cond("k", CondTest.Eq, operand = "XY")
    assertTrue(s.holds("XY"))
    assertFalse(s.holds("xy"))
    assertFalse(s.holds(null, present = false)) // an unknown String equals no operand
    assertTrue(s.copy(negate = true).holds(null, present = false))
    assertTrue(Cond("k", CondTest.Eq, operand = accent).holds("XY")) // a token compares by its value
    assertTrue(s.holds(Token("C.X", "XY")))
    val i = Cond("n", CondTest.Eq, operand = 3)
    assertTrue(i.holds(3))
    assertTrue(i.holds("3")) // a mock is a String: read as the field's kind
    assertFalse(i.holds("3.0"))
    assertFalse(i.holds("three"))
    assertFalse(i.holds(null, present = false))
    val b = Cond("on", CondTest.Eq, operand = false)
    assertTrue(b.holds(false))
    assertTrue(b.holds("false"))
    assertFalse(b.holds(null, present = false)) // an unknown Boolean reads true, as for `if (b.on)`
    assertTrue(Cond("on", CondTest.Eq, operand = true).holds(null, present = false))
    assertFalse(b.holds("maybe"))
  }

  @Test fun aCompoundConditionReadsEachTermsField() {
    val and = condOfTerms(listOf(Cond("enabled"), Cond("loading", negate = true)))
    assertTrue(and.holdsIn(mapOf("enabled" to true, "loading" to false)))
    assertFalse(and.holdsIn(mapOf("enabled" to true, "loading" to true)))
    assertFalse(and.holdsIn(mapOf("enabled" to "false")))
    assertFalse(and.holdsIn(emptyMap())) // unknown Booleans read true: `true && !true`
    val or = condOfTerms(listOf(Cond("a", CondTest.Eq, operand = 1), Cond("b", CondTest.Blank)), CondJoin.Or)
    assertTrue(or.holdsIn(mapOf("a" to "1")))
    assertTrue(or.holdsIn(mapOf("a" to 2, "b" to " ")))
    assertFalse(or.holdsIn(mapOf("a" to 2, "b" to "x")))
  }

  @Test fun deMorganNegatesEveryTermAndFlipsTheJoin() {
    val and = condOfTerms(listOf(Cond("enabled"), Cond("loading", negate = true)))
    val not = and.negated()
    assertEquals(CondJoin.Or, not.join)
    assertEquals("!enabled || loading", not.kotlin { it })
    for (e in listOf(true, false)) for (l in listOf(true, false)) {
      val v = mapOf("enabled" to e, "loading" to l)
      assertEquals(!and.holdsIn(v), not.holdsIn(v), "enabled=$e loading=$l")
    }
    assertEquals(Cond("k", CondTest.Eq, negate = true, operand = "x"), Cond("k", CondTest.Eq, operand = "x").negated())
    assertEquals(and, not.negated())
  }

  @Test fun reducingDecidesWhatIsKnownAndKeepsTheRest() {
    val and = condOfTerms(listOf(Cond("enabled"), Cond("loading", negate = true)))
    assertEquals(false, and.reduce { t -> if (t.field == "enabled") false else t })
    assertEquals(Cond("busy", negate = true), and.reduce { t -> if (t.field == "enabled") true else t.copy(field = "busy") })
    assertEquals(true, and.reduce { true })
    val or = and.negated()
    assertEquals(true, or.reduce { t -> if (t.field == "enabled") true else t })
    assertEquals(condOfTerms(listOf(Cond("ok", negate = true), Cond("busy")), CondJoin.Or), or.reduce { t -> t.copy(field = if (t.field == "enabled") "ok" else "busy") })
  }

  // ── wires ─────────────────────────────────────────────────────────────

  private val compound = PropValue.Conditional(
    "kind", test = "eq", whenTrue = accentDoc, whenFalse = lit("-"), operand = lit("card"),
    more = listOf(CondTerm("count", "eq", negate = true, operand = lit(0)), CondTerm("open")), join = "or",
  )

  @Test fun aK3ConditionalsJsonDecodesToTheIdenticalValueAndNewFieldsAreLeftOutWhenDefault() {
    val k3 = """{"kind":"dev.keliver.portal.document.PropValue.Conditional","field":"x","test":"blank","negate":true,"whenTrue":{"kind":"dev.keliver.portal.document.PropValue.Lit","tag":"s","s":"A"}}"""
    val v = DocJson.decodeFromString<PropValue>(k3)
    assertEquals(PropValue.Conditional("x", "blank", true, lit("A"), null), v)
    assertEquals(k3, DocJson.encodeToString(v))
    val json = DocJson.encodeToString<PropValue>(compound)
    assertEquals(compound, DocJson.decodeFromString<PropValue>(json))
    assertTrue("\"more\":[{\"field\":\"count\",\"test\":\"eq\",\"negate\":true,\"operand\":" in json, json)
    assertTrue("{\"field\":\"open\"}]" in json && "\"join\":\"or\"" in json, json)
  }

  @Test fun theTreeCarriesEveryTermWhereAK3ReaderDoesNotLook() {
    val d = doc(text(2, compound))
    val tree = d.toWidgetTree()
    val v = tree.children.single().props["text"] as Conditional
    assertEquals(
      condOfTerms(listOf(Cond("kind", CondTest.Eq, operand = "card"), Cond("count", CondTest.Eq, true, 0), Cond("open")), CondJoin.Or),
      v.cond,
    )
    val json = serializeTree(tree)
    assertTrue("\"condition\":{\"field\":\"kind\",\"test\":\"eq\",\"operand\":{\"s\":\"card\"},\"more\":[{\"field\":\"count\",\"test\":\"eq\",\"negate\":true,\"operand\":{\"i\":0}},{\"field\":\"open\"}],\"join\":\"or\"}" in json, json)
    assertEquals(tree, deserializeTree(json))
    assertEquals(compound, tree.children.single().toDocNode().props["text"])
    // A Condition node carries its terms as suffixed props; a K3 reader sees the first term's.
    val node = DocNode.Widget(Handle(3), "Condition", conditionNodeProps(condOfTerms(listOf(Cond("kind", CondTest.Eq, operand = accent), Cond("open", negate = true)))))
    assertEquals(setOf("field", "test", "operand", "field.1", "negate.1"), node.props.keys)
    assertEquals(
      condOfTerms(listOf(Cond("kind", CondTest.Eq, operand = accent), Cond("open", negate = true))),
      condOf(doc(node).toWidgetTree().children.single()),
    )
    assertEquals(node.condition(), condOf(doc(node).toWidgetTree().children.single()))
  }

  @Test fun anOlderEditorIsServedTheTrueBranchOfAnExtendedConditionalOnly() {
    val k3 = PropValue.Conditional("open", whenTrue = lit("a"), whenFalse = lit("b"))
    val d = doc(
      DocNode.Widget(Handle(2), "Button", props = mapOf("text" to compound, "containerArgb" to k3.copy(whenTrue = lit(1), whenFalse = lit(2)))),
      DocNode.Widget(Handle(3), "Condition", conditionNodeProps(condOfTerms(listOf(Cond("a"), Cond("b"))))),
    )
    val old = d.withoutExtendedConditions()
    val button = old.find(Handle(2)) as DocNode.Widget
    assertEquals(accentDoc, button.props["text"])
    assertEquals(d.find(Handle(2)).let { (it as DocNode.Widget).props["containerArgb"] }, button.props["containerArgb"])
    assertEquals(d.find(Handle(3)), old.find(Handle(3))) // a Condition node reaches it unchanged
    assertTrue(compound.isExtended())
    assertFalse(k3.isExtended())
  }

  // ── exporter and contract ─────────────────────────────────────────────

  @Test fun theExporterWritesEveryFormTypesEachFieldAndImportsAComparedToken() {
    val cond = DocNode.Widget(
      Handle(3), "Condition",
      conditionNodeProps(condOfTerms(listOf(Cond("ready"), Cond("tier", CondTest.Eq, operand = 2)))),
      children = listOf(text(4)),
    )
    val d = doc(text(2, compound), cond)
    val src = exportKotlin(d.toWidgetTree(), functionName = "Home")
    assertTrue("text = if (b.kind == \"card\" || b.count != 0 || b.open) C.Accent else \"-\"," in src, src)
    assertTrue("  if (b.ready && b.tier == 2) {\n" in src, src)
    assertTrue("  val kind: String\n" in src && "  val count: Int\n" in src && "  val open: Boolean\n" in src, src)
    assertTrue("  val ready: Boolean\n" in src && "  val tier: Int\n" in src, src)
    val tokenOp = doc(
      DocNode.Widget(Handle(2), "Condition", conditionNodeProps(Cond("code", CondTest.Eq, operand = accent)), children = listOf(text(3))),
      DocNode.Widget(Handle(4), "Row", props = mapOf("margin.top" to PropValue.Conditional("flag", whenTrue = lit(8.0), whenFalse = lit(0.0), test = "eq", operand = PropValue.Token("K.On", lit(true), "app.k.K")))),
    )
    val out = exportKotlin(tokenOp.toWidgetTree(), functionName = "Home")
    assertTrue("  if (b.code == C.Accent) {\n" in out, out)
    assertTrue("import app.components.C\n" in out && "import app.k.K\n" in out, out)
    assertTrue("  val code: String\n" in out && "  val flag: Boolean\n" in out, out)
    // Item fields an item-scoped compound condition reads are typed in the item interface.
    val repeat = DocNode.Widget(
      Handle(2), "Repeat", mapOf("items" to lit("rows"), "item" to lit("row")),
      children = listOf(text(3, PropValue.Conditional("row.kind", test = "eq", operand = lit(4), whenTrue = lit("a"), whenFalse = lit("b"), more = listOf(CondTerm("busy", negate = true))))),
    )
    val items = exportKotlin(doc(repeat).toWidgetTree(), functionName = "Home")
    assertTrue("interface Row {\n  val kind: Int\n}" in items, items)
    assertTrue("  val busy: Boolean\n" in items, items)
    assertTrue("row.kind == 4 && !b.busy" in items, items)
    val keys = collectPreviewBindingKeys(doc(repeat).toWidgetTree())
    assertTrue("rows.row.kind" in keys && "busy" in keys, keys.toString())
  }

  // ── what an op may not create ─────────────────────────────────────────

  @Test fun anExtendedConditionThatCouldNotBeWrittenIsRefused() {
    val d = doc(
      text(2),
      DocNode.Widget(Handle(3), "Button", props = mapOf("text" to lit("Go"))),
      text(4, PropValue.Bind("title")),
      DocNode.Widget(Handle(5), "Repeat", mapOf("items" to lit("items"), "item" to lit("item")), children = listOf(text(6))),
      contract = Contract(fields = mapOf("count" to "Int")),
    )
    fun refused(op: DocOp, why: String) = assertNotNull(d.tryApply(op).error, why).also { assertTrue(it.isNotEmpty()) }
    fun accepted(op: DocOp) = assertNull(d.tryApply(op).error, op.toString())
    fun eq(field: String, op: PropValue?, vararg more: CondTerm, join: String? = null) =
      PropValue.Conditional(field, test = "eq", operand = op, whenTrue = lit("a"), whenFalse = lit("b"), more = more.toList(), join = join)
    fun set(v: PropValue) = DocOp.SetProp(Handle(2), "text", v)

    accepted(set(eq("kind", lit("card"))))
    accepted(set(eq("kind", accentDoc)))
    accepted(set(eq("count", lit(-3))))
    accepted(set(eq("on", lit(true), CondTerm("ready"), CondTerm("tier", "eq", operand = lit(2)), join = "or")))
    refused(set(eq("kind", null)), "an equality without an operand")
    refused(set(eq("kind", PropValue.Lit("d", d = 1.5))), "a Double operand")
    refused(set(eq("kind", PropValue.Token("C.W", PropValue.Lit("d", d = 1.5)))), "a Double token")
    refused(set(eq("kind", PropValue.Bind("other"))), "field against field")
    refused(set(eq("kind", lit(Int.MIN_VALUE))), "-2147483648 is a Long in source")
    refused(set(PropValue.Conditional("x", operand = lit(1), whenTrue = lit("a"), whenFalse = lit("b"))), "an operand on a Boolean test")
    refused(set(eq("kind", lit("a"), CondTerm("x", "null"))), "a null term in a screen")
    refused(set(eq("kind", lit("a"), CondTerm("1x"))), "a bad field in a further term")
    refused(set(eq("kind", lit("a"), CondTerm("x"), join = "xor")), "an unknown join")
    // A single term is joined by nothing: a stray join is dropped, not stored.
    val single = d.apply(set(PropValue.Conditional("open", whenTrue = lit("a"), whenFalse = lit("b"), join = "or"))).doc
    assertNull(((single.find(Handle(2)) as DocNode.Widget).props["text"] as PropValue.Conditional).join)
    // Types: the declared Int is not compared with a String; one field has one type.
    refused(set(eq("count", lit("3"))), "count is declared Int")
    accepted(set(eq("count", lit(3))))
    refused(set(eq("title", lit(3))), "title is bound to a text prop")
    accepted(set(eq("title", lit("x"))))
    refused(set(eq("items", lit("x"))), "items is a Repeat's List")
    refused(set(eq("kind", lit("a"), CondTerm("kind", "eq", operand = lit(1)))), "kind compared with String and Int")
    refused(set(eq("kind", lit("a"), CondTerm("kind"))), "kind compared with a String and tested as a Boolean")
    val compared = d.apply(DocOp.SetProp(Handle(3), "text", eq("mode", lit(2)))).doc
    assertNotNull(compared.tryApply(set(PropValue.Conditional("mode", whenTrue = lit("a"), whenFalse = lit("b")))).error, "mode is compared with an Int elsewhere")
    assertNotNull(compared.tryApply(set(eq("mode", lit("2")))).error, "mode is an Int elsewhere")
    assertNotNull(compared.tryApply(set(PropValue.Conditional("mode", test = "empty", whenTrue = lit("a"), whenFalse = lit("b")))).error)
    assertNull(compared.tryApply(set(eq("mode", lit(3)))).error)
    // A Boolean equality is a Boolean test.
    val flagged = d.apply(DocOp.SetProp(Handle(3), "text", eq("flag", lit(false)))).doc
    assertNull(flagged.tryApply(set(PropValue.Conditional("flag", whenTrue = lit("a"), whenFalse = lit("b")))).error)
    assertNotNull(flagged.tryApply(DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(lit("on: "), PropValue.Bind("flag"))))).error)
  }

  @Test fun aConditionNodesShapeIsPlacedWholeAndOnlyItsValuesAreEdited() {
    val shaped = conditionNodeProps(condOfTerms(listOf(Cond("kind", CondTest.Eq, operand = "card"), Cond("open", negate = true)), CondJoin.Or))
    val d = doc(DocNode.Widget(Handle(2), "Condition", shaped, children = listOf(text(3))), DocNode.Widget(Handle(4), "Condition", mapOf("field" to lit("x"))))
    fun refused(op: DocOp) = assertNotNull(d.tryApply(op).error, op.toString())
    fun accepted(op: DocOp) = assertNull(d.tryApply(op).error, op.toString())
    // Inserted whole: accepted when it is a condition source can spell.
    accepted(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Condition", shaped)))
    refused(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Condition", shaped - "operand")))
    refused(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Condition", shaped + ("field.3" to lit("z")))))
    refused(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Condition", mapOf("field" to lit("a"), "join" to lit("or")))))
    refused(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Condition", shaped + ("operand.1" to lit(1)))))
    refused(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Condition", shaped + ("field.1" to lit("item.x")))))
    refused(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Condition", shaped + ("negate.1" to lit("yes")))))
    // Values change; the shape does not.
    accepted(DocOp.SetProp(Handle(2), "operand", lit("cash")))
    accepted(DocOp.SetProp(Handle(2), "field.1", lit("closed")))
    accepted(DocOp.SetProp(Handle(2), "negate.1", lit(false)))
    accepted(DocOp.SetProp(Handle(2), "negate", lit(true)))
    accepted(DocOp.SetProp(Handle(2), "field", lit("type")))
    refused(DocOp.SetProp(Handle(2), "operand", PropValue.Lit("d", d = 1.5)))
    refused(DocOp.SetProp(Handle(2), "test", lit("empty")))
    refused(DocOp.RemoveProp(Handle(2), "test"))
    refused(DocOp.RemoveProp(Handle(2), "operand"))
    refused(DocOp.SetProp(Handle(2), "test.1", lit("blank")))
    refused(DocOp.SetProp(Handle(2), "join", lit("and")))
    accepted(DocOp.RemoveProp(Handle(2), "join")) // K6 review: the join changes like a value's (&& when removed)
    refused(DocOp.RemoveProp(Handle(2), "field.1"))
    refused(DocOp.SetProp(Handle(2), "field.2", lit("z")))
    refused(DocOp.SetProp(Handle(4), "test", lit("eq")))
    refused(DocOp.SetProp(Handle(4), "operand", lit(1)))
    // K3 edits of a K3 Condition are unchanged.
    accepted(DocOp.SetProp(Handle(4), "test", lit("blank")))
    // Undo of an accepted value edit restores it.
    val r = d.apply(DocOp.SetProp(Handle(2), "operand", lit("cash")))
    assertEquals(d.root, r.doc.apply(assertNotNull(r.inverse)).doc.root)
  }

  // ── preview ───────────────────────────────────────────────────────────

  @Test fun previewExpansionDecidesEachTermAgainstTheInstanceArguments() {
    // `val active = enabled && !loading` inlined by ingest: `if (active)` is `enabled && !loading`.
    val active = condOfTerms(listOf(Cond("enabled"), Cond("loading", negate = true)))
    val body = WidgetNode(
      "Column",
      children = listOf(
        WidgetNode("StyledText", mapOf("text" to Conditional(Cond("loading"), "Loading…", Bind("text")), "colorArgb" to Conditional(active, 1, 2))),
        WidgetNode("Condition", dev.keliver.portal.conditionProps(active.negated()) { it }, children = listOf(WidgetNode("StyledText", mapOf("text" to "off")))),
        WidgetNode("Condition", dev.keliver.portal.conditionProps(Cond("text", CondTest.Eq, operand = "XY")) { it }, children = listOf(WidgetNode("StyledText", mapOf("text" to "xy")))),
      ),
    )
    val spec = ComponentSpec(
      "Btn",
      props = listOf(PropSpec("text", PropKind.Text, "Text"), PropSpec("enabled", PropKind.Bool, "Enabled"), PropSpec("loading", PropKind.Bool, "Loading")),
      events = emptyList(),
      paramTypes = mapOf("text" to "String", "enabled" to "Boolean", "loading" to "Boolean"),
      body = body,
    )
    val registry = MapComponentRegistry(listOf(spec))
    fun expand(vararg args: Pair<String, Any?>) = (expandForPreview(WidgetNode("Btn", mapOf(*args)), registry) as Expansion.Transparent).tree
    val idle = expand("text" to "Go", "enabled" to true, "loading" to false)
    assertEquals(listOf("Go"), idle.children.map { it.props["text"] })
    assertEquals(1, idle.children.single().props["colorArgb"])
    val busy = expand("text" to "XY", "enabled" to true, "loading" to true)
    assertEquals(listOf("Loading…", "off", "xy"), busy.children.map { it.props["text"] })
    assertEquals(2, busy.children.first().props["colorArgb"])
    // A binding argument re-points its term; a known one decides it.
    val bound = expand("text" to "Go", "enabled" to Bind("ok"), "loading" to false)
    assertEquals(Conditional(Cond("ok"), 1, 2), bound.children.first().props["colorArgb"])
    val both = expand("text" to Bind("t"), "enabled" to Bind("ok"), "loading" to Bind("busy"))
    assertEquals(condOfTerms(listOf(Cond("ok"), Cond("busy", negate = true))), (both.children.first().props["colorArgb"] as Conditional).cond)
    val notActive = both.children.first { it.type == "Condition" && condOf(it)?.join == CondJoin.Or }
    assertEquals(condOfTerms(listOf(Cond("ok", negate = true), Cond("busy")), CondJoin.Or), condOf(notActive))
    assertEquals(Cond("t", CondTest.Eq, operand = "XY"), condOf(both.children.last()))
  }

  @Test fun anItemScopedTermIsDecidedPerRowAndTheRestKept() {
    val node = WidgetNode("StyledText", mapOf("text" to Conditional(condOfTerms(listOf(Cond("row.kind", CondTest.Eq, operand = "a"), Cond("open"))), "yes", "no")))
    val mocks = mapOf("rows.row.kind" to "a|b")
    val first = resolveItemRow(node, "row", "rows", 0, mocks::get)
    assertEquals(Conditional(Cond("open"), "yes", "no"), first.props["text"])
    assertEquals("no", resolveItemRow(node, "row", "rows", 1, mocks::get).props["text"])
  }
}
