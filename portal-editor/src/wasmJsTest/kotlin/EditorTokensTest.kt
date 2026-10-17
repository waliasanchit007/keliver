import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals

/** K2: the editor's token list and which tokens its picker offers for a prop. */
class EditorTokensTest {
  private val json = """
    [{"ref":"C.Ink","owner":"C","name":"Ink","kind":"Int","value":-14737633,"const":true,"package":"app","import":"app.C","file":"Tokens.kt","ambiguous":false},
     {"ref":"C.Gap","owner":"C","name":"Gap","kind":"Double","value":12.0,"const":true,"package":"app","import":"app.C","file":"Tokens.kt","ambiguous":false},
     {"ref":"C.Title","owner":"C","name":"Title","kind":"String","value":"Funds","const":true,"package":"app","import":"app.C","file":"Tokens.kt","ambiguous":false},
     {"ref":"C.Big","owner":"C","name":"Big","kind":"Long","value":4294967295,"const":true,"package":"app","import":"app.C","file":"Tokens.kt","ambiguous":false},
     {"ref":"D.Ink","owner":"D","name":"Ink","kind":"Int","value":1,"const":true,"package":"a","import":"a.D","file":"T.kt","ambiguous":true}]
  """.trimIndent()

  private val tokens = parseTokens(json)

  @Test fun parsesEveryKindWithItsValue() {
    assertEquals(listOf("C.Ink", "C.Gap", "C.Title", "C.Big", "D.Ink"), tokens.map { it.ref })
    assertEquals(-14737633, tokens[0].value)
    assertEquals(12.0, tokens[1].value)
    assertEquals(4294967295L, tokens[3].value)
    assertEquals(PropValue.Token("C.Ink", lit(-14737633), "app.C"), tokens[0].toPropValue())
    assertEquals(emptyList(), parseTokens("not json"))
  }

  @Test fun thePickerOffersOnlyTokensOfThePropsPlainLiteralType() {
    fun refs(type: String, key: String, query: String = "", reg: dev.keliver.portal.ComponentRegistry = EmptyComponentRegistry) =
      tokensFor(type, key, reg, tokens, query).map { it.ref }
    assertEquals(listOf("C.Ink"), refs("StyledText", "colorArgb")) // ambiguous D.Ink is never offered
    assertEquals(listOf("C.Title"), refs("StyledText", "text"))
    assertEquals(listOf("C.Ink"), refs("StyledText", "mod.Background.colorArgb"))
    assertEquals(listOf("C.Gap"), refs("Row", "mod.Flex.value"))
    assertEquals(emptyList(), refs("Spacer", "height")) // a Dp
    assertEquals(emptyList(), refs("Row", "width")) // a Constraint
    assertEquals(emptyList(), refs("Row", "mod.Width.width")) // a Dp argument
    assertEquals(emptyList(), refs("StyledText", "colorArgb", query = "gap"))
    assertEquals(listOf("C.Ink"), refs("StyledText", "colorArgb", query = "1473"))
    // A project component's scalar parameters, from its catalogued kind.
    val reg = MapComponentRegistry(listOf(ComponentSpec("MenuRow", listOf(PropSpec("title", PropKind.Text, "Title")), emptyList(), emptyMap())))
    assertEquals(listOf("C.Title"), refs("MenuRow", "title", reg = reg))
  }
}
