import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.OpEnvelope
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E14: a component opened for editing is a document of its own — its body, its parameters as
 * the contract — and an edit is written to the component's file, surgically; an edit that would
 * change what its callers pass is refused before it is applied.
 */
class ComponentDocumentRelayTest {
  private val source = """
    package app.components

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun LabelPill(label: String) {
      Column {
        StyledText(text = label, fontSize = 12)
        StyledText(text = "New", fontSize = 10)
      }
    }
  """.trimIndent() + "\n"

  private fun open(text: String = source): Pair<DocumentService, java.io.File> {
    val dir = Files.createTempDirectory("component-doc").toFile().apply { deleteOnExit() }
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

  private fun awaitWrite(svc: DocumentService, version: Long): DocumentService.WriteBackStatus {
    val until = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < until) {
      svc.writeBack?.takeIf { it.version == version }?.let { return it }
      Thread.sleep(25)
    }
    return assertNotNull(svc.writeBack?.takeIf { it.version == version }, "write-back of v$version reported")
  }

  @Test fun anEditIsWrittenToTheComponentsFile() {
    val (svc, f) = open()
    assertEquals(mapOf("label" to "String"), svc.doc.contract.fields)
    val ack = svc.submit(OpBatch(svc.doc.version, OpEnvelope("editor", 0L), listOf(DocOp.SetProp(children(svc)[1].handle, "text", lit("Hot")))))
    assertTrue(ack.ok, ack.error)
    val status = awaitWrite(svc, svc.doc.version)
    assertEquals("surgical", status.outcome, status.message)
    assertEquals(source.replace("\"New\"", "\"Hot\""), f.readText())
    assertEquals(DocumentService.FileChange.SELF_WRITE, svc.fileChanged(f.readText()))
    // E20: what Export shows is the file itself, byte for byte.
    assertEquals(f, svc.file)
    assertEquals(f.readText(), svc.sourceText())
    // E43: a component's file has no compiled-version stamp beside it.
    assertEquals(listOf("LabelPill.kt"), f.parentFile.list()!!.sorted())
  }

  @Test fun anEditThatChangesTheSignatureIsRefusedAndTheFileIsLeftAsItIs() {
    val (svc, f) = open()
    val contract = svc.doc.contract.copy(fields = mapOf("label" to "String", "count" to "Int"))
    val ack = svc.submit(OpBatch(svc.doc.version, OpEnvelope("editor", 0L), listOf(DocOp.ContractEdit(contract))))
    assertFalse(ack.ok)
    assertTrue("parameters" in ack.error.orEmpty(), ack.error)
    assertEquals(source, f.readText())
  }

  @Test fun anAuthorsSaveIsReadAsTheDocument() {
    val (svc, f) = open()
    val saved = source.replace("fontSize = 10", "fontSize = 9")
    f.writeText(saved)
    assertEquals(DocumentService.FileChange.INGESTED, svc.fileChanged(saved))
    assertEquals(lit(9), (children(svc)[1] as DocNode.Widget).props["fontSize"])
    assertEquals(PropValue.Bind("label"), (children(svc)[0] as DocNode.Widget).props["text"])
  }

  @Test fun aComponentWithoutAReadableBodyIsNotOpened() {
    val dir = Files.createTempDirectory("component-doc").toFile().apply { deleteOnExit() }
    val f = dir.resolve("Opaque.kt").apply { writeText("package a\n\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun Opaque(label: String) = Unit\n") }
    assertNull(
      DocumentService.forComponent(
        "default/component:Opaque", f, "Opaque", { dev.keliver.portal.EmptyComponentRegistry },
        { dev.keliver.portal.ingest.TokenTable.EMPTY }, "app/components/Opaque.kt", { 0L },
      ),
    )
  }

  @Test fun aFileWhoseComponentIsAnotherIsNotOpenedAsThisOne() {
    val dir = Files.createTempDirectory("component-doc").toFile().apply { deleteOnExit() }
    val f = dir.resolve("LabelPill.kt").apply { writeText(source.replace("fun LabelPill(", "fun OtherPill(")) }
    assertNull(
      DocumentService.forComponent(
        "default/component:LabelPill", f, "LabelPill", { dev.keliver.portal.EmptyComponentRegistry },
        { dev.keliver.portal.ingest.TokenTable.EMPTY }, "app/components/LabelPill.kt", { 0L },
      ),
      "the file's component is OtherPill: editing it as LabelPill would write another function",
    )
  }
}
