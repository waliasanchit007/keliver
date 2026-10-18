import dev.keliver.portal.Bind
import dev.keliver.portal.ListOf
import dev.keliver.portal.PropKind
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.canonicalValue
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K4: the panel's text for a template and a list, and what it sends back. */
class EditorTemplatesTest {
  private val tokens = parseTokens(
    """
    [{"ref":"C.Max","kind":"Int","value":5,"import":"app.C","ambiguous":false},
     {"ref":"C.Brand","kind":"String","value":"Acme","import":"app.C","ambiguous":false},
     {"ref":"D.Max","kind":"Int","value":1,"import":"a.D","ambiguous":true},
     {"ref":"C.Ratio","kind":"Double","value":1.5,"import":"app.C","ambiguous":false}]
    """.trimIndent(),
  )
  private val known = knownTokens(null, tokens)
  private val max = PropValue.Token("C.Max", lit(5), "app.C")

  @Test fun aTemplateRoundTripsThroughThePanelText() {
    val t = Template(listOf("Rating {x}: ", Bind("rating"), "/", Token("C.Max", 5, "app.C"), " in ", Bind("item.city")))
    val text = templateSource(t)
    assertEquals("Rating {{x}}: {rating}/{◆C.Max} in {item.city}", text)
    assertEquals(
      PropValue.Template(listOf(lit("Rating {x}: "), PropValue.Bind("rating"), lit("/"), max, lit(" in "), PropValue.Bind("item.city"))),
      parseTemplateSource(text, known).value,
    )
  }

  @Test fun textWithoutAReferenceIsAPlainString() {
    assertEquals(lit("just {text}"), parseTemplateSource("just {{text}}", known).value)
  }

  @Test fun invalidTextIsNeverSent() {
    assertNotNull(parseTemplateSource("a {b", known).error)
    assertNotNull(parseTemplateSource("a } b", known).error)
    assertNotNull(parseTemplateSource("{b.count + 1}", known).error)
    assertNotNull(parseTemplateSource("{◆C.Nope}", known).error)
    assertNotNull(parseTemplateSource("{◆D.Max}", known).error) // ambiguous: not offered
    assertNull(parseTemplateSource("{◆C.Brand}!", known).error)
  }

  @Test fun aTokenTheValueAlreadyHoldsStaysNameable() {
    val t = Template(listOf(Token("X.Old", "o", "q.X"), "!"))
    assertEquals(
      PropValue.Template(listOf(PropValue.Token("X.Old", lit("o"), "q.X"), lit("!?"))),
      parseTemplateSource("{◆X.Old}!?", knownTokens(t, tokens)).value,
    )
  }

  @Test fun aListRoundTripsThroughThePanelText() {
    val ints = ListOf("li", listOf(16777215, Token("C.Max", 5, "app.C"), Bind("accent")))
    assertEquals("16777215, ◆C.Max, @accent", listSource(ints, PropKind.IntList))
    assertEquals(PropValue.ListOf("li", listOf(lit(16777215), max, PropValue.Bind("accent"))), parseListSource("16777215, ◆C.Max, @accent", PropKind.IntList, known).value)
    assertEquals(PropValue.Lit("li", li = listOf(-1, 1)), parseListSource("#FFFFFF, 1", PropKind.IntList, known).value)
    assertEquals(PropValue.ListOf("ls", listOf(lit("All"), PropValue.Token("C.Brand", lit("Acme"), "app.C"))), parseListSource("All|◆C.Brand", PropKind.StringList, known).value)
    assertEquals("a|b", listSource(listOf("a", "b"), PropKind.StringList))
    // A token of another kind, or a word that is no element, is refused.
    assertNotNull(parseListSource("◆C.Brand", PropKind.IntList, known).error)
    assertNotNull(parseListSource("1, two", PropKind.IntList, known).error)
  }

  @Test fun aDoubleTokenIsNeverOffered() {
    // The device (Kotlin/JS) prints 1.5 as "1.5" but 1.0 as "1", the preview "1.0": no template reads a Double.
    assertNull(known["C.Ratio"])
    assertNotNull(parseTemplateSource("x {◆C.Ratio}", known).error)
    assertTrue(templateTokenOffered(tokens.first { it.ref == "C.Max" }))
    assertFalse(templateTokenOffered(tokens.first { it.ref == "C.Ratio" }))
  }

  @Test fun aListBoundAsAWholeKeepsItsBindingRow() {
    assertFalse(editsAsList(PropKind.StringList, Bind("options")))
    assertFalse(editsAsList(PropKind.IntList, Bind("colors")))
    assertTrue(editsAsList(PropKind.StringList, listOf("a")))
    assertTrue(editsAsList(PropKind.IntList, ListOf("li", listOf(Bind("x")))))
    assertTrue(editsAsList(PropKind.IntList, null))
    assertFalse(editsAsList(PropKind.Text, "x"))
  }

  private val tricky = listOf(
    "", " ", "  x", "x  ", "@a", "◆C.Max", "|", "a|b", "\\", "\\@", "\\|", "\"", "\"q\"", "a\"", "\" spaced \"",
    "@", "◆", "{x}", "\t", "a\\|b", "#FF0000", "-1", "x, y", "é\uD840\uDC00", "\"\"", " \" ", "\\\"",
  )

  private fun stringRoundTrip(items: List<Any>) {
    val v = ListOf("ls", items)
    val text = listSource(v, PropKind.StringList)
    val expected = canonicalValue(
      PropValue.ListOf("ls", items.map { e ->
        when (e) {
          is String -> lit(e)
          is Bind -> PropValue.Bind(e.field)
          is Token -> PropValue.Token(e.ref, lit(e.value), e.importFq)
          else -> error("$e")
        }
      }),
    )
    val parsed = parseListSource(text, PropKind.StringList, known)
    assertNull(parsed.error, "$items as `$text`: ${parsed.error}")
    assertEquals(expected, parsed.value, "$items as `$text`")
  }

  @Test fun everyStringListReadsBackAsItself() {
    stringRoundTrip(emptyList())
    tricky.forEach { stringRoundTrip(listOf(it)) }
    for (a in tricky) for (b in tricky) stringRoundTrip(listOf(a, b))
    stringRoundTrip(tricky)
    val brand = Token("C.Brand", "Acme", "app.C")
    stringRoundTrip(listOf("@b", Bind("b"), "◆C.Brand", brand, "", " "))
    // A literal that looks like a reference is escaped, and stays a literal.
    assertEquals("\\@a|\\◆C.Brand|\"\"|\" x \"|a\\|b", listSource(listOf("@a", "◆C.Brand", "", " x ", "a|b"), PropKind.StringList))
  }

  @Test fun everyIntListReadsBackAsItself() {
    val items = listOf(Int.MIN_VALUE, -1, 0, 16777215, Int.MAX_VALUE)
    assertEquals(PropValue.Lit("li", li = items), parseListSource(listSource(items, PropKind.IntList), PropKind.IntList, known).value)
    val refs = ListOf("li", listOf(-1, Token("C.Max", 5, "app.C"), Bind("accent"), Int.MIN_VALUE))
    assertEquals(
      PropValue.ListOf("li", listOf(lit(-1), max, PropValue.Bind("accent"), lit(Int.MIN_VALUE))),
      parseListSource(listSource(refs, PropKind.IntList), PropKind.IntList, known).value,
    )
    assertEquals(PropValue.Lit("li", li = emptyList()), parseListSource(listSource(emptyList<Int>(), PropKind.IntList), PropKind.IntList, known).value)
  }

  @Test fun anAmbiguousListIsRefusedNotGuessed() {
    // An empty element is written "" — a bare one is a slip, not silently dropped.
    assertNotNull(parseListSource("a||b", PropKind.StringList, known).error)
    assertNotNull(parseListSource("1, , 2", PropKind.IntList, known).error)
    assertNotNull(parseListSource("a\\", PropKind.StringList, known).error) // a lone trailing backslash
    assertEquals(PropValue.Lit("ls", ls = listOf("a", "")), parseListSource("a|\"\"", PropKind.StringList, known).value)
  }
}
