package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K3 review nit: a CRLF file reads as its LF text (the PSI parser drops a
 * `@Composable` before `\r\n`, so the screen was not recognized and a
 * write-back fell to a whole-file LF export). Write-back keeps CRLF.
 */
class CrlfTest {
  private val lf = "package app.screens\n\nimport androidx.compose.runtime.Composable\nimport dev.keliver.layout.compose.Column\n" +
    "import dev.keliver.material.compose.StyledText\n\n@Composable\nfun HomeScreen(b: HomeScreenBindings) {\n  Column {\n" +
    "    // keep\n    StyledText(text = \"top\")\n    Legacy(\n      1,\n    )\n    if (b.open) {\n      StyledText(text = \"o\")\n    }\n  }\n}\n\n" +
    "interface HomeScreenBindings {\n  val open: Boolean\n}\n"
  private val crlf = lf.replace("\n", "\r\n")

  private fun doc(text: String): UiDocument =
    assertNotNull(Recognizer.recognize("app/screens/HomeScreen.kt", text)).let { UiDocument("home", it.root, it.contract, 0, 1000) }

  @Test fun aCrlfScreenReadsAsItsLfText() {
    val a = doc(lf)
    val b = doc(crlf)
    assertEquals(a.root, b.root)
    assertEquals(a.contract, b.contract)
    assertTrue((b.root as DocNode.Widget).children.none { it is DocNode.RawCode && '\r' in it.text })
  }

  @Test fun writeBackKeepsCrlf() {
    val d = doc(crlf)
    assertEquals(crlf, WriteBack.merge(crlf, d, fileName = "app/screens/HomeScreen.kt"))
    val top = (d.root as DocNode.Widget).children[0]
    val edited = WriteBack.merge(crlf, d.apply(DocOp.SetProp(top.handle, "text", lit("TOP"))).doc, fileName = "app/screens/HomeScreen.kt")
    assertEquals(crlf.replace("\"top\"", "\"TOP\""), edited)
  }

  @Test fun aCrlfComponentIsRecognized() {
    val src = "import androidx.compose.runtime.Composable\nimport dev.keliver.layout.compose.Column\n" +
      "import dev.keliver.material.compose.StyledText\n\n@Composable\nfun InfoTile(title: String) {\n  Column {\n    StyledText(text = title)\n  }\n}\n"
    val spec = assertNotNull(Recognizer.recognizeComponent("InfoTile.kt", src.replace("\n", "\r\n"))).spec
    assertTrue(spec.transparent, spec.diagnostic)
    val lfSpec = assertNotNull(Recognizer.recognizeComponent("InfoTile.kt", src)).spec
    assertEquals(lfSpec.copy(body = null), spec.copy(body = null))
    fun shape(n: dev.keliver.portal.WidgetNode?): String = n?.let { "${it.type}${it.props}${it.children.map(::shape)}" } ?: "-"
    assertEquals(shape(lfSpec.body), shape(spec.body))
  }

  @Test fun lineEndingsHelper() {
    assertEquals("a\r\nb\r\n", LineEndings.restore("a\r\nb\r\n", "a\nb\n"))
    assertEquals("a\nb\n", LineEndings.restore("a\nb\n", "a\nb\n"))
    // Mixed: written as LF (there is no one ending to keep).
    assertEquals("a\nb\n", LineEndings.restore("a\r\nb\n", "a\nb\n"))
    assertEquals("a\nb", LineEndings.toLf("a\r\nb"))
  }
}
