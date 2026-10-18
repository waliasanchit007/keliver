package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.kotlinStringLiteral
import dev.keliver.portal.parseKotlinStringLiteral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * U33: string literals keep their meaning through ingest -> edit -> write-back.
 * Found on a real app's screen: a heading `"Your savings\nat a glance"` showed a
 * literal `\n` in the editor, editing it wrote `\\n`, and a typed `$b` became a
 * template that interpolated the screen's bindings parameter.
 */
class StringLiteralRoundTripTest {
  private fun screen(textArg: String) = """
    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.StyledText
    import dev.keliver.layout.compose.Column

    @Composable
    fun PortalScreen(b: PortalScreenBindings) {
      Column {
        StyledText(
          text = $textArg,
          fontSize = 24,
        )
      }
    }

    interface PortalScreenBindings
  """.trimIndent()

  private fun doc(file: String): UiDocument {
    val rec = Recognizer.recognize("PortalScreen.kt", file)!!
    return UiDocument("main", rec.root, rec.contract, version = 0, nextHandle = 0)
  }

  private fun text(doc: UiDocument): DocNode {
    val col = doc.root as DocNode.Widget
    return col.children[0]
  }

  @Test fun escapesAreDecodedOnIngest() {
    val node = text(doc(screen(""""Your savings\nwith \"quotes\" \u20B9 \$5 \\ end"""")))
    assertIs<DocNode.Widget>(node)
    assertEquals(PropValue.Lit("s", s = "Your savings\nwith \"quotes\" \u20B9 \$5 \\ end"), node.props["text"])
  }

  @Test fun editingTextWithANewlineKeepsTheNewline() {
    val file = screen(""""Your savings\nat a glance"""")
    val d = doc(file)
    val target = d.apply(DocOp.SetProp(text(d).handle, "text", lit("Your savings\nat a glance today"))).doc
    val merged = assertNotNull(WriteBack.merge(file, target))
    assertTrue("""text = "Your savings\nat a glance today",""" in merged, merged)
    assertTrue("""\\n""" !in merged, merged)
    // Exactly one line changed: the write-back is still surgical.
    val changed = file.lines().zip(merged.lines()).count { (a, b) -> a != b }
    assertEquals(1, changed, merged)
  }

  @Test fun aTypedDollarIsEscapedNotATemplate() {
    val file = screen(""""Title"""")
    val d = doc(file)
    val target = d.apply(DocOp.SetProp(text(d).handle, "text", lit("Title \$b \${b.x}"))).doc
    val merged = assertNotNull(WriteBack.merge(file, target))
    assertTrue("""text = "Title \${'$'}b \${'$'}{b.x}",""" in merged, merged)
  }

  @Test fun templatesAndConcatenationsAreNotLiterals() {
    // Not in the grammar -> preserved as code, never misread as text. (K4: a
    // template over grammar references — `"Hi ${'$'}{b.name}"` — is grammar:
    // TemplateListGrammarTest; one over an unknown name or an expression is not.)
    for (expr in listOf(""""Hi ${'$'}name"""", """"Hi ${'$'}{b.name.trim()}"""", """"a" + "b"""", "\"\"\"raw\"\"\"")) {
      val node = text(doc(screen(expr)))
      assertIs<DocNode.RawCode>(node, "$expr -> $node")
    }
  }

  @Test fun literalHelpersRoundTrip() {
    val values = listOf("", "plain", "a\nb\r\tc\b", "q\"uote", "back\\slash", "\$x \${y} $", "\u0001\u001f", "₹ é 😀")
    for (v in values) {
      val lit = kotlinStringLiteral(v)
      assertEquals(v, parseKotlinStringLiteral(lit), lit)
    }
    assertEquals("\"\\\$b\"", kotlinStringLiteral("\$b"))
    assertEquals("\"\\n\"", kotlinStringLiteral("\n"))
    for (bad in listOf("\"\\q\"", "\"\\u12\"", "\"\\u12G4\"", "\"trailing\\\"", "\"\$x\"", "\"a\"+\"b\"", "\"\"\"x\"\"\"", "x")) {
      assertNull(parseKotlinStringLiteral(bad), bad)
    }
    assertEquals("cost \$ 5", parseKotlinStringLiteral("\"cost \$ 5\""))
  }

  @Test fun reviewEdgeCases() {
    // `${'$'}` is the idiomatic constant dollar.
    assertEquals("\$5 off", parseKotlinStringLiteral("\"\${'$'}5 off\""))
    // `$` before a non-BMP letter may be a template: refused.
    assertNull(parseKotlinStringLiteral("\"\$\uD835\uDC65\""))
    // Invisible and unencodable characters are written as \uXXXX, and round-trip.
    for (v in listOf("\u0085", "\u007F", "\u2028", "\u2029", "\uFEFF", "lone \uD800", "lone \uDC00 x")) {
      val lit = kotlinStringLiteral(v)
      assertTrue(lit.all { it in ' '..'~' }, lit)
      assertEquals(v, parseKotlinStringLiteral(lit), lit)
    }
    // A valid pair stays raw.
    assertEquals("\"😀\"", kotlinStringLiteral("😀"))
  }
}
