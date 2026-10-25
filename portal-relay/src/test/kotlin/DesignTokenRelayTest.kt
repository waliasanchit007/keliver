import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K2: the relay's design-token registry — built from the scanned sources,
 * served at /tokens, refreshed when a declaration changes (the open document
 * re-resolves, or the reference turns RawCode), and the gate that owns token
 * values.
 */
class DesignTokenRelayTest {
  private val project = "k2-" + System.nanoTime()

  private fun app(): Triple<File, File, File> {
    val root = Files.createTempDirectory("k2-app").toFile().apply { deleteOnExit() }
    val screens = File(root, "screens").apply { mkdirs() }
    val components = File(root, "components").apply { mkdirs() }
    File(components, "Tokens.kt").writeText(tokens(ink = "-14737633"))
    File(screens, "home.kt").writeText(home)
    return Triple(root, screens, components)
  }

  private fun tokens(ink: String) = """
    package app.components

    object C {
      const val White = -1
      const val Ink = $ink
      const val Title = "Funds"
      private const val Secret = 3
    }
  """.trimIndent() + "\n"

  private val home = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.C
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen() {
      Column {
        // Brand colour comes from the design tokens.
        StyledText(text = C.Title, colorArgb = C.Ink, fontSize = 12)
      }
    }
  """.trimIndent() + "\n"

  private val projected = mutableListOf<WidgetNode>()

  private fun service(screens: File, components: File, registry: MapComponentRegistry = MapComponentRegistry(emptyList())): DocumentService {
    Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile)
    return DocumentService.fromFileOrTree(
      screenKey = "$project/home",
      treeJson = null,
      onProjected = { projected += it },
      kotlinFile = File(screens, "home.kt"),
      functionName = "HomeScreen",
      packageName = "app.screens",
      components = { registry },
      tokens = { Tokens.table(project) },
      tokenFileId = "screens/home.kt",
      tokensVersion = { Tokens.version(project) },
    )
  }

  private fun text(svc: DocumentService) = (svc.doc.root as DocNode.Widget).children.single() as DocNode
  private fun batch(svc: DocumentService, vararg ops: DocOp) = OpBatch(svc.doc.version, OpEnvelope("t", 0L), ops.toList())

  private fun awaitFile(f: File, predicate: (String) -> Boolean): String {
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
      val t = f.readText()
      if (predicate(t)) return t
      Thread.sleep(50)
    }
    return f.readText()
  }

  @Test fun theRegistryListsExportedTokensWithTheirValueAndImport() {
    val (_, screens, components) = app()
    assertTrue(Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile))
    assertFalse(Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile), "an unchanged rescan reports no change")
    val json = Tokens.toJson(project)
    assertTrue(
      "{\"ref\":\"C.Ink\",\"owner\":\"C\",\"name\":\"Ink\",\"kind\":\"Int\",\"value\":-14737633,\"const\":true," +
        "\"package\":\"app.components\",\"import\":\"app.components.C\",\"file\":\"components/Tokens.kt\",\"ambiguous\":false}" in json,
      json,
    )
    assertTrue("\"ref\":\"C.Title\"" in json && "\"value\":\"Funds\"" in json, json)
    assertFalse("Secret" in json, "a private constant is never offered: $json")
  }

  @Test fun theDocumentCarriesTokensAndADeclarationChangeReResolvesIt() {
    val (_, screens, components) = app()
    val svc = service(screens, components)
    val node = text(svc) as DocNode.Widget
    assertEquals(PropValue.Token("C.Ink", lit(-14737633), "app.components.C"), node.props["colorArgb"])
    // The declaration changes; the screen file does not.
    File(components, "Tokens.kt").writeText(tokens(ink = "-16777216"))
    assertTrue(Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile))
    assertTrue(reingestWhenIdle(svc))
    val updated = text(svc) as DocNode.Widget
    assertEquals(node.handle, updated.handle, "the node keeps its identity")
    assertEquals(PropValue.Token("C.Ink", lit(-16777216), "app.components.C"), updated.props["colorArgb"])
    // The canvas/device projection renders the new value.
    assertEquals(Token("C.Ink", -16777216, "app.components.C"), projected.last().children.single().props["colorArgb"])
    // A token that changes kind (or disappears) makes the argument RawCode — never silently wrong.
    File(components, "Tokens.kt").writeText(tokens(ink = "\"#1F1F1F\""))
    assertTrue(Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile))
    assertTrue(reingestWhenIdle(svc))
    assertIs<DocNode.RawCode>(text(svc))
    File(components, "Tokens.kt").delete()
    Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile)
    assertTrue(reingestWhenIdle(svc))
    assertIs<DocNode.RawCode>(text(svc))
  }

  /**
   * K3 fifth review, S-c: a token value changing while an edit waits for its
   * write leaves the FILE as it was: the edit is written onto it, not
   * discarded as if the file had changed.
   */
  @Test fun aTokenValueChangedBetweenAnEditAndItsWriteDoesNotDiscardTheEdit() {
    val (_, screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    val node = text(svc) as DocNode.Widget
    val ack = svc.submit(batch(svc, DocOp.SetProp(node.handle, "fontSize", lit(14))))
    assertTrue(ack.ok, ack.error)
    // Within the write's 400 ms: the declaration changes (the relay's re-read waits for the write).
    File(components, "Tokens.kt").writeText(tokens(ink = "-16777216"))
    assertTrue(Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile))
    val deadline = System.currentTimeMillis() + 30_000
    while (svc.writeBack == null && System.currentTimeMillis() < deadline) Thread.sleep(25)
    val status = assertNotNull(svc.writeBack)
    assertEquals("surgical", status.outcome, status.toString())
    assertEquals(home.replace("fontSize = 12", "fontSize = 14"), file.readText())
    // The re-read that follows takes the new value and loses nothing.
    assertTrue(reingestWhenIdle(svc))
    val now = text(svc) as DocNode.Widget
    assertEquals(lit(14), now.props["fontSize"])
    assertEquals(PropValue.Token("C.Ink", lit(-16777216), "app.components.C"), now.props["colorArgb"])
    assertTrue(assertNotNull(svc.writeBack).written, svc.writeBack.toString())
  }

  @Test fun anEditBesideATokenWritesBackSurgicallyAndATokenInsertAddsItsImport() {
    val (_, screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    val node = text(svc) as DocNode.Widget
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "fontSize", lit(14)))).ok)
    val written = awaitFile(file) { "fontSize = 14" in it }
    assertEquals(home.replace("fontSize = 12", "fontSize = 14"), written)

    // A token inserted into a file that lacks its import gets it.
    val bare = home.replace("import app.components.C\n", "").replace("text = C.Title, colorArgb = C.Ink, ", "text = \"t\", ")
    file.writeText(bare)
    assertTrue(reingestWhenIdle(svc))
    val plain = text(svc) as DocNode.Widget
    val client = PropValue.Token("C.Ink", lit(0), "app.components.C") // a stale client value
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(plain.handle, "colorArgb", client))).ok)
    // The server owns the value: the stale client value was replaced by the table's.
    assertEquals(PropValue.Token("C.Ink", lit(-14737633), "app.components.C"), (text(svc) as DocNode.Widget).props["colorArgb"])
    val withImport = awaitFile(file) { "C.Ink" in it }
    assertTrue("StyledText(text = \"t\", fontSize = 12, colorArgb = C.Ink)" in withImport, withImport)
    assertTrue("import app.components.C\n" in withImport, withImport)
    assertEquals(1, Regex("import app\\.components\\.C\\n").findAll(withImport).count())
  }

  @Test fun theGateRefusesUnknownPrivateOrMisfittingTokensInDryRunAndSubmitAlike() {
    val (_, screens, components) = app()
    val row = ComponentSpec("MenuRow", listOf(PropSpec("title", PropKind.Text, "Title")), emptyList(), mapOf("title" to "String"))
    val svc = service(screens, components, MapComponentRegistry(listOf(row)))
    val node = text(svc) as DocNode.Widget
    val cases = listOf(
      DocOp.SetProp(node.handle, "colorArgb", PropValue.Token("C.Nope", lit(1), "app.components.C")),
      DocOp.SetProp(node.handle, "colorArgb", PropValue.Token("C.Secret", lit(3), "app.components.C")),
      DocOp.SetProp(node.handle, "colorArgb", PropValue.Token("C.Ink", lit(-14737633), "other.C")),
      DocOp.InsertNode(svc.doc.root.handle, null, DocNode.Widget(Handle(0), "MenuRow", mapOf("title" to PropValue.Token("C.Ink", lit(-14737633), "app.components.C")))),
    )
    for (op in cases) {
      val dry = svc.dryRun(batch(svc, op))
      val sub = svc.submit(batch(svc, op))
      assertFalse(dry.ok, "dryRun accepted $op")
      assertFalse(sub.ok, "submit accepted $op")
      assertEquals(dry.error, sub.error)
      assertNotNull(dry.error)
    }
    // The same component takes a token of its parameter's type.
    val ok = DocOp.InsertNode(svc.doc.root.handle, null, DocNode.Widget(Handle(0), "MenuRow", mapOf("title" to PropValue.Token("C.Title", lit("x"), "app.components.C"))))
    assertTrue(svc.dryRun(batch(svc, ok)).ok)
  }
}
