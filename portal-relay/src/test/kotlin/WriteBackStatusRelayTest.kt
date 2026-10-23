import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.OpEnvelope
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K3 last review round, through the relay: write-back never silently
 * destroys author code. A change that cannot be written without losing code
 * is refused — the file is untouched. K4 §7a: when the relay can tell at the
 * edit (it plans the write then), the edit itself is refused; when only the
 * write can (the file changed in between), or the write fails, the refusal
 * or failure is the screen's write-back status, sent on the change stream the
 * editor listens to. A RawCode replacement that could not stand as that one
 * statement is refused as an op, up front.
 */
class WriteBackStatusRelayTest {
  private val home = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(bindings: HomeScreenBindings) {
      Column {
        // keep me
        Legacy(bindings.title)
        StyledText(text = "a"); StyledText(text = "b")
      }
    }

    private fun helper() = Unit

    interface HomeScreenBindings {
      val title: String
    }
  """.trimIndent() + "\n"

  private class Fixture(val file: File, val svc: DocumentService, val events: ByteArrayOutputStream)

  private fun fixture(text: String = home, dir: File = Files.createTempDirectory("k3-writeback").toFile().apply { deleteOnExit() }): Fixture {
    val file = File(dir, "home.kt").apply { writeText(text) }
    val svc = DocumentService.fromFileOrTree(
      screenKey = "k3-writeback-${System.nanoTime()}/home",
      treeJson = null,
      onProjected = {},
      kotlinFile = file,
      functionName = "HomeScreen",
      packageName = "app.screens",
    )
    val events = ByteArrayOutputStream()
    svc.subscribe(events)
    return Fixture(file, svc, events)
  }

  private fun DocumentService.batch(vararg ops: DocOp) = OpBatch(doc.version, OpEnvelope("t", 0L), ops.toList())
  private fun DocumentService.children() = (doc.root as DocNode.Widget).children

  /** Waits for the write-back of [version] to be reported. */
  private fun awaitStatus(svc: DocumentService, version: Long): DocumentService.WriteBackStatus {
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
      svc.writeBack?.takeIf { it.version == version }?.let { return it }
      Thread.sleep(25)
    }
    return assertNotNull(svc.writeBack?.takeIf { it.version == version }, "write-back of v$version reported")
  }

  /**
   * K3 fifth review, S-a: deleting a use of a local declaration is not moving
   * it out of scope — the delete is accepted AND written in place (it was
   * refused at write-back, wedging the screen).
   */
  @Test fun deletingAUseOfALocalDeclarationIsWrittenInPlace() {
    val text = """
      package app.screens

      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.StyledText

      @Composable
      fun HomeScreen(bindings: HomeScreenBindings) {
        Column {
          val label = "x"
          Legacy(label)
          StyledText(text = "a")
        }
      }

      interface HomeScreenBindings
    """.trimIndent() + "\n"
    val f = fixture(text)
    val ack = f.svc.submit(f.svc.batch(DocOp.DeleteNode(f.svc.children()[1].handle)))
    assertTrue(ack.ok, ack.error)
    val status = awaitStatus(f.svc, ack.version)
    assertEquals("surgical", status.outcome, status.toString())
    assertEquals(text.replace("    Legacy(label)\n", ""), f.file.readText())
  }

  @Test fun theReviewersInsertIsWrittenInPlace() {
    val f = fixture()
    val node = DocNode.Widget(Handle(0), "StyledText", mapOf("text" to PropValue.Bind("title")))
    val ack = f.svc.submit(f.svc.batch(DocOp.InsertNode(f.svc.doc.root.handle, f.svc.children()[0].handle, node)))
    assertTrue(ack.ok, ack.error)
    val status = awaitStatus(f.svc, ack.version)
    assertEquals("surgical", status.outcome, status.toString())
    assertEquals(
      home.replace("    Legacy(bindings.title)\n", "    Legacy(bindings.title)\n    StyledText(\n      text = bindings.title,\n    )\n"),
      f.file.readText(),
    )
  }

  /**
   * K4 §7a preflight: a change write-back would refuse is refused when it is
   * made — the document never takes it, so nothing blocks the next edit.
   */
  @Test fun aChangeThatWouldLoseCodeIsRefusedAsAnOp() {
    val f = fixture()
    // `b` shares a line with `a`: it cannot be cut on its own, and a full export would drop the comment, RawCode and helper.
    val (_, a, b) = f.svc.children()
    val move = DocOp.MoveNode(b.handle, f.svc.doc.root.handle, f.svc.children()[0].handle)
    val dry = f.svc.dryRun(f.svc.batch(move))
    val ack = f.svc.submit(f.svc.batch(move))
    assertFalse(ack.ok)
    assertEquals(dry.error, ack.error)
    assertTrue("without losing code" in ack.error!! && "home.kt" in ack.error!! && "make this change in code" in ack.error!!, ack.error)
    assertEquals(0L, f.svc.doc.version)
    assertEquals(listOf(a.handle, b.handle), f.svc.children().drop(1).map { it.handle })
    // Nothing is wedged: the next edit is written.
    val next = f.svc.submit(f.svc.batch(DocOp.SetProp(a.handle, "text", lit("A"))))
    assertTrue(next.ok, next.error)
    assertEquals("surgical", awaitStatus(f.svc, next.version).outcome)
    assertEquals(home.replace("\"a\"", "\"A\""), f.file.readText())
  }

  /** [home] with `a` and `b` on lines of their own: moving `b` is written in place. */
  private val separate = home.replace("StyledText(text = \"a\"); StyledText(text = \"b\")", "StyledText(text = \"a\")\n    StyledText(text = \"b\")")

  /**
   * What the op-time plan cannot see: the file changes between the edit and
   * its write. Moving `b` is accepted against [separate]; before the write
   * runs, an IDE save puts `a` and `b` back on one line ([home] — it still
   * reads as the same document, so the writer merges onto it) and the move
   * can no longer be written: refused at write time. The fixture's file is
   * [home] after this.
   */
  private fun refusedAtWrite(f: Fixture): Pair<Long, DocumentService.WriteBackStatus> {
    val (_, _, b) = f.svc.children()
    val ack = f.svc.submit(f.svc.batch(DocOp.MoveNode(b.handle, f.svc.doc.root.handle, f.svc.children()[0].handle)))
    assertTrue(ack.ok, ack.error)
    f.file.writeText(home) // well inside the 400 ms debounce
    return ack.version to awaitStatus(f.svc, ack.version)
  }

  @Test fun aChangeTheFileCannotTakeByTheTimeItIsWrittenIsRefusedAndTheFileIsUntouched() {
    val f = fixture(separate)
    val (_, a, b) = f.svc.children()
    val (_, status) = refusedAtWrite(f)
    assertEquals("refused", status.outcome, status.toString())
    assertTrue("without losing code" in status.message && "home.kt" in status.message, status.message)
    assertEquals(home, f.file.readText(), "the file is untouched")
    // The editor hears it on the change stream ...
    val events = f.events.toString()
    assertTrue("\"writeBack\":{" in events && "\"outcome\":\"refused\"" in events, events)
    // ... and an undo writes the file as it was (nothing to write: it is unchanged).
    assertTrue(f.svc.undo("t").ok)
    assertEquals("surgical", awaitStatus(f.svc, f.svc.doc.version).outcome)
    assertEquals(home, f.file.readText())
    assertEquals(listOf(a.handle, b.handle), f.svc.children().drop(1).map { it.handle })
  }

  /**
   * S4: reading the file again (here: a component or token changed, so the
   * relay re-reads it) replaces a refused change with the file — and says so,
   * explicitly, on the stream; an ordinary read says the file was read.
   */
  @Test fun aReReadThatDiscardsARefusedChangeSaysSo() {
    val f = fixture(separate)
    assertEquals("refused", refusedAtWrite(f).second.outcome)
    assertTrue(reingestWhenIdle(f.svc))
    val status = assertNotNull(f.svc.writeBack)
    assertEquals("discarded", status.outcome, status.toString())
    assertFalse(status.written)
    assertTrue("could not be written" in status.message && "discarded" in status.message, status.message)
    assertTrue("\"outcome\":\"discarded\"" in f.events.toString(), f.events.toString())
    assertEquals(home, f.file.readText())
    // An IDE edit with nothing unwritten: the status says the file was read (it clears the editor's warning).
    val edited = home.replace("\"a\"", "\"A\"")
    f.file.writeText(edited)
    assertEquals(DocumentService.FileChange.INGESTED, f.svc.fileChanged(edited))
    val read = assertNotNull(f.svc.writeBack)
    assertEquals("external", read.outcome, read.toString())
    assertTrue(read.written)
  }

  /**
   * S6: after a refusal at write time every later edit is refused too (the
   * document keeps the change that cannot be written; the preflight lets an
   * edit through when the document before it could not be written either —
   * this edit is not why). The status says so, names the version that first
   * failed, and how to get out of it.
   */
  @Test fun aRefusalBlocksLaterEditsAndSaysWhichChangeFirstFailed() {
    val f = fixture(separate)
    val (_, a, b) = f.svc.children()
    val (firstVersion, refused) = refusedAtWrite(f)
    assertEquals("refused", refused.outcome)
    assertTrue("undo it (from the session that made it) or make it in code" in refused.message, refused.message)
    assertTrue("until then no edit to this screen is written" in refused.message, refused.message)
    val later = f.svc.submit(f.svc.batch(DocOp.SetProp(a.handle, "text", lit("A"))))
    assertTrue(later.ok, later.error)
    val blocked = awaitStatus(f.svc, later.version)
    assertEquals("refused", blocked.outcome)
    assertTrue("v$firstVersion" in blocked.message && "earlier change" in blocked.message, blocked.message)
    assertEquals(home, f.file.readText())
    // Undoing both writes the file again; the same move made now is refused as an op (the file is [home]).
    assertTrue(f.svc.undo("t").ok)
    assertTrue(f.svc.undo("t").ok)
    assertEquals("surgical", awaitStatus(f.svc, f.svc.doc.version).outcome)
    val again = f.svc.submit(f.svc.batch(DocOp.MoveNode(b.handle, f.svc.doc.root.handle, f.svc.children()[0].handle)))
    assertFalse(again.ok)
    assertFalse("earlier change" in again.error!!, again.error)
    assertTrue("make this change in code" in again.error!!, again.error)
  }

  /** S5: moving a declaration after its use is refused as an op (it would read back but not compile). */
  @Test fun aDeclarationMovedAfterItsUseIsRefusedAsAnOp() {
    val text = home.replace("    // keep me\n", "    val label = \"hi\"\n    // keep me\n")
      .replace("Legacy(bindings.title)", "Legacy(label)")
    assertTrue("val label" in text && "Legacy(label)" in text)
    val f = fixture(text)
    val (decl, use) = f.svc.children()
    val ack = f.svc.submit(f.svc.batch(DocOp.MoveNode(decl.handle, f.svc.doc.root.handle, use.handle)))
    assertFalse(ack.ok)
    assertTrue("`label`" in ack.error.orEmpty(), ack.error)
    assertFalse(f.svc.dryRun(f.svc.batch(DocOp.MoveNode(decl.handle, f.svc.doc.root.handle, use.handle))).ok)
    assertEquals(text, f.file.readText())
  }

  @Test fun aWriteFailureIsReportedNotSwallowed() {
    val f = fixture()
    assertTrue(f.file.setWritable(false))
    try {
      val a = f.svc.children()[1]
      val ack = f.svc.submit(f.svc.batch(DocOp.SetProp(a.handle, "text", lit("A"))))
      assertTrue(ack.ok, ack.error)
      val status = awaitStatus(f.svc, ack.version)
      assertEquals("failed", status.outcome, status.toString())
      assertEquals(home, f.file.readText())
    } finally {
      f.file.setWritable(true)
    }
  }

  /** The screen is written but its Compiled_ sibling is not: the status must not say the change is not in the file. */
  @Test fun aCompiledVersionFailureDoesNotDenyTheWrite() {
    val f = fixture()
    File(f.file.parentFile, "Compiled_home.kt").mkdirs() // a directory: writing it fails
    val a = f.svc.children()[1]
    val ack = f.svc.submit(f.svc.batch(DocOp.SetProp(a.handle, "text", lit("A"))))
    val status = awaitStatus(f.svc, ack.version)
    assertEquals("surgical", status.outcome, status.toString())
    assertTrue("Compiled_home.kt" in status.message && "not in the file" !in status.message, status.message)
    assertEquals(home.replace("\"a\"", "\"A\""), f.file.readText())
  }

  /** E43: a new relay or store starts a screen at the version its stamp holds, so the stamp never goes back. */
  @Test fun theVersionStartsAtTheStampAndTheStampNeverGoesBack() {
    val dir = Files.createTempDirectory("e43").toFile().apply { deleteOnExit() }
    val stamp = File(dir, "Compiled_home.kt").apply { writeText("package app.screens\n\nconst val COMPILED_VERSION_home: Int = 4\n") }
    val f = fixture(dir = dir)
    assertEquals(4L, f.svc.doc.version)
    val ack = f.svc.submit(f.svc.batch(DocOp.SetProp(f.svc.children()[1].handle, "text", lit("A"))))
    assertEquals(5L, ack.version)
    awaitStatus(f.svc, ack.version)
    assertTrue("COMPILED_VERSION_home: Int = 5" in stamp.readText(), stamp.readText())
    // Every change event carries the component-registry version (editors redraw instances when it moves).
    assertTrue("\"components\":0" in f.events.toString(), f.events.toString())
  }

  @Test fun aReplaceRawWithATrailingCommentIsRefusedAsAnOp() {
    val f = fixture()
    val raw = f.svc.children()[0]
    assertTrue(raw is DocNode.RawCode)
    val ack = f.svc.submit(f.svc.batch(DocOp.ReplaceRaw(raw.handle, "Legacy(2) // c")))
    assertFalse(ack.ok)
    assertTrue("comment" in ack.error.orEmpty(), ack.error)
    assertFalse(f.svc.dryRun(f.svc.batch(DocOp.ReplaceRaw(raw.handle, "Legacy(2) // c"))).ok)
    // Several statements are not one RawCode either.
    assertFalse(f.svc.submit(f.svc.batch(DocOp.ReplaceRaw(raw.handle, "Legacy(2)\nLegacy(3)"))).ok)
    // A plain replacement is accepted and written in place.
    val ok = f.svc.submit(f.svc.batch(DocOp.ReplaceRaw(raw.handle, "Legacy(bindings.title, 2)")))
    assertTrue(ok.ok, ok.error)
    assertEquals("surgical", awaitStatus(f.svc, ok.version).outcome)
    assertEquals(home.replace("Legacy(bindings.title)", "Legacy(bindings.title, 2)"), f.file.readText())
  }

  @Test fun aReplaceRawThatReadsAsAWidgetIsRefusedAsAnOp() {
    val f = fixture()
    val raw = f.svc.children()[0]
    val ack = f.svc.submit(f.svc.batch(DocOp.ReplaceRaw(raw.handle, "StyledText(text = bindings.title)")))
    assertFalse(ack.ok)
    assertTrue("StyledText" in ack.error.orEmpty() && "widget" in ack.error.orEmpty(), ack.error)
    assertEquals(home, f.file.readText())
  }
}
