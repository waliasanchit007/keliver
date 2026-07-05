package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WriteBackTest {
  // Human-authored: comments + irregular spacing + a RawCode if-block that the
  // recognizer can't model — all must survive a surgical prop edit.
  private val file = """
    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.Button
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText
    import dev.keliver.layout.compose.Column

    // A precious header comment.
    @Composable
    fun PortalScreen(b: PortalScreenBindings) {
      StyledBox(cornerRadiusDp = 12, fillWidth = true) {
        Column {
          /* inline note that must not move */
          StyledText(text = b.title,   fontSize = 22)
          if (b.title.length > 40) {
            StyledText(text = "long!", fontSize = 9)
          }
          Button(text = "Buy", onClick = { b.buy() })
        }
      }
    }

    interface PortalScreenBindings {
      val title: String
      fun buy()
    }
  """.trimIndent()

  private fun docFromFile(): UiDocument {
    val rec = Recognizer.recognize("PortalScreen.kt", file)!!
    return UiDocument("main", rec.root, rec.contract, version = 0, nextHandle = 0)
  }

  private fun styledTextHandle(doc: UiDocument): Handle {
    val col = (doc.root as DocNode.Widget).children[0] as DocNode.Widget
    return col.children[0].handle
  }

  @Test fun propEditPreservesCommentsAndRawCode() {
    val doc = docFromFile()
    val target = doc.apply(DocOp.SetProp(styledTextHandle(doc), "fontSize", lit(30))).doc

    val merged = WriteBack.merge(file, target)
    assertNotNull(merged)
    // The edit landed.
    assertTrue("fontSize = 30" in merged, "fontSize updated: $merged")
    assertTrue("fontSize = 22" !in merged)
    // Everything precious survived byte-exact.
    assertTrue("// A precious header comment." in merged)
    assertTrue("/* inline note that must not move */" in merged)
    assertTrue("if (b.title.length > 40) {" in merged)
    assertTrue("""StyledText(text = "long!", fontSize = 9)""" in merged)
    assertTrue("onClick = { b.buy() }" in merged)
    // StyledBox arg list untouched (its own comment context intact).
    assertTrue("StyledBox(cornerRadiusDp = 12, fillWidth = true) {" in merged)
    // The Bindings interface is untouched.
    assertTrue("interface PortalScreenBindings {" in merged)
  }

  @Test fun insertAppendsStatementPreservingSiblings() {
    val doc = docFromFile()
    val col = (doc.root as DocNode.Widget).children[0] as DocNode.Widget
    val lastChild = col.children.last().handle
    val target = doc.apply(
      DocOp.InsertNode(col.handle, after = lastChild, node = DocNode.Widget(Handle(0), "Spacer", mapOf("height" to PropValue0()))),
    ).doc

    val merged = WriteBack.merge(file, target)
    assertNotNull(merged)
    assertTrue("Spacer(" in merged)
    assertTrue("/* inline note that must not move */" in merged) // siblings + comment intact
    assertTrue("if (b.title.length > 40) {" in merged)
  }

  @Test fun deleteRemovesOnlyThatStatement() {
    val doc = docFromFile()
    val col = (doc.root as DocNode.Widget).children[0] as DocNode.Widget
    val button = col.children.last().handle // Button
    val target = doc.apply(DocOp.DeleteNode(button)).doc

    val merged = WriteBack.merge(file, target)
    assertNotNull(merged)
    assertTrue("onClick = { b.buy() }" !in merged) // Button gone
    assertTrue("/* inline note that must not move */" in merged)
    assertTrue("if (b.title.length > 40) {" in merged) // RawCode kept
  }

  @Test fun mergedOutputReIngestsToTheTargetDocument() {
    val doc = docFromFile()
    val target = doc.apply(DocOp.SetProp(styledTextHandle(doc), "fontSize", lit(30))).doc
    val merged = WriteBack.merge(file, target)!!
    val reRec = Recognizer.recognize("PortalScreen.kt", merged)!!
    // Structural + prop equality (handles differ; compare via export projection).
    assertEquals(
      exportEquiv(target.root),
      exportEquiv(UiDocument("main", reRec.root, reRec.contract, 0, 0).root),
    )
  }

  // Compare trees ignoring handles.
  private fun exportEquiv(n: DocNode): String = when (n) {
    is DocNode.RawCode -> "RAW(${n.text.trim()})"
    is DocNode.Widget -> "${n.type}${n.props}${n.modifiers}[${n.children.joinToString { exportEquiv(it) }}]"
  }

  // 12.0 height literal helper (avoids importing PropValue in the test twice).
  @Suppress("FunctionName")
  private fun PropValue0() = lit(12.0)
}
