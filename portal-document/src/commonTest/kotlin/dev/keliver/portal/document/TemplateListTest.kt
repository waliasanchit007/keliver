package dev.keliver.portal.document

import dev.keliver.portal.Bind
import dev.keliver.portal.ListOf
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.serializeTree
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K4: templates and lists with references through the document, both wires and the exporter. */
class TemplateListTest {
  private val brand = PropValue.Token("C.Brand", lit("Acme"), "app.components.C")
  private val start = PropValue.Token("C.Start", lit(-2840), "app.components.C")
  private val rating = PropValue.Template(listOf(lit("Rating: "), PropValue.Bind("rating"), lit(" · "), brand))
  private val colors = PropValue.ListOf("li", listOf(lit(16777215), start, PropValue.Bind("accent")))

  private fun doc(vararg children: DocNode): UiDocument =
    UiDocument("t", DocNode.Widget(Handle(1), "Column", children = children.toList()), Contract(), 0, 10)

  private fun text(h: Long) = DocNode.Widget(Handle(h), "StyledText", props = mapOf("text" to lit("x")))
  private fun box(h: Long) = DocNode.Widget(Handle(h), "StyledBox")

  private fun refused(d: UiDocument, op: DocOp): String = assertNotNull(d.tryApply(op).error, "expected a refusal: $op")

  @Test fun bothRoundTripOnTheDocumentWire() {
    val node: DocNode = DocNode.Widget(Handle(2), "StyledBox", props = mapOf("gradientColorsArgb" to colors))
    assertEquals(node, DocJson.decodeFromString<DocNode>(DocJson.encodeToString(node)))
    val batch = OpBatch(1, OpEnvelope("s", 0), listOf(DocOp.SetProp(Handle(2), "text", rating)))
    assertEquals(batch, DocJson.decodeFromString<OpBatch>(DocJson.encodeToString(batch)))
  }

  @Test fun theTreeAndTheDeviceWireCarryResolvedValuesForOlderReaders() {
    val d = doc(
      DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to rating)),
      DocNode.Widget(Handle(3), "StyledBox", props = mapOf("gradientColorsArgb" to colors)),
    )
    val tree = d.toWidgetTree()
    assertEquals(Template(listOf("Rating: ", Bind("rating"), " · ", Token("C.Brand", "Acme", "app.components.C"))), tree.children[0].props["text"])
    assertEquals(ListOf("li", listOf(16777215, Token("C.Start", -2840, "app.components.C"), Bind("accent"))), tree.children[1].props["gradientColorsArgb"])
    val json = serializeTree(tree)
    assertTrue("{\"s\":\"Rating: {rating} · Acme\",\"template\":" in json, json)
    assertTrue("{\"li\":[16777215,-2840,0],\"items\":" in json, json)
    assertEquals(tree, deserializeTree(json))
    // Editor -> server (duplicate) lifts both back unchanged.
    assertEquals(rating, tree.children[0].toDocNode().props["text"])
    assertEquals(colors, tree.children[1].toDocNode().props["gradientColorsArgb"])
    // The mocks fill a template's binding.
    assertEquals(Template(listOf("Rating: ", "4", " · ", Token("C.Brand", "Acme", "app.components.C"))), d.toWidgetTree(mocks = mapOf("rating" to "4")).children[0].props["text"])
  }

  @Test fun opsStoreTheCanonicalForm() {
    val d = doc(text(2), box(3))
    val set = d.apply(DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(lit("a"), lit(""), lit("b"), PropValue.Bind("x"), lit(7))))).doc
    assertEquals(PropValue.Template(listOf(lit("ab"), PropValue.Bind("x"), lit("7"))), (set.find(Handle(2)) as DocNode.Widget).props["text"])
    // No reference left: a plain literal. A list of literals: a plain literal list.
    val plain = d.apply(DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(lit("a"), lit("b"))))).doc
    assertEquals(lit("ab"), (plain.find(Handle(2)) as DocNode.Widget).props["text"])
    val list = d.apply(DocOp.SetProp(Handle(3), "gradientColorsArgb", PropValue.ListOf("li", listOf(lit(1), lit(2))))).doc
    assertEquals(PropValue.Lit("li", li = listOf(1, 2)), (list.find(Handle(3)) as DocNode.Widget).props["gradientColorsArgb"])
    // Inserted nodes too.
    val inserted = d.apply(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to PropValue.Template(listOf(lit("z"))))))).doc
    assertEquals(lit("z"), ((inserted.root as DocNode.Widget).children.first() as DocNode.Widget).props["text"])
  }

  @Test fun whatAnOpMayNotCreateIsRefused() {
    val d = doc(text(2), box(3))
    // A template only on a String prop; a list with references only on its list prop.
    assertTrue("fits only a String prop" in refused(d, DocOp.SetProp(Handle(2), "fontSize", rating)))
    assertTrue("fits only a String prop" in refused(d, DocOp.SetModifier(Handle(2), "Background.colorArgb", rating)))
    assertTrue("List<Int> or List<String>" in refused(d, DocOp.SetProp(Handle(2), "text", colors)))
    assertTrue("tag" in refused(d, DocOp.SetProp(Handle(3), "gradientColorsArgb", PropValue.ListOf("ls", listOf(brand)))))
    // Parts and elements: references only — never an action, a nested list or a conditional.
    assertTrue("a part is" in refused(d, DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(PropValue.Action("go"))))))
    assertTrue("not a field name" in refused(d, DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(PropValue.Bind("a b"))))))
    assertTrue("does not fit" in refused(d, DocOp.SetProp(Handle(3), "gradientColorsArgb", PropValue.ListOf("li", listOf(brand)))))
    assertTrue("literal element" in refused(d, DocOp.SetProp(Handle(3), "gradientColorsArgb", PropValue.ListOf("li", listOf(lit("x"), start)))))
    assertTrue("cannot be conditional" in refused(d, DocOp.SetProp(Handle(3), "gradientColorsArgb", PropValue.Conditional("on", whenTrue = colors, whenFalse = colors))))
    // An item reference outside a Repeat over its item.
    assertTrue("outside a Repeat" in refused(d, DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(PropValue.Bind("note.title"))))))
    assertTrue("outside a Repeat" in refused(d, DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "StyledBox", props = mapOf("gradientColorsArgb" to PropValue.ListOf("li", listOf(PropValue.Bind("row.c"))))))))
    // Inside one it is fine; a template is a conditional's branch.
    val rep = doc(DocNode.Widget(Handle(2), "Repeat", props = mapOf("items" to lit("notes"), "item" to lit("note")), children = listOf(text(3))))
    assertNull(rep.tryApply(DocOp.SetProp(Handle(3), "text", PropValue.Template(listOf(PropValue.Bind("note.title"))))).error)
    assertNull(d.tryApply(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("on", whenTrue = rating, whenFalse = lit("-")))).error)
  }

  @Test fun aReferenceTheDeviceAndThePreviewPrintDifferentlyIsRefused() {
    // A Double prints `1` on the device (Kotlin/JS) and `1.0` in the preview
    // (wasm/JVM); a nullable value prints `null` on the device and nothing in
    // the preview. No template reads either — a token, or a field the
    // Bindings interface declares so.
    val ratio = PropValue.Token("C.Ratio", PropValue.Lit("d", d = 1.0), "app.components.C")
    val d = UiDocument(
      "t",
      DocNode.Widget(Handle(1), "Column", children = listOf(text(2), box(3), DocNode.Widget(Handle(4), "SegmentedButtonRow"))),
      Contract(fields = mapOf("score" to "Double", "ratio" to "Float", "nick" to "String?", "n" to "Int", "big" to "Long", "on" to "Boolean", "s" to "String", "qn" to "kotlin.Int", "qs" to "kotlin.String")),
      0, 10,
    )
    assertTrue("Double" in refused(d, DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(lit("x "), ratio)))))
    assertTrue("Double" in refused(d, DocOp.SetProp(Handle(2), "text", PropValue.Conditional("on", whenTrue = PropValue.Template(listOf(ratio)), whenFalse = lit("-")))))
    for (field in listOf("score", "ratio", "nick")) {
      val msg = refused(d, DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(lit("x "), PropValue.Bind(field)))))
      assertTrue(field in msg && "declared" in msg, msg)
      assertTrue(field in refused(d, DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to PropValue.Template(listOf(PropValue.Bind(field))))))))
    }
    for (field in listOf("n", "big", "on", "s", "qn", "qs", "undeclared")) {
      assertNull(d.tryApply(DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(lit("x "), PropValue.Bind(field))))).error, field)
    }
    // A bound list element is declared as the element type, if at all.
    assertTrue("nick" in refused(d, DocOp.SetProp(Handle(4), "options", PropValue.ListOf("ls", listOf(lit("a"), PropValue.Bind("nick"))))))
    assertTrue("score" in refused(d, DocOp.SetProp(Handle(3), "gradientColorsArgb", PropValue.ListOf("li", listOf(PropValue.Bind("score"))))))
    assertNull(d.tryApply(DocOp.SetProp(Handle(4), "options", PropValue.ListOf("ls", listOf(lit("a"), PropValue.Bind("s"))))).error)
    assertNull(d.tryApply(DocOp.SetProp(Handle(3), "gradientColorsArgb", PropValue.ListOf("li", listOf(PropValue.Bind("n"))))).error)
    // A field whose type ingest could not read (`val x get() = 1.5`) is no template's reference;
    // a list element of it is left to the compile (a wrong element type does not compile).
    val unknown = d.copy(contract = Contract(fields = mapOf("x" to "<unknown>")))
    val msg = refused(unknown, DocOp.SetProp(Handle(2), "text", PropValue.Template(listOf(PropValue.Bind("x")))))
    assertTrue("`x`" in msg && "no type" in msg, msg)
    assertNull(unknown.tryApply(DocOp.SetProp(Handle(3), "gradientColorsArgb", PropValue.ListOf("li", listOf(PropValue.Bind("x"))))).error)
    // A fully qualified spelling is the same type.
    assertNull(d.tryApply(DocOp.SetProp(Handle(4), "options", PropValue.ListOf("ls", listOf(PropValue.Bind("qs"))))).error)
    assertNull(d.tryApply(DocOp.SetProp(Handle(3), "gradientColorsArgb", PropValue.ListOf("li", listOf(PropValue.Bind("qn"))))).error)
  }

  @Test fun aFieldATemplateOrAListReadsIsNotABoolean() {
    val accent = PropValue.Template(listOf(lit("tone "), PropValue.Bind("accent")))
    val tint = PropValue.ListOf("li", listOf(lit(1), PropValue.Bind("tint")))
    fun cond(field: String) = PropValue.Conditional(field, whenTrue = lit("a"), whenFalse = lit("b"))
    fun condition(field: String) = DocNode.Widget(Handle(0), "Condition", props = mapOf("field" to lit(field)), children = listOf(text(0)))
    // The template / the list first: a Boolean test on its field is refused.
    val d = doc(
      DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to accent)),
      DocNode.Widget(Handle(3), "StyledBox", props = mapOf("gradientColorsArgb" to tint)),
      text(4),
    )
    for (field in listOf("accent", "tint")) {
      assertTrue("as a Boolean" in refused(d, DocOp.SetProp(Handle(4), "text", cond(field))))
      assertTrue("as a Boolean" in refused(d, DocOp.InsertNode(Handle(1), null, condition(field))))
    }
    // Also in a conditional's branch.
    val inBranch = doc(DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to cond("on").copy(whenTrue = accent))), text(4))
    assertTrue("as a Boolean" in refused(inBranch, DocOp.SetProp(Handle(4), "text", cond("accent"))))
    // The condition first: a template or a list reading its field is refused.
    val c = doc(
      DocNode.Widget(Handle(2), "Condition", props = mapOf("field" to lit("accent")), children = listOf(text(5))),
      text(3),
      box(4),
      DocNode.Widget(Handle(6), "StyledText", props = mapOf("text" to cond("tint"))),
    )
    assertTrue("as a Boolean" in refused(c, DocOp.SetProp(Handle(3), "text", accent)))
    assertTrue("as a Boolean" in refused(c, DocOp.SetProp(Handle(4), "gradientColorsArgb", PropValue.ListOf("li", listOf(PropValue.Bind("accent"))))))
    assertTrue("as a Boolean" in refused(c, DocOp.SetProp(Handle(4), "gradientColorsArgb", tint)))
    assertTrue("as a Boolean" in refused(c, DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to accent)))))
    // An emptiness test reads a String: no conflict.
    val empty = doc(DocNode.Widget(Handle(2), "Condition", props = mapOf("field" to lit("accent"), "test" to lit("empty")), children = listOf(text(5))), text(3))
    assertNull(empty.tryApply(DocOp.SetProp(Handle(3), "text", accent)).error)
  }

  @Test fun aDeclaredBooleanIsTestedAndPrintedByATemplate() {
    // `val on: Boolean`: `if (b.on) { … }` and `"on: ${'$'}{b.on}"` compile together.
    val onText = PropValue.Template(listOf(lit("on: "), PropValue.Bind("on")))
    fun d(fields: Map<String, String>) = UiDocument(
      "t",
      DocNode.Widget(Handle(1), "Column", children = listOf(
        DocNode.Widget(Handle(2), "Condition", props = mapOf("field" to lit("on")), children = listOf(text(5))),
        DocNode.Widget(Handle(3), "StyledText", props = mapOf("text" to onText)),
        box(4),
        text(6),
      )),
      Contract(fields = fields),
      0, 10,
    )
    val newCondition = DocNode.Widget(Handle(0), "Condition", props = mapOf("field" to lit("on")), children = listOf(text(0)))
    val selfReading = PropValue.Conditional("on", whenTrue = onText, whenFalse = lit("-"))
    val ops = listOf(
      DocOp.SetProp(Handle(3), "text", PropValue.Template(listOf(lit("now "), PropValue.Bind("on")))),
      DocOp.MoveNode(Handle(3), Handle(4), null),
      DocOp.MoveNode(Handle(3), Handle(2), Handle(5)),
      DocOp.SetProp(Handle(2), "negate", lit(true)),
      DocOp.InsertNode(Handle(1), null, newCondition),
      DocOp.SetProp(Handle(6), "text", selfReading),
    )
    for (type in listOf("Boolean", "kotlin.Boolean")) {
      val declared = d(mapOf("on" to type))
      for (op in ops) assertNull(declared.tryApply(op).error, "$type: $op")
      // The conditional's own template is no evidence against its test (also alone on the screen).
      val alone = UiDocument("t", DocNode.Widget(Handle(1), "Column", children = listOf(text(6))), Contract(fields = mapOf("on" to type)), 0, 10)
      assertNull(alone.tryApply(DocOp.SetProp(Handle(6), "text", selfReading)).error, type)
    }
    // Undeclared, the contract would have to type `on` both a Boolean and text: refused as before.
    val undeclared = d(emptyMap())
    for (op in ops.filter { it !is DocOp.MoveNode }) assertTrue("as a Boolean" in refused(undeclared, op), "$op")
    // Declared as text, a Boolean test on it is still refused.
    assertTrue("as a Boolean" in refused(d(mapOf("on" to "String")), DocOp.InsertNode(Handle(1), null, newCondition)))
  }

  @Test fun aRepeatsWholeListIsNotOneValue() {
    // `b.notes.forEach { … }` makes `notes` a List: a template would print it, a
    // list element or a text prop cannot hold it.
    fun repeat(h: Long, field: String) =
      DocNode.Widget(Handle(h), "Repeat", props = mapOf("items" to lit(field), "item" to lit("note")), children = listOf(text(h + 100)))
    val notesText = PropValue.Template(listOf(lit("n: "), PropValue.Bind("notes")))
    val d = doc(repeat(2, "notes"), text(3), box(4), DocNode.Widget(Handle(5), "SegmentedButtonRow"))
    val refusedOps = listOf(
      DocOp.SetProp(Handle(3), "text", notesText),
      DocOp.SetProp(Handle(3), "text", PropValue.Conditional("on", whenTrue = notesText, whenFalse = lit("-"))),
      DocOp.SetProp(Handle(4), "gradientColorsArgb", PropValue.ListOf("li", listOf(lit(1), PropValue.Bind("notes")))),
      DocOp.SetProp(Handle(5), "options", PropValue.ListOf("ls", listOf(PropValue.Bind("notes")))),
      DocOp.SetProp(Handle(3), "text", PropValue.Bind("notes")),
      DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to notesText))),
    )
    for (op in refusedOps) assertTrue("Repeat" in refused(d, op) && "notes" in refused(d, op), "$op")
    // A List prop may be bound to the same list; another field, or an item field, is fine.
    assertNull(d.tryApply(DocOp.SetProp(Handle(5), "options", PropValue.Bind("notes"))).error)
    assertNull(d.tryApply(DocOp.SetProp(Handle(3), "text", PropValue.Template(listOf(PropValue.Bind("city"))))).error)
    assertNull(d.tryApply(DocOp.SetProp(Handle(102), "text", PropValue.Template(listOf(PropValue.Bind("note.title"))))).error)

    // The other way round: a Repeat over a field a template, a list or a text prop reads.
    val reads = doc(
      DocNode.Widget(Handle(3), "StyledText", props = mapOf("text" to PropValue.Template(listOf(PropValue.Bind("tags"))))),
      DocNode.Widget(Handle(4), "StyledBox", props = mapOf("gradientColorsArgb" to PropValue.ListOf("li", listOf(PropValue.Bind("tints"))))),
      DocNode.Widget(Handle(5), "StyledText", props = mapOf("text" to PropValue.Bind("label"))),
      repeat(6, "other"),
    )
    for (field in listOf("tags", "tints", "label")) {
      assertTrue("Repeat" in refused(reads, DocOp.InsertNode(Handle(1), null, repeat(0, field))), field)
      assertTrue("Repeat" in refused(reads, DocOp.SetProp(Handle(6), "items", lit(field))), field)
    }
    assertNull(reads.tryApply(DocOp.InsertNode(Handle(1), null, repeat(0, "notes"))).error)
  }

  @Test fun aReferenceIsBracedWhereWhatFollowsWouldContinueItsName() {
    // U+20000 is a letter outside the BMP: a surrogate pair continues a Kotlin identifier.
    val cjk = "\uD840\uDC00"
    assertEquals("\${x}", dev.keliver.portal.templateRef("x", '\uD840'))
    assertEquals("\"\${x}$cjk\"", dev.keliver.portal.fmtTemplate(Template(listOf(Bind("x"), cjk))) { it })
    assertEquals("\"\$x \"", dev.keliver.portal.fmtTemplate(Template(listOf(Bind("x"), " "))) { it })
    // A value preview expansion substituted is written as its text: a name before it is braced too.
    assertEquals("\"\${x}5\"", dev.keliver.portal.fmtTemplate(Template(listOf(Bind("x"), 5))) { it })
    assertEquals("\"\${x}ab!\"", dev.keliver.portal.fmtTemplate(Template(listOf(Bind("x"), Template(listOf("ab")), "!"))) { it })
    assertEquals("\"\$x-1\"", dev.keliver.portal.fmtTemplate(Template(listOf(Bind("x"), -1))) { it })
    // An empty text part is no boundary: what follows it decides.
    assertEquals("\"\${x}y\"", dev.keliver.portal.fmtTemplate(Template(listOf(Bind("x"), "", "y"))) { it })
  }

  @Test fun aMockedIntListElementReachesTheDeviceWire() {
    val d = doc(DocNode.Widget(Handle(3), "StyledBox", props = mapOf("gradientColorsArgb" to colors)))
    // A blank mock (none typed yet) is 0, as an unmocked binding is.
    assertTrue("{\"li\":[16777215,-2840,0],\"items\":" in serializeTree(d.toWidgetTree(mocks = mapOf("accent" to " "))))
    for (mock in listOf<Any?>("255", 255, " 255 ")) {
      val json = serializeTree(d.toWidgetTree(mocks = mapOf("accent" to mock)))
      assertTrue("{\"li\":[16777215,-2840,255],\"items\":" in json, json)
    }
    // A colour as the editor spells one: #RRGGBB (opaque) or #AARRGGBB.
    for ((mock, argb) in listOf("#FF0000" to 0xFFFF0000.toInt(), " #80ff0000 " to 0x80FF0000.toInt())) {
      val json = serializeTree(d.toWidgetTree(mocks = mapOf("accent" to mock)))
      assertTrue("{\"li\":[16777215,-2840,$argb],\"items\":" in json, json)
    }
    // Anything else is no Int: the element is left out, not sent as 0 (transparent black).
    for (mock in listOf("red", "#F00", "1.5")) {
      val json = serializeTree(d.toWidgetTree(mocks = mapOf("accent" to mock)))
      assertTrue("{\"li\":[16777215,-2840],\"items\":" in json, json)
    }
  }

  @Test fun theExporterWritesCompilingKotlin() {
    val d = doc(
      DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to rating)),
      DocNode.Widget(Handle(3), "StyledBox", props = mapOf("gradientColorsArgb" to colors)),
      DocNode.Widget(Handle(4), "StyledText", props = mapOf("text" to PropValue.Conditional("on", whenTrue = PropValue.Template(listOf(PropValue.Bind("n"), lit("!"))), whenFalse = lit("-")))),
    )
    val out = exportKotlin(d.toWidgetTree(), "S")
    assertTrue("text = \"Rating: \${b.rating} · \${C.Brand}\"," in out, out)
    assertTrue("gradientColorsArgb = listOf(16777215, C.Start, b.accent)," in out, out)
    assertTrue("text = if (b.on) \"\${b.n}!\" else \"-\"," in out, out)
    assertTrue("import app.components.C\n" in out, out)
    assertTrue("val rating: String" in out && "val accent: Int" in out && "val n: String" in out, out)
  }

  @Test fun anOlderEditorGetsTextAndResolvedLists() {
    val d = doc(
      DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to PropValue.Conditional("on", whenTrue = rating, whenFalse = lit("-")))),
      DocNode.Widget(Handle(3), "StyledBox", props = mapOf("gradientColorsArgb" to colors)),
    )
    val old = d.withoutTemplates().withoutLists().withoutConditionals()
    assertEquals(lit("Rating: {rating} · Acme"), (old.find(Handle(2)) as DocNode.Widget).props["text"])
    assertEquals(PropValue.Lit("li", li = listOf(16777215, -2840, 0)), (old.find(Handle(3)) as DocNode.Widget).props["gradientColorsArgb"])
    // A token view without tokens: the template's token part becomes text.
    val atoms = rating.mapAtoms { if (it is PropValue.Token) it.value else it }
    assertEquals(PropValue.Template(listOf(lit("Rating: "), PropValue.Bind("rating"), lit(" · Acme"))), atoms)
    assertEquals(listOf(lit("Rating: "), PropValue.Bind("rating"), lit(" · "), brand), rating.atoms())
  }
}
