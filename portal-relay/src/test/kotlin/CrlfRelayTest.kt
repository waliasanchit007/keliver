import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.OpEnvelope
import dev.keliver.portal.document.lit
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** K3 review nit: a CRLF screen is ingested and written back surgically (edits and moves), staying CRLF. */
class CrlfRelayTest {
  private val home = (
    "package app.screens\n\nimport androidx.compose.runtime.Composable\nimport dev.keliver.layout.compose.Column\n" +
      "import dev.keliver.material.compose.StyledText\n\n@Composable\nfun HomeScreen(b: HomeScreenBindings) {\n  Column {\n" +
      "    // keep me\n    StyledText(text = \"top\")\n    if (b.open) {\n      StyledText(text = \"o\")\n    }\n  }\n}\n\n" +
      "interface HomeScreenBindings {\n  val open: Boolean\n}\n"
    ).replace("\n", "\r\n")

  private fun awaitFile(f: File, predicate: (String) -> Boolean): String {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      val t = f.readText()
      if (predicate(t)) return t
      Thread.sleep(50)
    }
    return f.readText()
  }

  @Test fun aCrlfScreenIsEditedInPlaceAndStaysCrlf() {
    val dir = Files.createTempDirectory("k3-crlf").toFile().apply { deleteOnExit() }
    val file = File(dir, "home.kt").apply { writeText(home) }
    val svc = DocumentService.fromFileOrTree(
      screenKey = "k3-crlf-${System.nanoTime()}/home",
      treeJson = null,
      onProjected = {},
      kotlinFile = file,
      functionName = "HomeScreen",
      packageName = "app.screens",
    )
    val top = (svc.doc.root as DocNode.Widget).children.first() as DocNode.Widget
    assertEquals("StyledText", top.type, svc.doc.root.toString())
    val ack = svc.submit(OpBatch(svc.doc.version, OpEnvelope("t", 0L), listOf(DocOp.SetProp(top.handle, "text", lit("TOP")))))
    assertTrue(ack.ok, ack.error)
    assertEquals(home.replace("\"top\"", "\"TOP\""), awaitFile(file) { "TOP" in it })
    // A reorder is a surgical move (the comment stays with its statement) — CRLF too.
    val cond = (svc.doc.root as DocNode.Widget).children[1]
    val ack2 = svc.submit(OpBatch(svc.doc.version, OpEnvelope("t", 0L), listOf(DocOp.MoveNode(cond.handle, svc.doc.root.handle, null))))
    assertTrue(ack2.ok, ack2.error)
    val out = awaitFile(file) { it.indexOf("if (b.open)") in 0 until it.indexOf("// keep me") }
    val ifLines = "    if (b.open) {\r\n      StyledText(text = \"o\")\r\n    }\r\n"
    assertEquals(
      home.replace("\"top\"", "\"TOP\"").replace(ifLines, "").replace("    // keep me\r\n", ifLines + "    // keep me\r\n"),
      out,
    )
    assertTrue(allCrlf(out), out.replace("\r", "\\r"))
  }

  /** Every line ends in CRLF. */
  private fun allCrlf(text: String) = text.count { it == '\n' } == Regex("\r\n").findAll(text).count()
}
