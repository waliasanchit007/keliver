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
 * K4 last review, through the relay: a template reading a screen field whose
 * type the screen's file does not show is refused when the op is made — not
 * accepted and then refused at write-back, which blocks every later edit of
 * the screen (§7a). The reviewer's cases: (A) no Bindings interface in the
 * file, (B) a field inherited from an interface declared elsewhere, (E) a
 * new field beside such a supertype; and, read correctly now, (C) another
 * *Bindings interface declared first and (H) a nullable parameter; (D) a new
 * field of an interface read whole is still written and declared.
 */
class UnreadableContractRelayTest {
  private val bindings = "interface HomeScreenBindings {\n  val name: String\n}\n"

  private fun screen(param: String = "HomeScreenBindings", tail: String = bindings) = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: $param) {
      Column {
        StyledText(text = "x")
        StyledText(text = "y")
      }
    }
  """.trimIndent() + "\n" + (if (tail.isEmpty()) "" else "\n" + tail)

  private class Fixture(val file: File, val svc: DocumentService)

  private fun fixture(text: String): Fixture {
    val dir = Files.createTempDirectory("k4-unreadable").toFile().apply { deleteOnExit() }
    val file = File(dir, "home.kt").apply { writeText(text) }
    val svc = DocumentService.fromFileOrTree(
      screenKey = "k4-unreadable-${System.nanoTime()}/home", treeJson = null, onProjected = {},
      kotlinFile = file, functionName = "HomeScreen", packageName = "app.screens",
    )
    return Fixture(file, svc)
  }

  private fun DocumentService.batch(vararg ops: DocOp) = OpBatch(doc.version, OpEnvelope("t", 0L), ops.toList())
  private fun DocumentService.child(i: Int) = (doc.root as DocNode.Widget).children[i] as DocNode.Widget
  private fun hi(field: String) = PropValue.Template(listOf(lit("Hi "), PropValue.Bind(field)))

  private fun awaitStatus(svc: DocumentService, version: Long): DocumentService.WriteBackStatus {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      svc.writeBack?.takeIf { it.version == version }?.let { return it }
      Thread.sleep(25)
    }
    return assertNotNull(svc.writeBack?.takeIf { it.version == version }, "write-back of v$version reported")
  }

  /** Submits [op] and waits for it to be written — it must be. */
  private fun written(f: Fixture, op: DocOp): String {
    val ack = f.svc.submit(f.svc.batch(op))
    assertTrue(ack.ok, ack.error)
    val status = awaitStatus(f.svc, ack.version)
    assertTrue(status.written, status.json())
    return f.file.readText()
  }

  /** The template on [field] is refused as an op (dry run and submit alike); nothing changes, nothing is wedged. */
  private fun assertRefusedThenNotWedged(f: Fixture, field: String, why: String) {
    val before = f.file.readText()
    val version = f.svc.doc.version
    val op = DocOp.SetProp(f.svc.child(0).handle, "text", hi(field))
    val dry = f.svc.dryRun(f.svc.batch(op))
    val sub = f.svc.submit(f.svc.batch(op))
    assertFalse(sub.ok, "accepted a template on `$field`")
    assertEquals(dry.error, sub.error)
    val msg = sub.error!!
    assertTrue("`$field`" in msg && "not readable here" in msg && why in msg && "bind it directly or make this change in code" in msg, msg)
    assertEquals(version, f.svc.doc.version)
    assertEquals(before, f.file.readText())
    assertNull(f.svc.writeBack, "nothing was written or refused")
    // The next edit is written: the screen is not blocked.
    assertTrue("text = \"z\"" in written(f, DocOp.SetProp(f.svc.child(1).handle, "text", lit("z"))))
  }

  @Test fun caseA_noInterfaceInTheFile() {
    val f = fixture(screen(tail = ""))
    assertRefusedThenNotWedged(f, "name", "the screen takes `HomeScreenBindings`, which is not declared in this file")
    // U37: a plain binding of a field the file does not read is refused too — the editor cannot
    // tell whether the interface declared elsewhere has it, and cannot declare it there.
    val plain = f.svc.submit(f.svc.batch(DocOp.SetProp(f.svc.child(0).handle, "text", PropValue.Bind("name"))))
    assertFalse(plain.ok, "accepted a binding of `name`")
    assertTrue("`name`" in plain.error!! && "not declared in this file" in plain.error!!, plain.error)
    // One of a field the file reads prints nothing and compiles as the file does: it is written.
    val g = fixture(screen(tail = "").replace("StyledText(text = \"y\")", "StyledText(text = b.name)"))
    assertTrue("text = b.name" in written(g, DocOp.SetProp(g.svc.child(0).handle, "text", PropValue.Bind("name"))))
  }

  @Test fun caseB_aFieldInheritedFromAnotherFile() {
    val f = fixture(screen(tail = bindings.replace("interface HomeScreenBindings {", "interface HomeScreenBindings : BaseBindings {")))
    assertRefusedThenNotWedged(f, "title", "`HomeScreenBindings` extends `BaseBindings`, declared in another file")
    // A field the interface declares here keeps its type: a template reads it.
    assertTrue("text = \"Hi \${b.name}\"" in written(f, DocOp.SetProp(f.svc.child(0).handle, "text", hi("name"))))
  }

  @Test fun caseE_aNewFieldBesideASupertypeFromAnotherFile() {
    val f = fixture(screen(tail = bindings.replace("interface HomeScreenBindings {", "interface HomeScreenBindings : BaseBindings {")))
    assertRefusedThenNotWedged(f, "fresh", "declared in another file")
  }

  @Test fun caseD_aNewFieldOfAnInterfaceReadWholeIsWrittenAndDeclared() {
    val f = fixture(screen())
    val text = written(f, DocOp.SetProp(f.svc.child(0).handle, "text", hi("fresh")))
    assertTrue("text = \"Hi \${b.fresh}\"" in text, text)
    assertTrue("val fresh: String" in text.substringAfter("interface HomeScreenBindings"), text)
  }

  @Test fun caseC_theInterfaceTheScreenTakesNotTheFirstInTheFile() {
    val f = fixture(screen(tail = "interface AOtherBindings {\n  val name: Double\n}\n\n$bindings"))
    assertEquals("String", f.svc.doc.contract.fields["name"])
    assertNull(f.svc.doc.contract.unlistedFieldsUnknown)
    val text = written(f, DocOp.SetProp(f.svc.child(0).handle, "text", hi("name")))
    assertTrue("text = \"Hi \${b.name}\"" in text, text)
    val fresh = written(f, DocOp.SetProp(f.svc.child(1).handle, "text", hi("fresh")))
    assertTrue("val fresh: String" in fresh.substringAfter("interface HomeScreenBindings"), fresh)
    assertTrue("fresh" !in fresh.substringAfter("interface AOtherBindings").substringBefore("}"), fresh)
  }

  @Test fun caseH_aNullableParameter() {
    val f = fixture(screen(param = "HomeScreenBindings?"))
    assertEquals("String", f.svc.doc.contract.fields["name"])
    assertNull(f.svc.doc.contract.unlistedFieldsUnknown)
    assertTrue("text = \"Hi \${b.name}\"" in written(f, DocOp.SetProp(f.svc.child(0).handle, "text", hi("name"))))
  }

  @Test fun theSignalFollowsTheFileThroughAnExternalChangeAndAReingest() {
    val f = fixture(screen())
    assertNull(f.svc.doc.contract.unlistedFieldsUnknown)
    // The author makes the interface extend one declared elsewhere: the watcher reads it.
    val open = screen(tail = bindings.replace("interface HomeScreenBindings {", "interface HomeScreenBindings : BaseBindings {"))
    f.file.writeText(open)
    assertEquals(DocumentService.FileChange.INGESTED, f.svc.fileChanged(open))
    assertNotNull(f.svc.doc.contract.unlistedFieldsUnknown)
    assertFalse(f.svc.dryRun(f.svc.batch(DocOp.SetProp(f.svc.child(0).handle, "text", hi("title")))).ok)
    // Back to an interface read whole, picked up by a re-read of the file as it is.
    f.file.writeText(screen())
    assertTrue(reingestWhenIdle(f.svc))
    assertNull(f.svc.doc.contract.unlistedFieldsUnknown)
    assertTrue(f.svc.dryRun(f.svc.batch(DocOp.SetProp(f.svc.child(0).handle, "text", hi("title")))).ok)
  }

  @Test fun undoAndRedoGoThroughTheSameCheck() {
    // A template the contract can type, inserted, deleted, and the delete undone and redone — written each time.
    val f = fixture(screen(tail = bindings.replace("interface HomeScreenBindings {", "interface HomeScreenBindings : BaseBindings {")))
    val node = DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to hi("name")))
    assertTrue("Hi \$" in written(f, DocOp.InsertNode(f.svc.doc.root.handle, null, node)))
    val inserted = f.svc.child(0).handle
    assertTrue("Hi \$" !in written(f, DocOp.DeleteNode(inserted)))
    for ((verb, step) in listOf("undo" to { f.svc.undo("t") }, "redo" to { f.svc.redo("t") }, "undo" to { f.svc.undo("t") })) {
      val ack = step()
      assertTrue(ack.ok, "$verb: ${ack.error}")
      assertTrue(awaitStatus(f.svc, ack.version).written, verb)
    }
    assertTrue("Hi \$" in f.file.readText(), f.file.readText())
    // The one the contract cannot type never reaches a stack.
    assertFalse(f.svc.submit(f.svc.batch(DocOp.InsertNode(f.svc.doc.root.handle, null, node.copy(props = mapOf("text" to hi("title"))))))
      .ok)
  }
}
