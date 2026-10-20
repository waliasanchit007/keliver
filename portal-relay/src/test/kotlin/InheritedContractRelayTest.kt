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
 * U37, through the relay: a screen whose Bindings interface extends one
 * declared in another file (`interface HomeScreenBindings : BaseBindings`).
 * Any edit of the screen made contract write-back declare, in the screen's
 * interface, each inherited field or action the screen reads
 * (`/** TODO(portal) */ val title: String get() = ""`) — a redeclaration
 * without `override`, so the written file no longer compiled. Each written
 * file here is compiled for real, beside the other file's `BaseBindings`.
 *
 * And what the editor cannot tell — whether a field the file neither
 * declares nor reads is inherited or new — it does not write: the op is
 * refused when it is made, with the reason, and nothing is wedged.
 */
class InheritedContractRelayTest {
  private val base = """
    package app.screens

    interface BaseBindings {
      val title: String
      fun open()
    }
  """.trimIndent() + "\n"

  private fun screen(vararg statements: String, tail: String = "interface HomeScreenBindings : BaseBindings {\n  val name: String\n}\n") = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.ListItem
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
  """.trimIndent() + "\n" + statements.joinToString("") { "    $it\n" } + "  }\n}\n\n" + tail

  private class Fixture(val file: File, val svc: DocumentService)

  private fun fixture(text: String): Fixture {
    val dir = Files.createTempDirectory("u37-inherited").toFile().apply { deleteOnExit() }
    val file = File(dir, "home.kt").apply { writeText(text) }
    val svc = DocumentService.fromFileOrTree(
      screenKey = "u37-inherited-${System.nanoTime()}/home", treeJson = null, onProjected = {},
      kotlinFile = file, functionName = "HomeScreen", packageName = "app.screens",
    )
    return Fixture(file, svc)
  }

  private fun DocumentService.batch(vararg ops: DocOp) = OpBatch(doc.version, OpEnvelope("t", 0L), ops.toList())
  private fun DocumentService.child(i: Int) = (doc.root as DocNode.Widget).children[i] as DocNode.Widget

  private fun awaitStatus(svc: DocumentService, version: Long): DocumentService.WriteBackStatus {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      svc.writeBack?.takeIf { it.version == version }?.let { return it }
      Thread.sleep(25)
    }
    return assertNotNull(svc.writeBack?.takeIf { it.version == version }, "write-back of v$version reported")
  }

  private fun written(f: Fixture, op: DocOp): String {
    val ack = f.svc.submit(f.svc.batch(op))
    assertTrue(ack.ok, ack.error)
    val status = awaitStatus(f.svc, ack.version)
    assertTrue(status.written, status.json())
    return f.file.readText()
  }

  /** [text] compiles beside the other file's `BaseBindings`, and declares none of its members again. */
  private fun compilesWithoutRedeclaring(text: String) {
    val iface = text.substringAfter("interface HomeScreenBindings")
    assertTrue("val title" !in iface && "fun open" !in iface && "TODO(portal)" !in text, text)
    assertNull(KotlinCompileCheck.errors(mapOf("home.kt" to text, "BaseBindings.kt" to base)), text)
  }

  @Test fun theReviewersReproduction_anyEditOfTheScreenStillCompiles() {
    val f = fixture(screen("StyledText(text = b.title)", "StyledText(text = \"y\")"))
    compilesWithoutRedeclaring(f.file.readText())
    val text = written(f, DocOp.SetProp(f.svc.child(1).handle, "text", lit("z")))
    assertTrue("text = \"z\"" in text, text)
    compilesWithoutRedeclaring(text)
  }

  @Test fun anInheritedActionIsNotRedeclared() {
    val f = fixture(screen("ListItem(headline = b.name, onClick = { b.open() })", "StyledText(text = \"y\")"))
    val text = written(f, DocOp.SetProp(f.svc.child(1).handle, "text", lit("z")))
    compilesWithoutRedeclaring(text)
  }

  @Test fun deletingTheOnlyReadOfAnInheritedFieldAndUndoingItIsWritten() {
    val f = fixture(screen("StyledText(text = b.title)", "StyledText(text = \"y\")"))
    val gone = written(f, DocOp.DeleteNode(f.svc.child(0).handle))
    assertTrue("b.title" !in gone, gone)
    compilesWithoutRedeclaring(gone)
    // The file read `b.title` when the relay read it: the undo brings it back, and it is written.
    val ack = f.svc.undo("t")
    assertTrue(ack.ok, ack.error)
    assertTrue(awaitStatus(f.svc, ack.version).written)
    val back = f.file.readText()
    assertTrue("text = b.title" in back, back)
    compilesWithoutRedeclaring(back)
  }

  @Test fun aFieldTheFileNeitherDeclaresNorReadsIsRefusedWhenTheOpIsMade() {
    val f = fixture(screen("StyledText(text = b.title)", "StyledText(text = \"y\")"))
    val before = f.file.readText()
    val version = f.svc.doc.version
    val op = DocOp.SetProp(f.svc.child(1).handle, "text", PropValue.Bind("fresh"))
    val dry = f.svc.dryRun(f.svc.batch(op))
    val sub = f.svc.submit(f.svc.batch(op))
    assertFalse(sub.ok, "accepted a binding of `fresh`")
    assertEquals(dry.error, sub.error)
    val msg = sub.error!!
    assertTrue("`fresh`" in msg && "`HomeScreenBindings` extends `BaseBindings`, declared in another file" in msg && "make this change in code" in msg, msg)
    assertEquals(version, f.svc.doc.version)
    assertEquals(before, f.file.readText())
    assertNull(f.svc.writeBack, "nothing was written or refused")
    // Not wedged: the next edit is written.
    compilesWithoutRedeclaring(written(f, DocOp.SetProp(f.svc.child(1).handle, "text", lit("z"))))
    // An action the file neither declares nor calls, the same (a component's required event starts so).
    val item = DocNode.Widget(Handle(0), "ListItem", mapOf("headline" to lit("h"), "onClick" to PropValue.Action("tapped")))
    assertFalse(f.svc.submit(f.svc.batch(DocOp.InsertNode(f.svc.doc.root.handle, null, item))).ok)
  }

  @Test fun aSupertypeInTheSameFileIsReadWhole_aNewFieldIsDeclaredAndNothingInherited() {
    val tail = "interface ScreenBase {\n  val title: String\n  fun open()\n}\n\ninterface HomeScreenBindings : ScreenBase {\n  val name: String\n}\n"
    val f = fixture(screen("StyledText(text = b.title)", "ListItem(headline = b.name, onClick = { b.open() })", "StyledText(text = \"y\")", tail = tail))
    assertNull(f.svc.doc.contract.unlistedFieldsUnknown)
    val text = written(f, DocOp.SetProp(f.svc.child(2).handle, "text", PropValue.Bind("fresh")))
    val iface = text.substringAfter("interface HomeScreenBindings")
    assertTrue("val fresh: String get() = \"\"" in iface, text)
    assertTrue("val title" !in iface && "fun open" !in iface, text)
    assertNull(KotlinCompileCheck.errors(mapOf("home.kt" to text)), text)
  }
}
