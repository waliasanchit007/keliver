import dev.keliver.portal.Bind
import dev.keliver.portal.ListOf
import dev.keliver.portal.PropKind
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** K4: the panel's text for a template and a list, and what it sends back. */
class EditorTemplatesTest {
  private val tokens = parseTokens(
    """
    [{"ref":"C.Max","kind":"Int","value":5,"import":"app.C","ambiguous":false},
     {"ref":"C.Brand","kind":"String","value":"Acme","import":"app.C","ambiguous":false},
     {"ref":"D.Max","kind":"Int","value":1,"import":"a.D","ambiguous":true}]
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
}
