import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.OpEnvelope
import dev.keliver.portal.document.lit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F4 (feedback round 1): deleting nodes and undoing the deletes gives the file back byte for
 * byte — the author's argument order, and an import where the author had it — not the nodes
 * re-emitted in canonical order with the import appended.
 */
class UndoExactTextRelayTest {
  private val source = """
    package app.components

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.Divider
    import dev.keliver.material.compose.StyledText

    @Composable
    fun LabelPill(label: String) {
      Column {
        StyledText(fontSize = 12, text = label, weight = 600)
        Divider()
        StyledText(text = "New", fontSize = 10)
      }
    }
  """.trimIndent() + "\n"

  private fun open(text: String): Pair<DocumentService, File> {
    val dir = Files.createTempDirectory("undo-exact").toFile().apply { deleteOnExit() }
    val f = dir.resolve("LabelPill.kt").apply { writeText(text) }
    val svc = assertNotNull(
      DocumentService.forComponent(
        "default/component:LabelPill", f, "LabelPill",
        components = { dev.keliver.portal.EmptyComponentRegistry },
        tokens = { dev.keliver.portal.ingest.TokenTable.EMPTY },
        tokenFileId = "app/components/LabelPill.kt",
        tokensVersion = { 0L },
      ),
    )
    return svc to f
  }

  private fun children(svc: DocumentService) = (svc.doc.root as DocNode.Widget).children

  private fun awaitWritten(svc: DocumentService): String {
    val until = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < until) {
      if (svc.writeBack?.version == svc.doc.version) break
      Thread.sleep(25)
    }
    val status = assertNotNull(svc.writeBack?.takeIf { it.version == svc.doc.version }, "write-back of v${svc.doc.version} reported")
    assertEquals("surgical", status.outcome, status.message)
    return svc.file.readText()
  }

  private fun submit(svc: DocumentService, op: DocOp) =
    assertTrue(svc.submit(OpBatch(svc.doc.version, OpEnvelope("t", 0L), listOf(op))).ok)

  @Test fun deletingTwoNodesAndUndoingBothGivesTheFileBackByteForByte() {
    val (svc, f) = open(source)
    submit(svc, DocOp.DeleteNode(children(svc)[0].handle))
    val afterFirst = awaitWritten(svc)
    assertTrue("fontSize = 12" !in afterFirst, afterFirst)
    submit(svc, DocOp.DeleteNode(children(svc)[0].handle)) // the Divider: its import goes with it
    assertTrue("import dev.keliver.material.compose.Divider" !in awaitWritten(svc))

    assertTrue(svc.undo("t").ok)
    assertEquals(afterFirst, awaitWritten(svc), "the first undo gives back the file as the first delete left it")
    assertTrue(svc.undo("t").ok)
    assertEquals(source, awaitWritten(svc), "both undone: the file as the author wrote it")

    // Redo, then undo again: still the author's bytes.
    assertTrue(svc.redo("t").ok)
    assertEquals(afterFirst, awaitWritten(svc))
    assertTrue(svc.undo("t").ok)
    assertEquals(source, awaitWritten(svc))
    assertEquals(source, f.readText())
  }

  @Test fun afterAChangeInCodeAnEarlierTextIsNotWrittenBack() {
    val (svc, f) = open(source)
    submit(svc, DocOp.SetProp(children(svc)[2].handle, "text", lit("Hot")))
    awaitWritten(svc)
    // The author adds a comment in code: the tree is the same, the file is not the one remembered.
    val inCode = f.readText().replace("    Divider()\n", "    Divider() // keep me\n")
    f.writeText(inCode)
    assertEquals(DocumentService.FileChange.INGESTED, svc.fileChanged(inCode))
    // Back to the document the file was first read as: written onto the file as it is now.
    submit(svc, DocOp.SetProp(children(svc)[2].handle, "text", lit("New")))
    assertEquals(source.replace("    Divider()\n", "    Divider() // keep me\n"), awaitWritten(svc), "the comment made in code is kept")
  }
}
