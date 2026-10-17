package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K1: layout modifiers (flex, margin, width, …) and Margin values are portal
 * grammar — a component written with them is TRANSPARENT, exports and
 * re-ingests to the same tree, and writes back surgically.
 */
class LayoutGrammarTest {
  private val statsCard = """
    import androidx.compose.runtime.Composable
    import dev.keliver.Modifier
    import dev.keliver.layout.api.Constraint
    import dev.keliver.layout.api.CrossAxisAlignment
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText
    import dev.keliver.ui.Margin
    import dev.keliver.ui.dp

    @Composable
    fun StatsCard(users: String, funds: String, payout: String) {
      StyledBox(colorArgb = -1711276033, cornerRadiusDp = 12, fillWidth = true) {
        Row(width = Constraint.Fill, margin = Margin(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)) {
          Column(horizontalAlignment = CrossAxisAlignment.Center, modifier = Modifier.flex(1.0)) {
            StyledText(text = users)
          }
          Column(modifier = Modifier.flex(1.0).margin(Margin(all = 4.dp))) { StyledText(text = funds) }
        }
      }
    }
  """.trimIndent()

  private fun d(v: Double) = PropValue.Lit("d", d = v)

  private fun containsRaw(n: DocNode): Boolean = when (n) {
    is DocNode.RawCode -> true
    is DocNode.Widget -> n.children.any(::containsRaw)
  }

  /** A one-function screen whose body is [body] (indented two spaces). */
  private fun screen(body: String) = "import androidx.compose.runtime.Composable\n\n@Composable\nfun S() {\n" +
    body.trimIndent().lines().joinToString("\n") { "  $it" } + "\n}\n"

  private fun root(body: String): DocNode.Widget = Recognizer.recognize("S.kt", screen(body))!!.root

  @Test fun componentWithMarginAndScopedModifiersIsTransparent() {
    val rc = Recognizer.recognizeComponent("StatsCard.kt", statsCard)
    assertNotNull(rc)
    assertTrue(rc.spec.transparent, "diagnostic: ${rc.spec.diagnostic}")
    val root = rc.root!!
    assertFalse(containsRaw(root))
    val row = root.children.single() as DocNode.Widget
    assertEquals("Row", row.type)
    assertEquals(d(16.0), row.props["margin.start"])
    assertEquals(d(16.0), row.props["margin.end"])
    assertEquals(d(8.0), row.props["margin.top"])
    assertEquals(d(8.0), row.props["margin.bottom"])
    val (first, second) = row.children.map { it as DocNode.Widget }
    assertEquals(mapOf("Flex.value" to d(1.0)), first.modifiers)
    assertEquals(PropValue.Lit("i", i = 1), first.props["horizontalAlignment"])
    assertEquals(
      mapOf(
        "Flex.value" to d(1.0),
        "Margin.margin.start" to d(4.0), "Margin.margin.end" to d(4.0),
        "Margin.margin.top" to d(4.0), "Margin.margin.bottom" to d(4.0),
      ),
      second.modifiers,
    )
    // The expanded body carries the wire keys the editor/renderer read.
    val body = rc.spec.body!!
    assertEquals(16.0, body.children[0].props["margin.start"])
    assertEquals(1.0, body.children[0].children[0].props["mod.Flex.value"])
  }

  @Test fun everyMarginFormParses() {
    fun m(t: String) = MarginSyntax.parse(t)?.toList()
    assertEquals(listOf(8.0, 8.0, 8.0, 8.0), m("Margin(all = 8.dp)"))
    assertEquals(listOf(8.0, 8.0, 8.0, 8.0), m("Margin(8.dp)"))
    assertEquals(listOf(16.0, 16.0, 4.0, 4.0), m("Margin(horizontal = 16.dp, vertical = 4.dp)"))
    assertEquals(listOf(0.0, 0.0, 4.0, 4.0), m("Margin(vertical = 4.dp)"))
    assertEquals(listOf(16.0, 16.0, 0.0, 0.0), m("Margin(horizontal = 16.dp)"))
    assertEquals(listOf(1.0, 0.0, 0.0, 2.0), m("Margin(start = 1.dp, bottom = 2.dp)"))
    assertEquals(listOf(1.0, 2.0, 3.0, 4.0), m("Margin(bottom = 4.dp, top = 3.dp, end = 2.dp, start = 1.dp,)"))
    assertEquals(listOf(0.0, 0.0, 0.0, 0.0), m("Margin.Zero"))
    assertEquals(listOf(0.0, 0.0, 0.0, 0.0), m("Margin()"))
    // Every Dp spelling.
    assertNull(m("Margin(all = Dp(4))")) // Dp takes a Double: `Dp(4)` does not compile
    assertEquals(listOf(4.0, 4.0, 4.0, 4.0), m("Margin(all = Dp(4.0))"))
    assertEquals(listOf(4.0, 4.0, 4.0, 4.0), m("Margin(all = 4.0.dp)"))
    assertEquals(listOf(4.5, 4.5, 4.5, 4.5), m("Margin(all = 4.5.dp)"))
    // Not grammar.
    // Positional values read as Kotlin resolves them (pinned by ParityLayout.kt):
    assertEquals(listOf(8.0, 8.0, 4.0, 4.0), m("Margin(8.dp, 4.dp)")) // (horizontal, vertical)
    assertEquals(listOf(1.0, 2.0, 3.0, 0.0), m("Margin(1.dp, 2.dp, 3.dp)")) // constructor
    assertEquals(listOf(1.0, 2.0, 3.0, 4.0), m("Margin(1.dp, 2.dp, 3.dp, 4.dp)"))
    assertNull(m("Margin(1.dp, 2.dp, 3.dp, 4.dp, 5.dp)"))
    assertNull(m("Margin(all = gap)"))
    assertNull(m("Margin(all = 4)"))
    assertNull(m("Margin(start = 1.dp, horizontal = 2.dp)"))
    assertNull(m("Margin(all = 1.dp, all = 2.dp)"))
    assertNull(m("Margin(8.dp, top = 2.dp)"))
    assertNull(m("Margin(all = 1.dp) + Margin(all = 2.dp)"))
    assertNull(m("Margin(all = 1.dp /* gap */)"))
  }

  @Test fun marginWidgetPropFormsAndNonGrammar() {
    val row = root("""Row(margin = Margin(horizontal = 16.dp)) {}""")
    assertEquals(d(16.0), row.props["margin.start"])
    assertEquals(d(0.0), row.props["margin.top"])
    // Mixed positional + named: not grammar -> the call stays RawCode.
    val raw = root("""
      Column {
        Row(margin = Margin(16.dp, top = 4.dp)) {}
      }
    """)
    assertIs<DocNode.RawCode>(raw.children.single())
  }

  @Test fun scopedModifierUnderAWrongParentStaysRawCode() {
    val r = root("""
      Row {
        StyledText(text = "in row", modifier = Modifier.flex(1.0))
        StyledBox {
          StyledText(text = "in box", modifier = Modifier.flex(1.0))
        }
        StyledText(text = "column-only", modifier = Modifier.horizontalAlignment(CrossAxisAlignment.Center))
        StyledText(text = "row ok", modifier = Modifier.verticalAlignment(CrossAxisAlignment.End))
      }
    """)
    val (inRow, box, colOnly, rowOk) = r.children
    assertEquals(mapOf("Flex.value" to d(1.0)), (inRow as DocNode.Widget).modifiers)
    assertIs<DocNode.RawCode>((box as DocNode.Widget).children.single()) // StyledBox gives no RowScope
    assertIs<DocNode.RawCode>(colOnly) // RowScope has no horizontalAlignment
    assertEquals(mapOf("VerticalAlignment.alignment" to PropValue.Lit("i", i = 2)), (rowOk as DocNode.Widget).modifiers)
    // No layout parent at all.
    val top = Recognizer.recognize("S.kt", screen("""StyledText(text = "x", modifier = Modifier.flex(1.0))"""))!!
    assertTrue(containsRaw(top.root))
  }

  @Test fun logicNodesPassTheParentScopeThrough() {
    val r = Recognizer.recognize("S.kt", screen("""
      Row {
        if (b.show) {
          StyledText(text = "a", modifier = Modifier.flex(2.0))
        }
      }
    """).replace("fun S()", "fun S(b: SBindings)"))!!.root
    val cond = r.children.single() as DocNode.Widget
    assertEquals("Condition", cond.type)
    assertEquals(mapOf("Flex.value" to d(2.0)), (cond.children.single() as DocNode.Widget).modifiers)
  }

  @Test fun sizeResolvesByScopeAndArgumentTypes() {
    val r = root("""
      Row {
        StyledText(text = "a", modifier = Modifier.size(12, 12))
        StyledText(text = "b", modifier = Modifier.size(12.dp, Dp(14.0)))
      }
    """)
    val (a, b) = r.children.map { it as DocNode.Widget }
    assertEquals(mapOf("Size.widthDp" to PropValue.Lit("i", i = 12), "Size.heightDp" to PropValue.Lit("i", i = 12)), a.modifiers)
    assertEquals(mapOf("LayoutSize.width" to d(12.0), "LayoutSize.height" to d(14.0)), b.modifiers)
    // Outside a layout scope only material's size(Int, Int) exists.
    val box = root("""
      StyledBox {
        StyledText(text = "a", modifier = Modifier.size(12, 12))
        StyledText(text = "b", modifier = Modifier.size(12.dp, 12.dp))
      }
    """)
    assertIs<DocNode.Widget>(box.children[0])
    assertIs<DocNode.RawCode>(box.children[1])
  }

  @Test fun modifierChainsWithNestedParensWhitespaceAndRepeats() {
    val r = root("""
      Column {
        StyledText(
          text = "a",
          modifier = Modifier
            .padding(8)
            .margin(Margin(horizontal = 4.dp, vertical = Dp(2.0)))
            .width(Dp(100.0)),
        )
        StyledText(text = "b", modifier = Modifier.flex(1.0).flex(2.0))
        StyledText(text = "c", modifier = Modifier.flex(1))
      }
    """)
    val a = r.children[0] as DocNode.Widget
    assertEquals(PropValue.Lit("i", i = 8), a.modifiers["Padding.allDp"])
    assertEquals(d(4.0), a.modifiers["Margin.margin.end"])
    assertEquals(d(2.0), a.modifiers["Margin.margin.bottom"])
    assertEquals(d(100.0), a.modifiers["Width.width"])
    assertIs<DocNode.RawCode>(r.children[1]) // a repeated modifier would be lossy
    assertIs<DocNode.RawCode>(r.children[2]) // flex takes a Double; `1` does not compile
  }

  @Test fun typedArgumentsRoundTripTheExportersOwnOutput() {
    // aspectRatio takes a Float: the exporter writes `1.5f`, which must re-ingest.
    val r = root("""
      Column {
        StyledText(text = "a", modifier = Modifier.aspectRatio(1.5f).padding(2))
      }
    """)
    val a = r.children.single() as DocNode.Widget
    assertEquals(d(1.5), a.modifiers["AspectRatio.ratio"])
    val out = exportKotlin(UiDocument("_", r, dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree())
    assertTrue("Modifier.aspectRatio(1.5f).padding(2)" in out, out)
    assertEquals(stripHandles(r), stripHandles(Recognizer.recognize("S.kt", out)!!.root))
  }

  @Test fun exportPrintsTheShortestExactMarginAndRoundTrips() {
    val rc = Recognizer.recognizeComponent("StatsCard.kt", statsCard)!!
    val doc = UiDocument("_", rc.root!!, dev.keliver.portal.document.Contract(), 0, 0)
    val exported = exportKotlin(doc.toWidgetTree(), functionName = "StatsCard")
    assertTrue("margin = Margin(horizontal = 16.dp, vertical = 8.dp)," in exported, exported)
    assertTrue("modifier = Modifier.flex(1.0).margin(Margin(all = 4.dp))," in exported, exported)
    assertTrue("import dev.keliver.ui.Margin\n" in exported, exported)
    assertTrue("import dev.keliver.ui.dp\n" in exported, exported)
    // Scoped modifiers are scope members: no import.
    assertFalse("import dev.keliver.layout.compose.flex" in exported, exported)
    assertFalse("import dev.keliver.layout.compose.margin" in exported, exported)

    val again = Recognizer.recognize("StatsCard.kt", exported)!!
    assertFalse(containsRaw(again.root), exported)
    assertEquals(stripHandles(doc.root), stripHandles(again.root))
    // ...and the export is a fixed point.
    val doc2 = UiDocument("_", again.root, dev.keliver.portal.document.Contract(), 0, 0)
    assertEquals(exported, exportKotlin(doc2.toWidgetTree(), functionName = "StatsCard"))
  }

  @Test fun everyMarginShapeExportsItsShortestForm() {
    fun export(s: Double, e: Double, t: Double, b: Double): String {
      val row = DocNode.Widget(
        dev.keliver.portal.document.Handle(1), "Row",
        props = mapOf("margin.start" to d(s), "margin.end" to d(e), "margin.top" to d(t), "margin.bottom" to d(b)),
      )
      val out = exportKotlin(UiDocument("_", row, dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree())
      val reparsed = Recognizer.recognize("S.kt", out)!!.root
      assertContentEquals(doubleArrayOf(s, e, t, b), sidesOf(reparsed.props, listOf("start", "end", "top", "bottom").map { "margin.$it" }))
      return Regex("margin = (.*),").find(out)!!.groupValues[1]
    }
    assertEquals("Margin.Zero", export(0.0, 0.0, 0.0, 0.0))
    assertEquals("Margin(all = 8.dp)", export(8.0, 8.0, 8.0, 8.0))
    assertEquals("Margin(horizontal = 16.dp, vertical = 4.5.dp)", export(16.0, 16.0, 4.5, 4.5))
    assertEquals("Margin(horizontal = 16.dp)", export(16.0, 16.0, 0.0, 0.0))
    assertEquals("Margin(vertical = 2.dp)", export(0.0, 0.0, 2.0, 2.0))
    assertEquals("Margin(start = 16.dp, bottom = 8.dp)", export(16.0, 0.0, 0.0, 8.0))
    assertEquals("Margin(start = 1.dp, end = 2.dp, top = 3.dp, bottom = 4.dp)", export(1.0, 2.0, 3.0, 4.0))
    // Absent sides: no margin argument at all.
    val bare = exportKotlin(UiDocument("_", DocNode.Widget(dev.keliver.portal.document.Handle(1), "Row"), dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree())
    assertFalse("margin =" in bare, bare)
  }

  // ── Surgical write-back ────────────────────────────────────────────────

  private val layoutScreen = """
    import androidx.compose.runtime.Composable
    import dev.keliver.Modifier
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText
    import dev.keliver.ui.Margin
    import dev.keliver.ui.dp

    @Composable
    fun Stats() {
      Row(margin = Margin(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)) {
        // Left column.
        Column(modifier = Modifier.flex(1.0)) {
          StyledText(text = "a")
        }
        Column(modifier = Modifier.flex(1.0).margin(Margin(all = 4.dp))) { StyledText(text = "b") }
      }
    }
  """.trimIndent()

  private fun layoutDoc(): UiDocument {
    val rec = Recognizer.recognize("Stats.kt", layoutScreen)!!
    return UiDocument("stats", rec.root, rec.contract, version = 0, nextHandle = 0)
  }

  private fun child(doc: UiDocument, i: Int) = (doc.root as DocNode.Widget).children[i] as DocNode.Widget

  @Test fun changingOneMarginSideTouchesOnlyThatSide() {
    val doc = layoutDoc()
    val target = doc.apply(DocOp.SetProp(doc.root.handle, "margin.top", d(10.0))).doc
    val merged = WriteBack.merge(layoutScreen, target)
    assertEquals(layoutScreen.replace("top = 8.dp", "top = 10.dp"), merged)
  }

  @Test fun changingOneFlexValueTouchesOnlyThatArgument() {
    val doc = layoutDoc()
    val second = child(doc, 1)
    val target = doc.apply(DocOp.SetModifier(second.handle, "Flex.value", d(2.0))).doc
    val merged = WriteBack.merge(layoutScreen, target)
    assertEquals(
      layoutScreen.replace("Modifier.flex(1.0).margin(", "Modifier.flex(2.0).margin("),
      merged,
    )
  }

  @Test fun marginModifierEditsKeepTheAuthorsFormWhenTheyCan() {
    val doc = layoutDoc()
    val second = child(doc, 1)
    // All four sides move together: `all = X` stays.
    var target = doc
    for (side in listOf("start", "end", "top", "bottom")) {
      target = target.apply(DocOp.SetModifier(second.handle, "Margin.margin.$side", d(6.0))).doc
    }
    assertEquals(
      layoutScreen.replace("Margin(all = 4.dp)", "Margin(all = 6.dp)"),
      WriteBack.merge(layoutScreen, target),
    )
    // One side only: `all = X` can't say it -> just that Margin(...) becomes canonical.
    val oneSide = doc.apply(DocOp.SetModifier(second.handle, "Margin.margin.start", d(6.0))).doc
    assertEquals(
      layoutScreen.replace("Margin(all = 4.dp)", "Margin(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)"),
      WriteBack.merge(layoutScreen, oneSide),
    )
  }

  @Test fun addingAScopedModifierReplacesOnlyTheModifierExpression() {
    val doc = layoutDoc()
    val first = child(doc, 0)
    val target = doc.apply(DocOp.SetModifier(first.handle, "Grow.value", d(1.0))).doc
    val merged = WriteBack.merge(layoutScreen, target)
    assertEquals(
      layoutScreen.replace(
        "Column(modifier = Modifier.flex(1.0)) {",
        "Column(modifier = Modifier.flex(1.0).grow(1.0)) {",
      ),
      merged,
    )
    val reread = Recognizer.recognize("Stats.kt", merged!!)!!
    assertFalse(containsRaw(reread.root))
  }

  private fun stripHandles(n: DocNode): String = when (n) {
    is DocNode.RawCode -> "RAW(${n.text.trim()})"
    is DocNode.Widget -> "${n.type}${n.props.toSortedMap()}${n.modifiers.toSortedMap()}[${n.children.joinToString { stripHandles(it) }}]"
  }

  // ── Review follow-ups ──────────────────────────────────────────────────

  @Test fun commentsBetweenChainLinksStayGrammar() {
    val r = root("""
      Row {
        StyledText(
          text = "a",
          modifier = Modifier.padding(8)
            // why the weight
            .flex(1.0) /* end */,
        )
      }
    """)
    val a = r.children.single() as DocNode.Widget
    assertEquals(mapOf("Padding.allDp" to PropValue.Lit("i", i = 8), "Flex.value" to d(1.0)), a.modifiers)
  }

  @Test fun exportKeepsAuthorModifierOrder() {
    val r = root("""
      Row {
        StyledText(text = "a", modifier = Modifier.margin(Margin(all = 4.dp)).padding(8).flex(1.0))
      }
    """)
    val out = exportKotlin(UiDocument("_", r, dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree())
    assertTrue("modifier = Modifier.margin(Margin(all = 4.dp)).padding(8).flex(1.0)," in out, out)
  }

  @Test fun exportImportsOnlyWhatTheBodyWrites() {
    val out = exportKotlin(UiDocument("_", root("""Row {}"""), dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree())
    assertTrue("import dev.keliver.layout.compose.Row\n" in out, out)
    for (unused in listOf("Margin", "ui.dp", "Constraint", "CrossAxisAlignment", "Overflow", "Modifier")) {
      assertFalse("import dev.keliver.$unused\n" in out || Regex("import [\\w.]*\\.$unused\n").containsMatchIn(out), out)
    }
  }

  @Test fun numbersNeverUseExponentNotation() {
    assertEquals("0.0001", dev.keliver.portal.kotlinDoubleLiteral(1.0E-4))
    assertEquals("16.0", dev.keliver.portal.kotlinDoubleLiteral(16.0))
    assertEquals("16.5", dev.keliver.portal.kotlinDoubleLiteral(16.5))
    assertEquals("100000000000000000000.0", dev.keliver.portal.kotlinDoubleLiteral(1.0E20))
    assertEquals("0.0000001.dp", dev.keliver.portal.kotlinDpLiteral(1.0E-7))
    val row = DocNode.Widget(
      dev.keliver.portal.document.Handle(1), "Row",
      children = listOf(DocNode.Widget(dev.keliver.portal.document.Handle(2), "StyledText",
        props = mapOf("text" to PropValue.Lit("s", s = "a")),
        modifiers = mapOf("Flex.value" to d(1.0E-4), "Margin.margin.start" to d(1.0E-4)))),
    )
    val out = exportKotlin(UiDocument("_", row, dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree())
    assertFalse("E-" in out, out)
    val back = (Recognizer.recognize("S.kt", out)!!.root.children.single() as DocNode.Widget).modifiers
    assertEquals(d(1.0E-4), back["Flex.value"])
    assertEquals(d(1.0E-4), back["Margin.margin.start"])
  }

  @Test fun negativeDpValuesNeverReachTheFile() {
    val doc = layoutDoc()
    // The document clamps a Dp (Margin side / Dp modifier arg) at 0.
    val t1 = doc.apply(DocOp.SetProp(doc.root.handle, "margin.top", d(-4.0))).doc
    assertEquals(d(0.0), (t1.root as DocNode.Widget).props["margin.top"])
    val merged = WriteBack.merge(layoutScreen, t1)!!
    assertEquals(layoutScreen.replace("top = 8.dp", "top = 0.dp"), merged)
    // Even a tree that bypassed the document never prints a negative Dp.
    assertEquals("0.dp", MarginSyntax.dpLike("8.dp", -4.0))
    assertEquals("Dp(0.0)", MarginSyntax.dpLike("Dp(8.0)", -4.0))
    val raw = DocNode.Widget(
      dev.keliver.portal.document.Handle(1), "Row",
      props = mapOf("margin.start" to d(-3.0)),
      children = listOf(DocNode.Widget(dev.keliver.portal.document.Handle(2), "Spacer",
        props = mapOf("width" to d(-2.0)), modifiers = mapOf("Width.width" to d(-1.0)))),
    )
    val out = exportKotlin(UiDocument("_", raw, dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree())
    assertFalse("-" in out.substringAfter("fun "), out)
    assertTrue("width = Dp(0.0)" in out && "Modifier.width(Dp(0.0))" in out && "margin = Margin.Zero" in out, out)
  }

  @Test fun aScopedModifierUnderANonScopeParentIsNotExported() {
    // B1 safety net: a tree that bypassed the document still exports compiling code.
    val tree = DocNode.Widget(
      dev.keliver.portal.document.Handle(1), "Column",
      children = listOf(
        DocNode.Widget(dev.keliver.portal.document.Handle(2), "StyledBox", children = listOf(
          DocNode.Widget(dev.keliver.portal.document.Handle(3), "StyledText",
            props = mapOf("text" to PropValue.Lit("s", s = "x")),
            modifiers = mapOf("Flex.value" to d(1.0), "Padding.allDp" to PropValue.Lit("i", i = 2))),
        )),
        DocNode.Widget(dev.keliver.portal.document.Handle(4), "StyledText",
          props = mapOf("text" to PropValue.Lit("s", s = "y")), modifiers = mapOf("Flex.value" to d(2.0))),
      ),
      modifiers = mapOf("Flex.value" to d(3.0)), // root: no layout parent
    )
    val out = exportKotlin(UiDocument("_", tree, dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree())
    assertTrue("modifier = Modifier.padding(2)," in out, out)
    assertTrue("modifier = Modifier.flex(2.0)," in out, out) // valid under the Column
    assertFalse("flex(1.0)" in out || "flex(3.0)" in out, out)
  }

  @Test fun exportAfterMovingUnderANonScopeParentDropsTheModifier() {
    val src = screen("""
      Column {
        Row {
          StyledText(text = "a", modifier = Modifier.flex(1.0).padding(4))
        }
        StyledBox {
        }
      }
    """)
    val rec = Recognizer.recognize("S.kt", src)!!
    val doc = UiDocument("s", rec.root, rec.contract, 0, 0)
    val col = doc.root as DocNode.Widget
    val row = col.children[0] as DocNode.Widget
    val box = col.children[1] as DocNode.Widget
    val text = row.children.single()
    val moved = doc.apply(DocOp.MoveNode(text.handle, box.handle, null))
    val movedText = ((moved.doc.root as DocNode.Widget).children[1] as DocNode.Widget).children.single() as DocNode.Widget
    assertEquals(mapOf("Padding.allDp" to PropValue.Lit("i", i = 4)), movedText.modifiers) // stripped in the doc
    // Whatever the relay saves — surgical merge or full export — carries no flex.
    // The relay's path here is the SURGICAL merge (delete + insert): it must carry no flex.
    val merged = WriteBack.merge(src, moved.doc)
    assertNotNull(merged)
    assertFalse("flex(" in merged, merged)
    assertTrue("Modifier.padding(4)" in merged, merged)
    val out = exportKotlin(moved.doc.toWidgetTree(), functionName = "S")
    assertTrue("modifier = Modifier.padding(4)," in out, out)
    assertFalse("flex(" in out, out)
    // Undo restores the modifier exactly.
    val undone = moved.doc.apply(moved.inverse!!).doc
    assertEquals(stripHandles(doc.root), stripHandles(undone.root))
  }

  @Test fun surgicalDpModifierEditKeepsTheDpSpelling() {
    val src = "import dev.keliver.Modifier\nimport dev.keliver.ui.Dp\nimport dev.keliver.ui.dp\n" + screen("""
      Row {
        StyledText(text = "a", modifier = Modifier.width(12.dp).height(Dp(10.0)))
      }
    """)
    val rec = Recognizer.recognize("S.kt", src)!!
    val doc = UiDocument("s", rec.root, rec.contract, 0, 0)
    val text = (doc.root as DocNode.Widget).children.single()
    val t = doc.apply(DocOp.SetModifier(text.handle, "Width.width", d(14.5))).doc
      .apply(DocOp.SetModifier(text.handle, "Height.height", d(11.0))).doc
    assertEquals(src.replace("width(12.dp)", "width(14.5.dp)").replace("height(Dp(10.0))", "height(Dp(11.0))"), WriteBack.merge(src, t))
  }

  @Test fun editingAMarginZeroSourceWritesTheCanonicalMarginAndItsImport() {
    val src = """
      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Row
      import dev.keliver.ui.Margin

      @Composable
      fun S() {
        Row(margin = Margin.Zero) {}
      }
    """.trimIndent() + "\n"
    val rec = Recognizer.recognize("S.kt", src)!!
    val doc = UiDocument("s", rec.root, rec.contract, 0, 0)
    val t = doc.apply(DocOp.SetProp(doc.root.handle, "margin.top", d(8.0))).doc
    val merged = WriteBack.merge(src, t)!!
    assertTrue("Row(margin = Margin(top = 8.dp)) {}" in merged, merged)
    assertTrue("import dev.keliver.ui.Margin\nimport dev.keliver.ui.dp\n" in merged, merged)
    assertFalse(containsRaw(Recognizer.recognize("S.kt", merged)!!.root))
  }

  @Test fun writeBackAddsTheImportsItsTextNeedsAndOnlyThose() {
    val src = """
      import androidx.compose.runtime.Composable
      import dev.keliver.Modifier
      import dev.keliver.layout.compose.Column
      import dev.keliver.layout.compose.Row
      import dev.keliver.material.compose.StyledText
      import dev.keliver.ui.Dp
      import dev.keliver.ui.Margin

      @Composable
      fun S() {
        Column {
          Row(margin = Margin(all = Dp(1.0))) {}
          StyledText(text = "a", modifier = Modifier.margin(Margin(all = Dp(4.0))))
          Row(margin = Margin(all = Dp(2.0))) {
            StyledText(text = "b")
          }
        }
      }
    """.trimIndent() + "\n"
    val rec = Recognizer.recognize("S.kt", src)!!
    val doc = UiDocument("s", rec.root, rec.contract, 0, 0)
    val col = doc.root as DocNode.Widget
    val row = col.children[0]
    val text = col.children[1]
    val inner = (col.children[2] as DocNode.Widget).children.single()
    val t = doc
      .apply(DocOp.SetModifier(text.handle, "Margin.margin.start", d(6.0))).doc // all= can't say it -> canonical .dp
      .apply(DocOp.SetModifier(text.handle, "HorizontalAlignment.alignment", PropValue.Lit("i", i = 1))).doc
      .apply(DocOp.SetProp(row.handle, "width", PropValue.Lit("i", i = 1))).doc // a new arg: Constraint
      .apply(DocOp.SetModifier(inner.handle, "LayoutSize.width", d(12.0))).doc // scoped size(Dp, Dp)
      .apply(DocOp.SetModifier(inner.handle, "LayoutSize.height", d(12.0))).doc
    val merged = WriteBack.merge(src, t)!!
    for (fq in listOf("dev.keliver.layout.api.Constraint", "dev.keliver.layout.api.CrossAxisAlignment", "dev.keliver.ui.dp")) {
      assertEquals(1, Regex("^import ${Regex.escape(fq)}$", RegexOption.MULTILINE).findAll(merged).count(), merged)
    }
    assertFalse("import dev.keliver.layout.api.Overflow" in merged, merged)
    // The scoped LayoutSize is a scope member: never material's top-level `size`.
    assertTrue(".size(Dp(12.0), Dp(12.0))" in merged, merged)
    assertFalse("import dev.keliver.material.compose.size" in merged, merged)
    assertFalse("import dev.keliver.layout.api.MainAxisAlignment" in merged, merged)
    // Kept sorted among the existing imports.
    val imports = merged.lines().filter { it.startsWith("import ") }
    assertEquals(imports.sorted(), imports)
    val reread = Recognizer.recognize("S.kt", merged)!!
    assertFalse(containsRaw(reread.root), merged)

    // A star import already covers it; an existing same-name import is never shadowed.
    assertEquals("import dev.keliver.ui.*\nx", WriteBack.ensureImports("import dev.keliver.ui.*\nx", setOf("dev.keliver.ui.dp")))
    assertEquals("import foo.dp\nx", WriteBack.ensureImports("import foo.dp\nx", setOf("dev.keliver.ui.dp")))
    // Two owners of one simple name in a single merge: neither is written (no guessing which `C` wins).
    assertEquals(
      "import a.A\nimport z.D\nx",
      WriteBack.ensureImports("import a.A\nx", setOf("x.C", "q.C", "z.D")),
    )
  }

  @Test fun ensureImportsHandlesCommentsSemicolonsBackticksAndFileAnnotations() {
    // Existing imports written with a trailing comment / semicolon / backticks are recognized.
    for (existing in listOf(
      "import dev.keliver.ui.dp // spacing",
      "import dev.keliver.ui.dp;",
      "import dev.keliver.`ui`.dp",
      "import dev.keliver.ui.dp /* why */",
    )) {
      val text = "$existing\n\nfun f() {}\n"
      assertEquals(text, WriteBack.ensureImports(text, setOf("dev.keliver.ui.dp")), existing)
    }
    // No package, no imports: after the @file: annotations, set off by blank lines.
    val annotated = "@file:Suppress(\"x\")\n\nfun f() {}\n"
    assertEquals(
      "@file:Suppress(\"x\")\n\nimport dev.keliver.ui.dp\n\nfun f() {}\n",
      WriteBack.ensureImports(annotated, setOf("dev.keliver.ui.dp")),
    )
    // A package and no imports: a blank line after `package`, one before the code.
    assertEquals(
      "package a.b\n\nimport dev.keliver.ui.dp\n\nfun f() {}\n",
      WriteBack.ensureImports("package a.b\n\nfun f() {}\n", setOf("dev.keliver.ui.dp")),
    )
    assertEquals(
      "package a.b\n\nimport dev.keliver.ui.dp\n\nfun f() {}\n",
      WriteBack.ensureImports("package a.b\nfun f() {}\n", setOf("dev.keliver.ui.dp")),
    )
  }

  @Test fun componentInstancesNeverExportModifiersAsNamedArgs() {
    fun spec(withModifier: Boolean) = dev.keliver.portal.ComponentSpec(
      name = "Tile",
      props = listOf(dev.keliver.portal.PropSpec("weight", dev.keliver.portal.PropKind.Double, "Weight")),
      events = emptyList(),
      paramTypes = mapOf("weight" to "Double") + (if (withModifier) mapOf("modifier" to "Modifier") else emptyMap()),
    )
    val tree = DocNode.Widget(
      dev.keliver.portal.document.Handle(1), "Row",
      children = listOf(DocNode.Widget(dev.keliver.portal.document.Handle(2), "Tile",
        props = mapOf("weight" to d(1.0E-4)), modifiers = mapOf("Flex.value" to d(1.0)))),
    )
    val wire = UiDocument("_", tree, dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree()
    val without = exportKotlin(wire, components = dev.keliver.portal.MapComponentRegistry(listOf(spec(false))))
    assertFalse("mod." in without, without)
    assertFalse("flex(" in without, without)
    assertTrue("weight = 0.0001," in without, without) // N3: no exponent
    val with = exportKotlin(wire, components = dev.keliver.portal.MapComponentRegistry(listOf(spec(true))))
    assertTrue("Tile(\n      modifier = Modifier.flex(1.0),\n      weight = 0.0001," in with, with)
    assertTrue("import dev.keliver.Modifier\n" in with, with)
    assertFalse("mod." in with, with)
  }

  @Test fun layoutSizeExportImportsNoMaterialSize() {
    val r = root("""
      Row {
        StyledText(text = "a", modifier = Modifier.size(12.dp, 12.dp))
      }
    """)
    val out = exportKotlin(UiDocument("_", r, dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree())
    assertTrue("Modifier.size(Dp(12.0), Dp(12.0))" in out, out)
    assertFalse("import dev.keliver.material.compose.size" in out, out)
    assertTrue("import dev.keliver.ui.Dp\n" in out, out)
  }
}
