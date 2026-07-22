package dev.keliver.portal.ingest

import dev.keliver.portal.MapComponentRegistry
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

  // ── P0 indent regression: a nested multi-line call, edited surgically, must
  // keep every line at its ORIGINAL depth (the bug: emitter spliced at exporter
  // base depth → column-2 argument lines). Byte-level asserts on purpose —
  // substring asserts are exactly how this shipped broken. ──
  private val nestedFile = """
    import androidx.compose.runtime.Composable
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText
    import dev.keliver.layout.compose.Column

    @Composable
    fun PortalScreen(b: PortalScreenBindings) {
      StyledBox(cornerRadiusDp = 12) {
        Column {
          StyledText(
            text = b.title,
            fontSize = 22,
            bold = true,
          )
        }
      }
    }

    interface PortalScreenBindings {
      val title: String
    }
  """.trimIndent()

  private fun nestedDoc(): UiDocument {
    val rec = Recognizer.recognize("PortalScreen.kt", nestedFile)!!
    return UiDocument("main", rec.root, rec.contract, version = 0, nextHandle = 0)
  }

  @Test fun nestedPropEditIsByteMinimal() {
    val doc = nestedDoc()
    val styled = ((doc.root as DocNode.Widget).children[0] as DocNode.Widget).children[0].handle
    val target = doc.apply(DocOp.SetProp(styled, "fontSize", lit(30))).doc

    val merged = WriteBack.merge(nestedFile, target)
    assertNotNull(merged)
    assertEquals(
      nestedFile.replace("fontSize = 22,", "fontSize = 30,"),
      merged,
      "surgical edit must change only the value, preserving all indentation",
    )
  }

  @Test fun singleLineIrregularSpacingSurvivesEditAndUndo() {
    // The `file` fixture's StyledText is single-line with irregular spacing
    // ("b.title,   fontSize") — a value-only edit must preserve it byte-exact,
    // and applying the inverse op must restore the ORIGINAL bytes.
    val doc = docFromFile()
    val h = styledTextHandle(doc)
    val target = doc.apply(DocOp.SetProp(h, "fontSize", lit(30))).doc

    val merged = WriteBack.merge(file, target)
    assertNotNull(merged)
    assertEquals(file.replace("fontSize = 22", "fontSize = 30"), merged, "value-only edit")

    // Undo: merge the ORIGINAL doc back onto the edited text → original bytes.
    val rec2 = Recognizer.recognize("PortalScreen.kt", merged)!!
    val doc2 = UiDocument("main", rec2.root, rec2.contract, version = 0, nextHandle = 0)
    val undone = doc2.apply(DocOp.SetProp(styledTextHandle(doc2), "fontSize", lit(22))).doc
    assertEquals(file, WriteBack.merge(merged, undone), "edit+undo must be byte-idempotent")
  }

  @Test fun nestedInsertLandsAtSiblingDepthAndReIngests() {
    val doc = nestedDoc()
    val col = (doc.root as DocNode.Widget).children[0] as DocNode.Widget
    val target = doc.apply(
      DocOp.InsertNode(
        col.handle,
        after = col.children.last().handle,
        node = DocNode.Widget(Handle(0), "Spacer", mapOf("height" to lit(12.0))),
      ),
    ).doc

    val merged = WriteBack.merge(nestedFile, target)
    assertNotNull(merged)
    assertTrue("\n      Spacer(" in merged, "insert must sit at the sibling's depth (6):\n$merged")
    val reRec = Recognizer.recognize("PortalScreen.kt", merged)!!
    assertEquals(
      exportEquiv(target.root),
      exportEquiv(UiDocument("main", reRec.root, reRec.contract, 0, 0).root),
      "merged file must re-ingest to the target tree",
    )
  }

  private val sectionCardSpec = Recognizer.recognizeComponent(
    "SectionCard.kt",
    """
      package app.components
      import androidx.compose.runtime.Composable
      import dev.keliver.material.compose.StyledBox
      @Composable
      fun SectionCard(label: String, content: @Composable () -> Unit) {
        StyledBox(fillWidth = true) { content() }
      }
    """.trimIndent(),
  )!!.spec
  private val componentRegistry = MapComponentRegistry(listOf(sectionCardSpec))

  private val slottedFile = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import app.components.SectionCard

    @Composable
    fun SlotsScreen() {
      Column {
        SectionCard(label = "Account") {
        }
      }
    }
  """.trimIndent()

  @Test fun insertIntoEmptyComponentSlotIsSurgicalAndReingests() {
    val rec = Recognizer.recognize("SlotsScreen.kt", slottedFile, componentRegistry)!!
    val doc = UiDocument("slots", rec.root, rec.contract, 0, 0)
    val card = (doc.root as DocNode.Widget).children.single() as DocNode.Widget
    val target = doc.apply(DocOp.InsertNode(
      parent = card.handle,
      after = null,
      node = DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("Inside"))),
    )).doc

    val merged = WriteBack.merge(slottedFile, target, componentRegistry)
    assertNotNull(merged)
    assertTrue("SectionCard(label = \"Account\") {" in merged)
    assertTrue("\n      StyledText(" in merged, merged)
    val reread = Recognizer.recognize("SlotsScreen.kt", merged, componentRegistry)!!
    assertTrue(!containsRaw(reread.root), merged)
    assertEquals(1, (reread.root.children.single() as DocNode.Widget).children.size)
  }

  @Test fun surgicalInsertOfEmptySlottedComponentEmitsRequiredLambda() {
    val src = slottedFile.replace(
      "    SectionCard(label = \"Account\") {\n    }\n",
      "",
    )
    val rec = Recognizer.recognize("SlotsScreen.kt", src, componentRegistry)!!
    val doc = UiDocument("slots", rec.root, rec.contract, 0, 0)
    val root = doc.root as DocNode.Widget
    val target = doc.apply(DocOp.InsertNode(
      parent = root.handle,
      after = null,
      node = DocNode.Widget(Handle(0), "SectionCard", mapOf("label" to lit("Account"))),
    )).doc

    val merged = WriteBack.merge(src, target, componentRegistry)
    assertNotNull(merged)
    assertTrue("SectionCard(" in merged, merged)
    assertTrue(") {\n" in merged, merged)
    assertTrue(!containsRaw(Recognizer.recognize("SlotsScreen.kt", merged, componentRegistry)!!.root), merged)
  }

  private fun containsRaw(n: DocNode): Boolean = when (n) {
    is DocNode.RawCode -> true
    is DocNode.Widget -> n.children.any(::containsRaw)
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
