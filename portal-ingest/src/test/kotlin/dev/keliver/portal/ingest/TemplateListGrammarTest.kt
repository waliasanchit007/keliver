package dev.keliver.portal.ingest

import dev.keliver.portal.Bind
import dev.keliver.portal.ListOf
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.collectContract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.document.withoutLists
import dev.keliver.portal.document.withoutTemplates
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.intList
import dev.keliver.portal.serializeTree
import dev.keliver.portal.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K4: string templates over grammar references (`"Rating: ${'$'}{b.rating}"`)
 * and lists whose elements are design tokens or bindings
 * (`listOf(C.Start, C.End)`) are grammar in screens — recognized, written
 * back surgically (a no-op is byte-identical; an edit rewrites only the
 * changed part or element, keeping the author's `$x` / `${'$'}{x}` and escapes),
 * exported as compiling Kotlin, and previewed.
 */
class TemplateListGrammarTest {
  private val tokensKt = """
    package app.components

    object C {
      const val Start = -2840
      const val End = -5674
      const val Brand = "Acme"
      const val Max = 5
      const val Ratio = 1.5
    }
  """.trimIndent()
  private val tokens = TokenTable.scan(listOf("Tokens.kt" to tokensKt))

  // § is `$` (kept out of the Kotlin raw string).
  private val src = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.C
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.SegmentedButtonRow
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        // a comment that must survive
        StyledText(text = "Rating: §{b.rating}/§{C.Max}")
        StyledText(text = ", §{b.city}")
        StyledText(text = "§{b.count} items")
        StyledText(text = "Say \"hi\" \§5\n§{C.Brand}${'$'}{'§'}!")
        StyledBox(gradientColorsArgb = listOf(C.Start, C.End), gradientStops = listOf(0.0f, 1.0f)) {
          StyledText(text = "x")
        }
        SegmentedButtonRow(options = listOf("All", C.Brand, b.extra), selectedIndex = 0)
        b.notes.forEach { note ->
          StyledText(text = "§{note.title} (§{note.count})")
        }
      }
    }

    interface HomeScreenBindings {
      val rating: String
      val city: String
      val count: Int
      val extra: String
      val notes: List<Note>
    }

    interface Note {
      val title: String
      val count: String
    }
  """.trimIndent().replace('§', '$')

  private val file = "app/screens/HomeScreen.kt"

  private fun doc(text: String = src): UiDocument =
    Recognizer.recognize(file, text, tokens = tokens)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun merge(target: UiDocument, text: String = src): String =
    assertNotNull(WriteBack.merge(text, target, tokens = tokens, fileName = file, base = doc(text)))

  private fun column(d: UiDocument) = d.root as DocNode.Widget
  private fun child(d: UiDocument, i: Int) = column(d).children[i] as DocNode.Widget

  private fun containsRaw(n: DocNode): Boolean = when (n) {
    is DocNode.RawCode -> true
    is DocNode.Widget -> n.children.any(::containsRaw)
  }

  private fun assertOnly(edited: String, old: String, new: String) {
    assertEquals(1, src.split(old).size - 1, "the edit's anchor occurs once: $old")
    assertEquals(src.replace(old, new), edited)
  }

  private val max = PropValue.Token("C.Max", lit(5), "app.components.C")
  private val brand = PropValue.Token("C.Brand", lit("Acme"), "app.components.C")

  @Test fun everyFormIsRecognizedWithoutRawCode() {
    val d = doc()
    assertTrue(!containsRaw(d.root), d.root.toString())
    assertEquals(
      PropValue.Template(listOf(lit("Rating: "), PropValue.Bind("rating"), lit("/"), max)),
      child(d, 0).props["text"],
    )
    assertEquals(PropValue.Template(listOf(lit(", "), PropValue.Bind("city"))), child(d, 1).props["text"])
    assertEquals(PropValue.Template(listOf(PropValue.Bind("count"), lit(" items"))), child(d, 2).props["text"])
    // Escapes keep their meaning: \" \$ \n and the `${'$'}{'$'}` idiom are text.
    assertEquals(PropValue.Template(listOf(lit("Say \"hi\" \$5\n"), brand, lit("\$!"))), child(d, 3).props["text"])
    assertEquals(
      PropValue.ListOf("li", listOf(PropValue.Token("C.Start", lit(-2840), "app.components.C"), PropValue.Token("C.End", lit(-5674), "app.components.C"))),
      child(d, 4).props["gradientColorsArgb"],
    )
    assertEquals(PropValue.Lit("lf", lf = listOf(0.0f, 1.0f)), child(d, 4).props["gradientStops"])
    assertEquals(PropValue.ListOf("ls", listOf(lit("All"), brand, PropValue.Bind("extra"))), child(d, 5).props["options"])
    val repeat = child(d, 6)
    assertEquals(
      PropValue.Template(listOf(PropValue.Bind("note.title"), lit(" ("), PropValue.Bind("note.count"), lit(")"))),
      (repeat.children.single() as DocNode.Widget).props["text"],
    )
  }

  @Test fun anExpressionInATemplateOrAnUnfitListElementStaysRawCode() {
    fun one(stmt: String): DocNode = column(doc(src.replace("StyledText(text = \", \${b.city}\")", stmt))).children[1]
    assertIs<DocNode.RawCode>(one("StyledText(text = \"\${b.count + 1}\")"))
    assertIs<DocNode.RawCode>(one("StyledText(text = \"\${b.city.uppercase()}\")"))
    assertIs<DocNode.RawCode>(one("StyledText(text = \"\${1}\")"))
    assertIs<DocNode.RawCode>(one("StyledText(text = \"\$local\")"))
    assertIs<DocNode.RawCode>(one("StyledText(text = \"\"\"\${b.city}\"\"\")"))
    // A template only where a String is taken; a list element of its list's kind.
    assertIs<DocNode.RawCode>(one("StyledText(text = \"x\", fontSize = \"\${b.count}\")"))
    assertIs<DocNode.RawCode>(one("StyledBox(gradientColorsArgb = listOf(C.Brand, C.End))"))
    assertIs<DocNode.RawCode>(one("StyledBox(gradientColorsArgb = listOf(C.Ratio))"))
    assertIs<DocNode.RawCode>(one("StyledBox(gradientColorsArgb = listOf(b.a + 1))"))
  }

  @Test fun aDoubleOrNullableReferenceInATemplateStaysRawCode() {
    // The device (Kotlin/JS) prints 1.0 as "1" and a null as "null"; the
    // preview prints "1.0" and nothing — no faithful preview, so not grammar.
    val typed = src.replace("  val extra: String\n", "  val extra: String\n  val score: Double\n  val ratio: Float\n  val nick: String?\n  val big: Long\n")
    assertTrue("val score: Double" in typed)
    fun one(stmt: String): DocNode = column(doc(typed.replace("StyledText(text = \", \${b.city}\")", stmt))).children[1]
    assertIs<DocNode.RawCode>(one("StyledText(text = \"\${C.Ratio}\")"))
    assertIs<DocNode.RawCode>(one("StyledText(text = \"x \${b.score}\")"))
    assertIs<DocNode.RawCode>(one("StyledText(text = \"x \${b.ratio}\")"))
    assertIs<DocNode.RawCode>(one("StyledText(text = \"x \${b.nick}\")"))
    assertIs<DocNode.RawCode>(one("StyledText(text = if (b.on) \"x \${b.score}\" else \"-\")"))
    // A bound list element is declared as the element type.
    assertIs<DocNode.RawCode>(one("SegmentedButtonRow(options = listOf(\"a\", b.nick))"))
    assertIs<DocNode.RawCode>(one("StyledBox(gradientColorsArgb = listOf(b.score))"))
    // String, Int, Long, Boolean and undeclared fields print alike: grammar.
    assertIs<DocNode.Widget>(one("StyledText(text = \"x \${b.big} \${b.count} \${b.undeclared}\")"))
    assertIs<DocNode.Widget>(one("SegmentedButtonRow(options = listOf(\"a\", b.city))"))
  }

  @Test fun aNoOpWriteBackIsByteIdentical() {
    assertEquals(src, merge(doc()))
  }

  @Test fun editingOneTemplatePartRewritesOnlyThatPart() {
    val d = doc()
    val t = child(d, 0).props["text"] as PropValue.Template
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 0).handle, "text", t.copy(parts = listOf(lit("Score: ")) + t.parts.drop(1)))).doc),
      "\"Rating: \${b.rating}", "\"Score: \${b.rating}",
    )
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 0).handle, "text", t.copy(parts = t.parts.take(3) + PropValue.Bind("count")))).doc),
      "/\${C.Max}\")", "/\${b.count}\")",
    )
    // The escapes around an unchanged part keep their spelling.
    val e = child(d, 3).props["text"] as PropValue.Template
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 3).handle, "text", e.copy(parts = e.parts.take(2) + lit("?")))).doc),
      "\${C.Brand}\${'$'}!\")", "\${C.Brand}?\")",
    )
    // A text segment with characters that need escaping is written escaped.
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 1).handle, "text", PropValue.Template(listOf(lit("\"\$\" "), PropValue.Bind("city"))))).doc),
      "\", \${b.city}\"", "\"\\\"\\\$\\\" \${b.city}\"",
    )
  }

  @Test fun aSimpleNameKeepsItsSpellingAndIsBracedWhenTextWouldContinueIt() {
    val text = src.replace("\"\${b.count} items\"", "\"\$top items\"").replace("fun HomeScreen(", "fun HomeScreen(").let {
      it.replace("object C", "object C") // no-op
    }
    // `top` is a top-level token in the screen's package.
    val withTop = TokenTable.scan(listOf("Tokens.kt" to tokensKt, "Top.kt" to "package app.screens\n\nconst val top = 3\n"))
    val d = Recognizer.recognize(file, text, tokens = withTop)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }
    val t = child(d, 2).props["text"] as PropValue.Template
    assertIs<PropValue.Token>(t.parts[0])
    assertEquals(text, WriteBack.merge(text, d, tokens = withTop, fileName = file, base = d))
    // New text right after `$top` that would continue the name: the reference is braced.
    val edited = WriteBack.merge(
      text, d.apply(DocOp.SetProp(child(d, 2).handle, "text", t.copy(parts = listOf(t.parts[0], lit("items")))) ).doc,
      tokens = withTop, fileName = file, base = d,
    )
    assertEquals(text.replace("\"\$top items\"", "\"\${top}items\""), edited)
    // A letter outside the BMP (U+20000, a surrogate pair) continues the name too.
    val cjk = "\uD840\uDC00"
    val edited2 = WriteBack.merge(
      text, d.apply(DocOp.SetProp(child(d, 2).handle, "text", t.copy(parts = listOf(t.parts[0], lit(cjk))))).doc,
      tokens = withTop, fileName = file, base = d,
    )
    assertEquals(text.replace("\"\$top items\"", "\"\${top}$cjk\""), edited2)
    // …in the surgical rewrite itself, not only in the canonical fallback.
    assertEquals(
      "\"\${top}$cjk A\"",
      TemplateSyntax.surgical("\"\$top items \\u0041\"", listOf(t.parts[0], lit(" items A")), listOf(t.parts[0], lit("$cjk A"))) { (it as? PropValue.Token)?.ref },
    )
  }

  @Test fun turningAPlainStringIntoATemplateAndBack() {
    val d = doc()
    val x = (child(d, 4).children.single() as DocNode.Widget)
    val out = merge(d.apply(DocOp.SetProp(x.handle, "text", PropValue.Template(listOf(lit("x "), PropValue.Bind("city"))))).doc)
    assertEquals(src.replace("StyledText(text = \"x\")", "StyledText(text = \"x \${b.city}\")"), out)
    val back = doc(out)
    val x2 = (child(back, 4).children.single() as DocNode.Widget)
    assertEquals(src, merge(back.apply(DocOp.SetProp(x2.handle, "text", lit("x"))).doc, out))
  }

  @Test fun editingOneListElementRewritesOnlyThatElement() {
    val d = doc()
    val g = child(d, 4).props["gradientColorsArgb"] as PropValue.ListOf
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 4).handle, "gradientColorsArgb", g.copy(items = listOf(g.items[0], lit(-1))))).doc),
      "listOf(C.Start, C.End)", "listOf(C.Start, -1)",
    )
    val o = child(d, 5).props["options"] as PropValue.ListOf
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 5).handle, "options", o.copy(items = listOf(lit("Any"), o.items[1], o.items[2])))).doc),
      "listOf(\"All\", C.Brand", "listOf(\"Any\", C.Brand",
    )
    // A literal list gains a token element: only that element is rewritten.
    val fromLiteral = src.replace("listOf(C.Start, C.End)", "listOf(-1, -2)")
    val d2 = doc(fromLiteral)
    val start = PropValue.Token("C.Start", lit(-2840), "app.components.C")
    assertEquals(
      fromLiteral.replace("listOf(-1, -2)", "listOf(-1, C.Start)"),
      merge(d2.apply(DocOp.SetProp(child(d2, 4).handle, "gradientColorsArgb", PropValue.ListOf("li", listOf(lit(-1), start)))).doc, fromLiteral),
    )
  }

  @Test fun aNewTokenInATemplateOrAListAddsItsImport() {
    val noImport = src.replace("import app.components.C\n", "")
      .replace("/\${C.Max}", "").replace("\${C.Brand}", "").replace("listOf(C.Start, C.End)", "listOf(1, 2)")
      .replace("listOf(\"All\", C.Brand, b.extra)", "listOf(\"All\", b.extra)")
    val d = doc(noImport)
    assertTrue(!containsRaw(d.root))
    val t = child(d, 0).props["text"] as PropValue.Template
    val out = assertNotNull(
      WriteBack.merge(noImport, d.apply(DocOp.SetProp(child(d, 0).handle, "text", PropValue.Template(t.parts + max))).doc, tokens = tokens, fileName = file, base = d),
    )
    assertTrue("import app.components.C\n" in out, out)
    assertTrue("\"Rating: \${b.rating}\${C.Max}\"" in out, out)
    val out2 = assertNotNull(
      WriteBack.merge(noImport, d.apply(DocOp.SetProp(child(d, 4).handle, "gradientColorsArgb", PropValue.ListOf("li", listOf(lit(1), PropValue.Token("C.End", lit(-5674), "app.components.C"))))).doc, tokens = tokens, fileName = file, base = d),
    )
    assertTrue("import app.components.C\n" in out2 && "listOf(1, C.End)" in out2, out2)
  }

  @Test fun theExportReadsBackAsTheDocument() {
    val d = doc()
    val exported = NodeEmitter.screen(d, "HomeScreen")
    assertTrue("text = \"Rating: \${b.rating}/\${C.Max}\"" in exported, exported)
    assertTrue("gradientColorsArgb = listOf(C.Start, C.End)" in exported, exported)
    assertTrue("options = listOf(\"All\", C.Brand, b.extra)" in exported, exported)
    assertTrue("import app.components.C" in exported, exported)
    val back = Recognizer.recognize(file, "package app.screens\n\n$exported", tokens = tokens)!!
    assertTrue(WriteBack.sameTree(back.root, d.root), WriteBack.firstDifference(back.root, d.root))
  }

  @Test fun theContractTypesATemplateOnlyFieldAsAString() {
    val tree = doc().toWidgetTree()
    val fields = collectContract(tree).fields
    assertEquals("Text", fields["city"]?.name)
    assertEquals("Text", fields["rating"]?.name)
    assertEquals("Text", fields["extra"]?.name)
    val exported = exportKotlin(tree, "HomeScreen")
    assertTrue("val rating: String" in exported, exported)
    assertTrue("interface Note {\n  val title: String\n  val count: String\n}" in exported, exported)
  }

  @Test fun previewEvaluatesTemplatesAndLists() {
    val tree = doc().toWidgetTree(mocks = mapOf("rating" to "4.5"))
    val col = tree.children
    assertEquals("Rating: 4.5/5", col[0].str("text"))
    assertEquals(", {city}", col[1].str("text"))
    assertEquals("Say \"hi\" \$5\nAcme\$!", col[3].str("text"))
    assertEquals(listOf(-2840, -5674), col[4].intList("gradientColorsArgb"))
    // The device wire carries the resolved text / list for a reader that predates K4.
    val wire = serializeTree(tree)
    assertTrue("\"s\":\"Rating: 4.5/5\"" in wire, wire)
    assertTrue("\"li\":[-2840,-5674]" in wire, wire)
    val back = deserializeTree(wire)
    assertEquals(Template(listOf("Rating: ", "4.5", "/", Token("C.Max", 5, "app.components.C"))), back.children[0].props["text"])
    assertEquals(ListOf("ls", listOf("All", Token("C.Brand", "Acme", "app.components.C"), Bind("extra"))), back.children[5].props["options"])
  }

  @Test fun anOlderEditorIsServedTheTextAndTheResolvedList() {
    val old = doc().withoutTemplates().withoutLists()
    assertEquals(lit("Rating: {rating}/5"), child(old, 0).props["text"])
    assertEquals(PropValue.Lit("li", li = listOf(-2840, -5674)), child(old, 4).props["gradientColorsArgb"])
    assertEquals(PropValue.Lit("ls", ls = listOf("All", "Acme", "{extra}")), child(old, 5).props["options"])
  }

  @Test fun anItemFieldOutsideItsRepeatIsRefused() {
    val d = doc()
    val r = d.tryApply(DocOp.SetProp(child(d, 1).handle, "text", PropValue.Template(listOf(PropValue.Bind("note.title")))))
    assertNull(r.result)
    assertTrue(r.error!!.contains("outside a Repeat"), r.error)
  }
}
