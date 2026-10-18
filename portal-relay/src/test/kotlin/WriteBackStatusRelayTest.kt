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
 * is refused — the file is untouched — and the refusal (or any write
 * failure) is the screen's write-back status, sent on the change stream the
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

  private fun fixture(text: String = home): Fixture {
    val dir = Files.createTempDirectory("k3-writeback").toFile().apply { deleteOnExit() }
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
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      svc.writeBack?.takeIf { it.version == version }?.let { return it }
      Thread.sleep(25)
    }
    return assertNotNull(svc.writeBack?.takeIf { it.version == version }, "write-back of v$version reported")
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

  @Test fun aChangeThatWouldLoseCodeIsRefusedAndTheFileIsUntouched() {
    val f = fixture()
    // `b` shares a line with `a`: it cannot be cut on its own, and a full export would drop the comment, RawCode and helper.
    val (_, a, b) = f.svc.children()
    val ack = f.svc.submit(f.svc.batch(DocOp.MoveNode(b.handle, f.svc.doc.root.handle, f.svc.children()[0].handle)))
    assertTrue(ack.ok, ack.error)
    val status = awaitStatus(f.svc, ack.version)
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
