import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K4 §7a, the write-back preflight: an edit the document accepts but
 * write-back would refuse is refused when it is made — submit, dry run, undo
 * and redo alike — so it never reaches the document and never blocks the
 * screen's later edits. The cases the document cannot see: (a) a template
 * reading a list item's field typed Double or nullable (the contract carries
 * no item types) and (b) a plain binding set on a screen that takes no
 * bindings parameter.
 */
class WriteBackPreflightRelayTest {
  // § is `$`.
  private val rows = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(text = "x")
        b.rows.forEach { row ->
          StyledText(text = "a §{row.name}")
        }
      }
    }

    interface HomeScreenBindings {
      val rows: List<Row>
    }

    interface Row {
      val price: Double
      val nick: String?
      val name: String
    }
  """.trimIndent().replace('§', '$') + "\n"

  private val unbound = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen() {
      Column {
        // keep me
        StyledText(text = "a")
        StyledText(text = "b")
      }
    }
  """.trimIndent() + "\n"

  private class Fixture(val file: File, val svc: DocumentService)

  private fun fixture(text: String, components: () -> ComponentRegistry = { EmptyComponentRegistry }): Fixture {
    val dir = Files.createTempDirectory("k4-preflight").toFile().apply { deleteOnExit() }
    val file = File(dir, "home.kt").apply { writeText(text) }
    val svc = DocumentService.fromFileOrTree(
      screenKey = "k4-preflight-${System.nanoTime()}/home", treeJson = null, onProjected = {},
      kotlinFile = file, functionName = "HomeScreen", packageName = "app.screens", components = components,
    )
    return Fixture(file, svc)
  }

  private fun DocumentService.batch(vararg ops: DocOp) = OpBatch(doc.version, OpEnvelope("t", 0L), ops.toList())
  private fun DocumentService.child(i: Int) = (doc.root as DocNode.Widget).children[i]
  private fun DocumentService.rowText() = ((child(1) as DocNode.Widget).children.single() as DocNode.Widget).handle

  private fun awaitStatus(svc: DocumentService, version: Long): DocumentService.WriteBackStatus {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      svc.writeBack?.takeIf { it.version == version }?.let { return it }
      Thread.sleep(25)
    }
    return assertNotNull(svc.writeBack?.takeIf { it.version == version }, "write-back of v$version reported")
  }

  /** Submits [op]; it must be accepted and written. The file's text after. */
  private fun written(f: Fixture, op: DocOp): String {
    val ack = f.svc.submit(f.svc.batch(op))
    assertTrue(ack.ok, ack.error)
    val status = awaitStatus(f.svc, ack.version)
    assertTrue(status.written, status.json())
    return f.file.readText()
  }

  /** [op] is refused as an op (dry run and submit alike, with [why]); nothing changes and the next edit is written. */
  private fun assertRefusedNotWedged(f: Fixture, op: DocOp, why: String, next: DocOp) {
    val before = f.file.readText()
    val version = f.svc.doc.version
    val dry = f.svc.dryRun(f.svc.batch(op))
    val sub = f.svc.submit(f.svc.batch(op))
    assertFalse(sub.ok, "accepted $op")
    assertFalse(dry.ok, "dry run accepted $op")
    assertEquals(dry.error, sub.error)
    val msg = sub.error!!
    assertTrue(why in msg && "make this change in code" in msg && "home.kt" in msg, msg)
    assertEquals(version, f.svc.doc.version, "the document did not take it")
    Thread.sleep(500) // past the write-back debounce: nothing was scheduled
    assertEquals(before, f.file.readText())
    assertNull(f.svc.writeBack, "nothing was written or refused")
    assertTrue(f.svc.undo("t").error.orEmpty().startsWith("nothing to undo"), "it reached no undo stack")
    // The screen is not blocked: the next edit is written.
    written(f, next)
  }

  @Test fun a_aTemplateOnAnItemFieldTheFileTypesDoubleOrNullableIsRefusedAsAnOp() {
    for (field in listOf("price", "nick")) {
      val f = fixture(rows)
      val op = DocOp.SetProp(f.svc.rowText(), "text", PropValue.Template(listOf(lit("a "), PropValue.Bind("row.$field"))))
      assertRefusedNotWedged(f, op, "row.$field", DocOp.SetProp((f.svc.child(0) as DocNode.Widget).handle, "text", lit("z")))
      assertTrue("StyledText(text = \"z\")" in f.file.readText(), f.file.readText())
    }
    // A String item field is still written.
    val f = fixture(rows)
    val text = written(f, DocOp.SetProp(f.svc.rowText(), "text", PropValue.Template(listOf(lit("b "), PropValue.Bind("row.name")))))
    assertTrue("\"b \${row.name}\"" in text, text)
  }

  @Test fun b_aPlainBindingOnAScreenWithNoBindingsParameterIsRefusedAsAnOp() {
    val f = fixture(unbound)
    val op = DocOp.SetProp((f.svc.child(0) as DocNode.Widget).handle, "text", PropValue.Bind("title"))
    assertRefusedNotWedged(f, op, "takes no bindings parameter", DocOp.SetProp((f.svc.child(1) as DocNode.Widget).handle, "text", lit("B")))
    assertEquals(unbound.replace("\"b\"", "\"B\""), f.file.readText())
  }

  @Test fun aNormalEditIsAcceptedAndWritten() {
    val f = fixture(unbound)
    val ack = f.svc.dryRun(f.svc.batch(DocOp.SetProp((f.svc.child(0) as DocNode.Widget).handle, "text", lit("A"))))
    assertTrue(ack.ok, ack.error)
    assertEquals(unbound.replace("\"a\"", "\"A\""), written(f, DocOp.SetProp((f.svc.child(0) as DocNode.Widget).handle, "text", lit("A"))))
    assertEquals("surgical", f.svc.writeBack?.outcome)
  }

  /**
   * K4 final review: the file on disk momentarily not a screen (the author
   * mid-edit) refuses every plan, the document's as it is too — so a change
   * write-back would refuse was accepted "as not why". The author then saved
   * the exact text the relay last read (the watcher skips it: already read),
   * and the change stayed in the document, refused at every write — the
   * screen wedged. Such a change is now checked against the file as last
   * read, and refused there.
   */
  @Test fun aChangeMadeWhileTheFileIsNotAScreenIsCheckedAgainstTheFileLastRead() {
    val f = fixture(rows)
    val read = f.file.readText()
    f.file.writeText("// mid-edit\n")
    assertEquals(DocumentService.FileChange.NOT_A_SCREEN, f.svc.fileChanged("// mid-edit\n"))
    val bad = DocOp.SetProp(f.svc.rowText(), "text", PropValue.Template(listOf(lit("a "), PropValue.Bind("row.price"))))
    val dry = f.svc.dryRun(f.svc.batch(bad))
    val sub = f.svc.submit(f.svc.batch(bad))
    assertFalse(sub.ok, "accepted a change the file as last read cannot take")
    assertEquals(dry.error, sub.error)
    assertTrue("row.price" in sub.error!! && "make this change in code" in sub.error!!, sub.error)
    // A change the file as last read takes is accepted, as before.
    val fine = DocOp.SetProp((f.svc.child(0) as DocNode.Widget).handle, "text", lit("z"))
    assertTrue(f.svc.dryRun(f.svc.batch(fine)).ok)
    // The author restores the text last read: nothing is wedged, the next edit is written.
    f.file.writeText(read)
    assertEquals(DocumentService.FileChange.ALREADY_READ, f.svc.fileChanged(read))
    assertTrue("StyledText(text = \"z\")" in written(f, fine), f.file.readText())
  }

  /** A file that cannot be read when the change is made (gone, or not a file): accepted as for a file changed since it was read — the writer reports what it finds. */
  @Test fun aFileThatCannotBeReadAtOpTimeIsNotAnHttpError() {
    val f = fixture(unbound)
    assertTrue(f.file.delete() && f.file.mkdir(), "the screen's path is now a directory")
    val ack = f.svc.dryRun(f.svc.batch(DocOp.SetProp((f.svc.child(0) as DocNode.Widget).handle, "text", lit("A"))))
    assertTrue(ack.ok, ack.error)
  }

  /**
   * Undo and redo go through the same check: a step write-back would refuse
   * (here: the component it restores is no longer one, so the file would not
   * read back as the document) is refused and dropped, never applied, and
   * nothing is wedged.
   */
  @Test fun undoAndRedoAreCheckedToo() {
    val menuRow = ComponentSpec("MenuRow", listOf(PropSpec("title", PropKind.Text, "Title")), emptyList(), mapOf("title" to "String"))
    var registry: ComponentRegistry = MapComponentRegistry(listOf(menuRow))
    val f = fixture(unbound.replace("import dev.keliver.material.compose.StyledText\n", "import dev.keliver.material.compose.StyledText\nimport app.components.MenuRow\n"), { registry })
    // Redo: insert, undo, the component goes away, redo.
    val row = DocNode.Widget(Handle(0), "MenuRow", mapOf("title" to lit("t")))
    assertTrue("MenuRow(" in written(f, DocOp.InsertNode(f.svc.doc.root.handle, null, row)))
    val undo = f.svc.undo("t")
    assertTrue(undo.ok, undo.error)
    assertTrue(awaitStatus(f.svc, undo.version).written)
    registry = EmptyComponentRegistry
    val version = f.svc.doc.version
    val fileBefore = f.file.readText()
    val redo = f.svc.redo("t")
    assertFalse(redo.ok, "redo of a change write-back would refuse")
    assertTrue("redo failed (step dropped)" in redo.error!! && "make this change in code" in redo.error!!, redo.error)
    assertEquals(version, f.svc.doc.version)
    assertEquals("nothing to redo", f.svc.redo("t").error)
    Thread.sleep(500)
    assertEquals(fileBefore, f.file.readText())
    assertFalse(f.svc.writeBack?.outcome == "refused", f.svc.writeBack?.json())

    // Undo (InsertExisting): the component back, inserted and deleted, gone again, undo the delete.
    registry = MapComponentRegistry(listOf(menuRow))
    assertTrue(reingestWhenIdle(f.svc))
    assertTrue("MenuRow(" in written(f, DocOp.InsertNode(f.svc.doc.root.handle, null, row)))
    assertTrue("MenuRow(" !in written(f, DocOp.DeleteNode(f.svc.child(0).handle)))
    registry = EmptyComponentRegistry
    val undoDelete = f.svc.undo("t")
    assertFalse(undoDelete.ok, "undo of a delete write-back would refuse")
    assertTrue("undo failed (step dropped)" in undoDelete.error!!, undoDelete.error)
    assertTrue("MenuRow(" !in f.file.readText())
    // Nothing is wedged: the next edit is written.
    assertTrue("text = \"B\"" in written(f, DocOp.SetProp((f.svc.child(1) as DocNode.Widget).handle, "text", lit("B"))))
  }
}
