import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.OpEnvelope
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K3 fourth review, B1: write-back only ever merges onto the file the relay
 * last wrote or read. When the file on disk is anything else — the author
 * undid in the IDE an edit the relay had read (so the file is back to a text
 * the relay once wrote), or saved in the IDE between an editor edit and its
 * write — the change is not merged by position (which deleted the author's
 * statement and wrote it as surgical): the file is re-read, left exactly as
 * it is, and the editor is told its change was discarded.
 */
class FileChangedSinceReadRelayTest {
  private val home = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(text = "a")
        Legacy(1)
        StyledText(text = "b")
      }
    }

    interface HomeScreenBindings
  """.trimIndent() + "\n"

  private fun service(file: File) = DocumentService.fromFileOrTree(
    screenKey = "b1-${System.nanoTime()}/home",
    treeJson = null,
    onProjected = {},
    kotlinFile = file,
    functionName = "HomeScreen",
    packageName = "app.screens",
  )

  private fun tempFile(text: String?): File {
    val dir = Files.createTempDirectory("k3-b1").toFile().apply { deleteOnExit() }
    return File(dir, "home.kt").apply { if (text != null) writeText(text) }
  }

  private fun DocumentService.batch(vararg ops: DocOp) = OpBatch(doc.version, OpEnvelope("t", 0L), ops.toList())
  private fun DocumentService.children() = (doc.root as DocNode.Widget).children
  private fun DocumentService.texts() = children().map { n ->
    when (n) {
      is DocNode.RawCode -> n.text
      is DocNode.Widget -> ((n.props["text"] as? PropValue.Lit)?.s ?: n.type)
    }
  }

  private fun awaitStatus(svc: DocumentService, version: Long): DocumentService.WriteBackStatus {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      svc.writeBack?.takeIf { it.version >= version }?.let { return it }
      Thread.sleep(25)
    }
    return assertNotNull(svc.writeBack?.takeIf { it.version >= version }, "write-back of v$version reported")
  }

  private fun setText(svc: DocumentService, i: Int, text: String): Long {
    val ack = svc.submit(svc.batch(DocOp.SetProp(svc.children()[i].handle, "text", lit(text))))
    assertTrue(ack.ok, ack.error)
    return ack.version
  }

  @Test fun anIdeUndoOfAnEditTheRelayReadIsReadAgainAndTheAuthorsCodeSurvives() {
    val file = tempFile(home)
    val svc = service(file)
    // 1. The editor edits `a`: the relay writes T1.
    assertEquals("surgical", awaitStatus(svc, setText(svc, 0, "A")).outcome)
    val t1 = file.readText()
    assertEquals(DocumentService.FileChange.SELF_WRITE, svc.fileChanged(t1), "the relay's own write is not an edit")
    // 2. The author deletes `Legacy(1)` in the IDE: read.
    val t2 = t1.replace("    Legacy(1)\n", "")
    file.writeText(t2)
    assertEquals(DocumentService.FileChange.INGESTED, svc.fileChanged(t2))
    assertEquals(listOf("A", "b"), svc.texts())
    // 3. The author undoes that in the IDE: the file is T1 again — an edit, not the relay's write.
    file.writeText(t1)
    assertEquals(DocumentService.FileChange.INGESTED, svc.fileChanged(t1), "T1 is the author's text now")
    assertEquals(listOf("A", "Legacy(1)", "b"), svc.texts())
    // 4. The next editor edit keeps the author's statement.
    assertEquals("surgical", awaitStatus(svc, setText(svc, 2, "B")).outcome)
    assertEquals(t1.replace("\"b\"", "\"B\""), file.readText())
  }

  @Test fun theFileChangingBetweenAnEditAndItsWriteIsReadNotMergedByPosition() {
    val file = tempFile(home)
    val svc = service(file)
    // The editor edits `b` ...
    val v = setText(svc, 2, "B")
    // ... and before its write lands (400 ms), the author's IDE save removes `a` and the watcher has not run yet.
    val authored = home.replace("    StyledText(text = \"a\")\n", "")
    file.writeText(authored)
    val status = awaitStatus(svc, v)
    assertEquals("discarded", status.outcome, status.toString())
    assertFalse(status.written)
    assertTrue("home.kt" in status.message && "changed" in status.message, status.message)
    assertEquals(authored, file.readText(), "the author's file is left exactly as it is")
    assertEquals(listOf("Legacy(1)", "b"), svc.texts(), "the editor now shows the file")
    // K3 fifth review, B-1: ~300 ms later the watcher reads the same author text. The writer read it
    // already: that is not a change, and the discard is still what the editor is told.
    val version = svc.doc.version
    assertEquals(DocumentService.FileChange.ALREADY_READ, svc.fileChanged(authored))
    assertEquals(version, svc.doc.version)
    assertEquals("discarded", svc.writeBack?.outcome, svc.writeBack.toString())
    // A read of the file as the same document (only whitespace changed) is nothing new either.
    file.writeText("$authored\n")
    assertEquals(DocumentService.FileChange.INGESTED, svc.fileChanged("$authored\n"))
    assertEquals("discarded", svc.writeBack?.outcome, "nothing new happened: the discard stays current")
    file.writeText(authored)
    assertEquals(DocumentService.FileChange.INGESTED, svc.fileChanged(authored))
    // And the next edit is written onto it.
    assertEquals("surgical", awaitStatus(svc, setText(svc, 1, "B")).outcome)
    assertEquals(authored.replace("\"b\"", "\"B\""), file.readText())
  }

  /**
   * K3 fifth review: an editor edit undone before its write leaves the document
   * equal to the file — an author edit read then discards nothing.
   */
  @Test fun aReadAfterAnEditWasUndoneDiscardsNothing() {
    val file = tempFile(home)
    val svc = service(file)
    setText(svc, 2, "B")
    assertTrue(svc.undo("t").ok)
    val authored = home.replace("\"a\"", "\"A\"")
    file.writeText(authored)
    assertEquals(DocumentService.FileChange.INGESTED, svc.fileChanged(authored))
    val status = assertNotNull(svc.writeBack)
    assertEquals("external", status.outcome, status.toString())
    assertTrue(status.written)
  }

  @Test fun aFileThatAppearedUnreadIsReadNotMergedOnto() {
    val file = tempFile(null)
    val svc = service(file) // no file: nothing has been read
    file.writeText(home) // the file appears (e.g. a git checkout) before the watcher reads it
    val ack = svc.submit(svc.batch(DocOp.InsertNode(svc.doc.root.handle, null, DocNode.Widget(dev.keliver.portal.document.Handle(0), "StyledText", mapOf("text" to lit("x"))))))
    assertTrue(ack.ok, ack.error)
    val status = awaitStatus(svc, ack.version)
    assertEquals("discarded", status.outcome, status.toString())
    assertEquals(home, file.readText())
    assertEquals(listOf("a", "Legacy(1)", "b"), svc.texts())
  }
}
