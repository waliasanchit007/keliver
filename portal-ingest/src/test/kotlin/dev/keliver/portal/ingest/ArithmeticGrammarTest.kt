package dev.keliver.portal.ingest

import dev.keliver.portal.Arith
import dev.keliver.portal.ArithOp
import dev.keliver.portal.Bind
import dev.keliver.portal.Expansion
import dev.keliver.portal.IntDp
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.arithValue
import dev.keliver.portal.dbl
import dev.keliver.portal.document.DP_CTOR_FORM
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.expandForPreview
import dev.keliver.portal.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K7: Int arithmetic (`b.size * 2 / 5`, `C.Base + b.delta`) where source takes
 * an Int, and an Int used as a Dp (`Dp(b.gap.toDouble())`, `(b.gap + 4).dp`)
 * on a Dp prop, are grammar in screens and component bodies — recognized,
 * written back surgically (a no-op is byte-identical; an edit of one operand
 * rewrites only that operand), exported, typed in the contract and previewed.
 */
class ArithmeticGrammarTest {
  private val tokensKt = """
    package app.components

    object C {
      const val Base = 12
      const val Gap = 8
      const val Name = "n"
      const val Ratio = 1.5
    }
  """.trimIndent()
  private val tokens = TokenTable.scan(listOf("Tokens.kt" to tokensKt))
  private val file = "app/screens/HomeScreen.kt"

  private val src = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.C
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Spacer
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText
    import dev.keliver.ui.Dp
    import dev.keliver.ui.dp

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        // a comment that must survive
        StyledText(text = "a", fontSize = b.size * 2 / 5)
        StyledBox(cornerRadiusDp = (b.size) / 2, colorArgb = C.Base + b.delta) {
          StyledText(text = "b", fontSize = b.size-1)
        }
        Spacer(height = Dp(b.gap.toDouble()))
        Spacer(width = (b.gap + 4).dp)
        StyledText(text = "c", fontSize = if (b.big) b.size - (b.delta - 3) else 12)
      }
    }

    interface HomeScreenBindings {
      val size: Int
      val delta: kotlin.Int
      val gap: Int
      val big: Boolean
      val label: String
      val ratio: Double
      val maybe: Int?
    }
  """.trimIndent()

  private fun rec(text: String = src) = assertNotNull(Recognizer.recognize(file, text, tokens = tokens))
  private fun doc(text: String = src): UiDocument = rec(text).let { UiDocument("home", it.root, it.contract, 0, 1000) }
  private fun kids(d: UiDocument) = (d.root as DocNode.Widget).children
  private fun w(d: UiDocument, i: Int) = kids(d)[i] as DocNode.Widget
  private fun a(op: String, l: PropValue, r: PropValue) = PropValue.Arith(op, l, r)
  private fun b(f: String) = PropValue.Bind(f)
  private val base = PropValue.Token("C.Base", lit(12), "app.components.C")

  private fun merged(text: String, d: UiDocument): String = assertNotNull(
    WriteBack.merge(text, d, tokens = tokens, fileName = file, base = doc(text)),
    (WriteBack.attempt(text, d, tokens = tokens, fileName = file, base = doc(text)) as? WriteBack.Outcome.NotSurgical)?.reason,
  )

  @Test fun everyFormIsRecognizedWithItsTree() {
    val d = doc()
    assertTrue(kids(d).none { it is DocNode.RawCode }, rec().rawReasons.toString())
    assertEquals(a("/", a("*", b("size"), lit(2)), lit(5)), w(d, 0).props["fontSize"])
    assertEquals(a("/", b("size"), lit(2)), w(d, 1).props["cornerRadiusDp"])
    assertEquals(a("+", base, b("delta")), w(d, 1).props["colorArgb"])
    assertEquals(a("-", b("size"), lit(1)), (w(d, 1).children[0] as DocNode.Widget).props["fontSize"])
    assertEquals(PropValue.IntDp(b("gap"), DP_CTOR_FORM), w(d, 2).props["height"])
    assertEquals(PropValue.IntDp(a("+", b("gap"), lit(4))), w(d, 3).props["width"])
    val c = assertIs<PropValue.Conditional>(w(d, 4).props["fontSize"])
    assertEquals(a("-", b("size"), a("-", b("delta"), lit(3))), c.whenTrue)
    assertEquals(lit(12), c.whenFalse)
  }

  @Test fun whatIsNotGrammarStaysCodeAndSaysWhy() {
    fun reason(arg: String, iface: String = ""): String {
      val text = src.replace("fontSize = b.size * 2 / 5)", "fontSize = $arg)").replace("val maybe: Int?", "val maybe: Int?\n  $iface")
      val r = rec(text)
      val raw = assertIs<DocNode.RawCode>((r.root as DocNode.Widget).children[0], "`$arg` should not be grammar")
      assertTrue(raw.text.contains(arg))
      return r.rawReasons.first()
    }
    fun check(arg: String, want: String) = reason(arg).let { assertTrue(want in it, "$arg: $it") }
    check("b.size / b.delta", "non-zero Int literal")
    check("b.size % 0", "non-zero Int literal")
    check("b.size / C.Gap", "non-zero Int literal")
    check("4 * 2", "literals only")
    check("-b.size", "unary")
    check("b.size + -2147483648", "decimal")
    check("b.size + 3L", "decimal")
    check("b.size + 0x10", "decimal")
    check("b.label / 2", "declared String")
    check("b.ratio * 2", "declared Double")
    check("b.maybe + 1", "declared Int?")
    check("C.Name + 1", "not an Int")
    assertTrue("C.Ratio" in reason("C.Ratio * 2") || "not an Int" in reason("C.Ratio * 2"))
    check("maxOf(b.size, 1) + 1", "not an Int operand")
    check("b.size /* half */ / 2", "comment")
    // A modifier argument takes literals and tokens only.
    val mod = rec(src.replace("fontSize = b.size * 2 / 5)", "modifier = Modifier.padding(b.size / 2))").replace("import dev.keliver.ui.dp", "import dev.keliver.ui.dp\nimport dev.keliver.Modifier\nimport dev.keliver.material.compose.padding"))
    assertIs<DocNode.RawCode>((mod.root as DocNode.Widget).children[0])
    // An Int as a Dp reads only an Int.
    val dbl = rec(src.replace("Dp(b.gap.toDouble())", "Dp(b.ratio.toDouble())"))
    assertIs<DocNode.RawCode>((dbl.root as DocNode.Widget).children[2])
    assertTrue("declared Double" in dbl.rawReasons.first(), dbl.rawReasons.toString())
    // An Int-as-Dp of literals only stays as before.
    assertIs<DocNode.RawCode>((rec(src.replace("Dp(b.gap.toDouble())", "Dp(4.toDouble())")).root as DocNode.Widget).children[2])
  }

  @Test fun aTypeIngestCannotReadIsAnIntOperandButNotADp() {
    val text = src.replace("interface HomeScreenBindings {", "interface HomeScreenBindings : app.Base {")
    val d = doc(text)
    assertIs<DocNode.Widget>(kids(d)[0]) // `b.size * 2 / 5`: declared Int here
    val unknown = text.replace("fontSize = b.size * 2 / 5", "fontSize = b.inherited * 2").replace("Dp(b.gap.toDouble())", "Dp(b.inherited.toDouble())")
    val r = rec(unknown)
    assertIs<DocNode.Widget>((r.root as DocNode.Widget).children[0])
    assertIs<DocNode.RawCode>((r.root as DocNode.Widget).children[2])
    assertTrue("not readable" in r.rawReasons.first(), r.rawReasons.toString())
  }

  @Test fun aNoOpIsByteIdenticalCrlfToo() {
    assertEquals(src, merged(src, doc()))
    val crlf = src.replace("\n", "\r\n")
    assertEquals(crlf, merged(crlf, doc(crlf)))
  }

  @Test fun anEditOfOneOperandRewritesOnlyThatOperand() {
    val d = doc()
    fun set(i: Int, k: String, v: PropValue) = d.apply(DocOp.SetProp(w(d, i).handle, k, v)).doc
    assertEquals(src.replace("b.size * 2 / 5", "b.size * 2 / 4"), merged(src, set(0, "fontSize", a("/", a("*", b("size"), lit(2)), lit(4)))))
    assertEquals(src.replace("b.size * 2 / 5", "b.delta * 2 / 5"), merged(src, set(0, "fontSize", a("/", a("*", b("delta"), lit(2)), lit(5)))))
    // The author's parentheses and spacing stay.
    assertEquals(src.replace("(b.size) / 2", "(b.delta) / 2"), merged(src, set(1, "cornerRadiusDp", a("/", b("delta"), lit(2)))))
    assertEquals(src.replace("C.Base + b.delta", "C.Base + 7"), merged(src, set(1, "colorArgb", a("+", base, lit(7)))))
    // `b.size-1` with a negative literal on the right is not a decrement.
    val inner = (w(d, 1).children[0] as DocNode.Widget).handle
    assertEquals(src.replace("b.size-1", "b.size- -3"), merged(src, d.apply(DocOp.SetProp(inner, "fontSize", a("-", b("size"), lit(-3)))).doc))
    // An Int as a Dp keeps its spelling; only the changed operand moves.
    assertEquals(src.replace("Dp(b.gap.toDouble())", "Dp(b.size.toDouble())"), merged(src, set(2, "height", PropValue.IntDp(b("size"), DP_CTOR_FORM))))
    assertEquals(src.replace("(b.gap + 4).dp", "(b.gap + 6).dp"), merged(src, set(3, "width", PropValue.IntDp(a("+", b("gap"), lit(6))))))
    // A branch edit stays inside its branch.
    val c = w(d, 4).props["fontSize"] as PropValue.Conditional
    assertEquals(src.replace("b.size - (b.delta - 3)", "b.size - (b.delta - 2)"),
      merged(src, set(4, "fontSize", c.copy(whenTrue = a("-", b("size"), a("-", b("delta"), lit(2)))))))
  }

  @Test fun anotherShapeOrAPlainValueRewritesTheArgumentAlone() {
    val d = doc()
    fun set(i: Int, k: String, v: PropValue) = d.apply(DocOp.SetProp(w(d, i).handle, k, v)).doc
    assertEquals(src.replace("b.size * 2 / 5", "b.size * (2 + b.delta)"), merged(src, set(0, "fontSize", a("*", b("size"), a("+", lit(2), b("delta"))))))
    assertEquals(src.replace("b.size * 2 / 5", "14"), merged(src, set(0, "fontSize", lit(14))))
    assertEquals(src.replace("b.size * 2 / 5", "b.size"), merged(src, set(0, "fontSize", b("size"))))
    // A Dp literal in place of an Int as a Dp keeps the author's Dp spelling.
    assertEquals(src.replace("(b.gap + 4).dp", "6.dp").replace("Dp(b.gap.toDouble())", "Dp(2.0)"),
      merged(src, d.apply(DocOp.SetProp(w(d, 3).handle, "width", lit(6.0))).doc.let { it.apply(DocOp.SetProp(w(d, 2).handle, "height", lit(2.0))).doc }))
    // A Dp literal becoming an Int as a Dp: written canonically, imports added.
    val plain = src.replace("Spacer(width = (b.gap + 4).dp)", "Spacer(width = 4.dp)").replace("import dev.keliver.ui.dp\n", "")
      .replace("Spacer(height = Dp(b.gap.toDouble()))", "Spacer(height = Dp(2.0))")
    val pd = doc(plain)
    val out = merged(plain, pd.apply(DocOp.SetProp(w(pd, 3).handle, "width", PropValue.IntDp(a("*", b("gap"), lit(2))))).doc)
    assertTrue("Spacer(width = (b.gap * 2).dp)" in out, out)
    assertTrue("import dev.keliver.ui.dp\n" in out, out)
  }

  @Test fun aNewTokenOperandImportsItsObject() {
    val text = src.replace("import app.components.C\n", "").replace(", colorArgb = C.Base + b.delta", "")
    val d = doc(text)
    val out = merged(text, d.apply(DocOp.SetProp(w(d, 0).handle, "fontSize", a("+", b("size"), PropValue.Token("C.Gap", lit(8), "app.components.C")))).doc)
    assertTrue("fontSize = b.size + C.Gap" in out, out)
    assertTrue("import app.components.C\n" in out, out)
  }

  @Test fun theExportReadsBackAsTheSameTree() {
    val d = doc()
    val exported = NodeEmitter.screen(d, "HomeScreen")
    val back = assertNotNull(Recognizer.recognize(file, exported, tokens = tokens))
    assertTrue(WriteBack.sameTree(back.root, d.root), WriteBack.firstDifference(back.root, d.root))
  }

  @Test fun theContractDeclaresANewOperandInt() {
    val text = src.replace("b.size * 2 / 5", "b.columns * 48 + 8")
    val r = rec(text)
    val out = ContractWriteBack.ensure(text, UiDocument("home", r.root, r.contract, 0, 0).toWidgetTree(), "HomeScreen")
    assertTrue("val columns: Int" in out, out)
  }

  // ── Components ────────────────────────────────────────────────────────

  private val avatarKt = """
    package app.components

    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText

    @Composable
    fun Disc(size: Int, label: String, inset: Int = 4) {
      val radius = size / 2
      StyledBox(cornerRadiusDp = radius, colorArgb = C.Base * 2 + inset) {
        StyledText(text = label, fontSize = size * 2 / 5 - inset)
      }
    }
  """.trimIndent()

  private val gapKt = """
    package app.components

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Spacer
    import dev.keliver.ui.Dp

    @Composable
    fun VGap(gap: Int = 8) {
      Spacer(height = Dp(gap.toDouble()))
    }
  """.trimIndent()

  @Test fun aComponentWithArithmeticIsTransparentAndPreviewsItsArguments() {
    val badge = Recognizer.recognizeComponent("app/components/Disc.kt", avatarKt, tokens = tokens)!!.spec
    assertTrue(badge.transparent, badge.diagnostic)
    // The derived local is inlined where it is read.
    assertEquals(Arith(ArithOp.Div, Bind("size"), 2), badge.body!!.props["cornerRadiusDp"])
    val gap = Recognizer.recognizeComponent("app/components/VGap.kt", gapKt, tokens = tokens)!!.spec
    assertTrue(gap.transparent, gap.diagnostic)
    assertEquals(IntDp(Bind("gap"), ctor = true), gap.body!!.props["height"])

    val registry = MapComponentRegistry(listOf(badge, gap))
    fun expand(type: String, vararg args: Pair<String, Any?>) =
      (expandForPreview(WidgetNode(type, mapOf(*args)), registry) as Expansion.Transparent).tree
    val t = expand("Disc", "size" to 48, "label" to "AB")
    assertEquals(24, t.int("cornerRadiusDp"))
    assertEquals(28, t.int("colorArgb"))
    assertEquals(48 * 2 / 5 - 4, t.children[0].int("fontSize"))
    assertEquals(8.0, expand("VGap").dbl("height")) // the default
    assertEquals(3.0, expand("VGap", "gap" to 3).dbl("height"))
    // A screen binding argument is decided when the canvas renders (its mock).
    val bound = expand("Disc", "size" to Bind("s"), "label" to "AB")
    val radius = assertIs<Arith>(bound.props["cornerRadiusDp"])
    assertNull(arithValue(radius))
    assertEquals(21, arithValue(radius) { if (it == Bind("s")) 42 else null })
    // An arithmetic argument is evaluated inside the body's expression.
    val nested = expand("Disc", "size" to Arith(ArithOp.Times, Token("C.Gap", 8), 4), "label" to "AB")
    assertEquals(16, nested.int("cornerRadiusDp"))
  }

  @Test fun anInstanceArgumentMayBeArithmetic() {
    val badge = Recognizer.recognizeComponent("app/components/Disc.kt", avatarKt, tokens = tokens)!!.spec
    val registry = MapComponentRegistry(listOf(badge))
    val text = src.replace("StyledText(text = \"a\", fontSize = b.size * 2 / 5)", "Disc(size = b.size * 2, label = \"x\")")
      .replace("import app.components.C\n", "import app.components.C\nimport app.components.Disc\n")
    val r = assertNotNull(Recognizer.recognize(file, text, registry, tokens))
    val inst = (r.root as DocNode.Widget).children[0] as DocNode.Widget
    assertEquals("Disc", inst.type)
    assertEquals(a("*", b("size"), lit(2)), inst.props["size"])
    val d = UiDocument("home", r.root, r.contract, 0, 1000)
    assertEquals(text, WriteBack.merge(text, d, registry, tokens, file, d))
    val edited = d.apply(DocOp.SetProp(inst.handle, "size", a("*", b("size"), lit(3)))).doc
    assertEquals(text.replace("b.size * 2, label", "b.size * 3, label"), WriteBack.merge(text, edited, registry, tokens, file, d))
  }

  @Test fun whatKeepsAComponentOpaqueSaysWhy() {
    fun diag(body: String, params: String = "size: Int"): String {
      val kt = "package app.components\n\nimport androidx.compose.runtime.Composable\nimport dev.keliver.material.compose.StyledBox\n\n" +
        "@Composable\nfun Thing($params) {\n$body\n}\n"
      val spec = Recognizer.recognizeComponent("app/components/Thing.kt", kt, tokens = tokens)!!.spec
      assertTrue(!spec.transparent, "expected opaque: $body")
      return spec.diagnostic!!
    }
    assertTrue("literal" in diag("  val pad = 8\n  StyledBox(cornerRadiusDp = pad + size) {}"))
    assertTrue("locals stay flat" in diag("  val r = size / 2\n  val q = r + 1\n  StyledBox(cornerRadiusDp = q) {}"))
    assertTrue("before its definition" in diag("  StyledBox(cornerRadiusDp = r + 1) {}\n  val r = size / 2"))
    assertTrue("declared Double" in diag("  StyledBox(cornerRadiusDp = size * 2) {}", "size: Double"))
    assertTrue("declared Int?" in diag("  StyledBox(cornerRadiusDp = size * 2) {}", "size: Int?"))
    assertTrue("Boolean local" in diag("  val on = size == 2\n  StyledBox(cornerRadiusDp = on + 1) {}"))
    assertTrue("not a derived local" in diag("  val t = \"x\".length\n  StyledBox(cornerRadiusDp = size) {}"))
  }
}
