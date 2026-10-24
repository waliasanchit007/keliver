import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.OpEnvelope
import dev.keliver.portal.document.lit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * K3 review S-c through the relay: write-back knows which `if` an edited
 * Condition is from the document the file was last written from — also
 * after earlier writes have moved the document's handles away from a fresh
 * parse's.
 */
class ConditionIdentityRelayTest {
  private val home = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(text = "top")
        if (b.alpha) {
          // alpha comment
          StyledText(text = "A")
        }
        if (b.beta) {
          // beta comment
          StyledText(text = "B")
        }
      }
    }

    interface HomeScreenBindings {
      val alpha: Boolean
      val beta: Boolean
    }
  """.trimIndent() + "\n"

  private fun awaitFile(f: File, predicate: (String) -> Boolean): String {
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
      val t = f.readText()
      if (predicate(t)) return t
      Thread.sleep(50)
    }
    return f.readText()
  }

  @Test fun anEditedConditionKeepsItsIfAcrossWrites() {
    val dir = Files.createTempDirectory("k3-identity").toFile().apply { deleteOnExit() }
    val file = File(dir, "home.kt").apply { writeText(home) }
    val svc = DocumentService.fromFileOrTree(
      screenKey = "k3-identity-${System.nanoTime()}/home",
      treeJson = null,
      onProjected = {},
      kotlinFile = file,
      functionName = "HomeScreen",
      packageName = "app.screens",
    )
    fun batch(vararg ops: DocOp) = OpBatch(svc.doc.version, OpEnvelope("t", 0L), ops.toList())
    fun children() = (svc.doc.root as DocNode.Widget).children

    // A first write: the document's handles no longer number like a fresh parse of the file.
    val first = DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("first")))
    assertTrue(svc.submit(batch(DocOp.InsertNode(svc.doc.root.handle, null, first))).ok)
    val afterFirst = awaitFile(file) { "\"first\"" in it }
    assertTrue("\"first\"" in afterFirst, afterFirst)

    // Delete beta and edit alpha's condition to beta: alpha's if (with its comment) stays.
    val (alpha, beta) = children().filter { (it as DocNode.Widget).type == "Condition" }
    val ack = svc.submit(batch(DocOp.DeleteNode(beta.handle), DocOp.SetProp(alpha.handle, "field", lit("beta"))))
    assertTrue(ack.ok, ack.error)
    // (A read can land mid-write and see an empty file: wait for the whole new text.)
    val out = awaitFile(file) { "if (b.alpha)" !in it && "interface HomeScreenBindings" in it }
    assertEquals(
      afterFirst.replace(
        "    if (b.alpha) {\n      // alpha comment\n      StyledText(text = \"A\")\n    }\n" +
          "    if (b.beta) {\n      // beta comment\n      StyledText(text = \"B\")\n    }\n",
        "    if (b.beta) {\n      // alpha comment\n      StyledText(text = \"A\")\n    }\n",
      ),
      out,
    )
  }
}
