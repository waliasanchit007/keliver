import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.document.DocJson
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
 * K2 review: the relay's token gate (server-owned values checked AFTER they
 * are replaced, Long tokens, what a reference means in the target file),
 * event routing with a tokens dir above the screens, the scan cache, file
 * identity, undo after a declaration changed, older editors, and the
 * tokens-version change event.
 */
class DesignTokenGateTest {
  private val project = "k2gate-" + System.nanoTime()
  private val root: File = Files.createTempDirectory("k2-gate").toFile().apply { deleteOnExit() }
  private val screens = File(root, "screens").apply { mkdirs() }
  private val components = File(root, "components").apply { mkdirs() }
  private val homeFile = File(screens, "home.kt")

  private fun tokens(ink: String = "-14737633", extra: String = "") = """
    package app.components

    object C {
      const val Ink = $ink
      const val Title = "Funds"
      const val Big = 0xFFFFFFFF
      $extra
    }
  """.trimIndent() + "\n"

  private fun home(imports: String = "import app.components.C\n", body: String = "StyledText(text = \"a\", colorArgb = C.Ink, fontSize = 12)") = """
package app.screens

import androidx.compose.runtime.Composable
${imports}import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText

@Composable
fun HomeScreen() {
  Column {
    $body
  }
}
"""

  private val row = ComponentSpec(
    "MenuRow",
    listOf(PropSpec("title", PropKind.Text, "Title"), PropSpec("tint", PropKind.Int, "Tint")),
    emptyList(),
    mapOf("title" to "String", "tint" to "Int"),
  )

  private fun rebuild() = Tokens.rebuild(project, listOf(screens, components), root = root)

  private fun service(text: String = home()): DocumentService {
    File(components, "Tokens.kt").writeText(tokens())
    homeFile.writeText(text)
    rebuild()
    return DocumentService.fromFileOrTree(
      screenKey = "$project/home", treeJson = null, onProjected = {}, kotlinFile = homeFile,
      functionName = "HomeScreen", packageName = "app.screens",
      components = { MapComponentRegistry(listOf(row)) },
      tokens = { Tokens.table(project) },
      tokenFileId = Tokens.fileId(homeFile, root),
      tokensVersion = { Tokens.version(project) },
    )
  }

  private fun batch(svc: DocumentService, vararg ops: DocOp) = OpBatch(svc.doc.version, OpEnvelope("t", 0L), ops.toList())
  private fun text(svc: DocumentService) = (svc.doc.root as DocNode.Widget).children.single() as DocNode.Widget
  private fun token(ref: String, v: Any, import: String = "app.components.C") = PropValue.Token(ref, lit(v), import)

  private fun assertRefused(svc: DocumentService, op: DocOp, contains: String? = null) {
    val dry = svc.dryRun(batch(svc, op))
    val sub = svc.submit(batch(svc, op))
    assertFalse(dry.ok, "dryRun accepted $op")
    assertFalse(sub.ok, "submit accepted $op")
    assertEquals(dry.error, sub.error)
    contains?.let { assertTrue(it in dry.error!!, dry.error) }
  }

  private fun insertRow(svc: DocumentService, props: Map<String, PropValue>) =
    DocOp.InsertNode(svc.doc.root.handle, null, DocNode.Widget(Handle(0), "MenuRow", props))

  @Test fun theFitCheckSeesTheServersValueNotTheClients() {
    val svc = service()
    // Forged: the client claims C.Ink is a String; the server's value is an Int.
    assertRefused(svc, insertRow(svc, mapOf("title" to token("C.Ink", "x"))), "does not fit")
    // Stale kind: the client still thinks C.Title is an Int.
    assertRefused(svc, insertRow(svc, mapOf("tint" to token("C.Title", 5))), "does not fit")
    assertTrue(svc.dryRun(batch(svc, insertRow(svc, mapOf("title" to token("C.Title", "stale"))))).ok)
  }

  @Test fun aLongTokenIsNeverOfferedOrAccepted() {
    service()
    assertFalse("C.Big" in Tokens.toJson(project), Tokens.toJson(project))
    val svc = service()
    assertRefused(svc, DocOp.SetProp(text(svc).handle, "colorArgb", token("C.Big", 1)), "unknown design token")
  }

  @Test fun aTokenMustMeanTheChosenDeclarationInTheTargetFile() {
    // (a) the file imports a different C.
    File(root, "x").mkdirs()
    val svc = service(home(imports = "import x.C\n", body = "StyledText(text = \"a\")"))
    assertRefused(svc, DocOp.SetProp(text(svc).handle, "colorArgb", token("C.Ink", 0)), "imports a different C")
    // (b) the file's own package has an object C: importing app.components.C would rebind C.Muted.
    File(screens, "Local.kt").writeText("package app.screens\n\nobject C { const val Muted = 8 }\n")
    val svc2 = service(home(imports = "", body = "StyledText(text = \"a\", colorArgb = C.Muted)"))
    assertRefused(svc2, DocOp.SetProp(text(svc2).handle, "colorArgb", token("C.Ink", 0)), "would change what C means")
    File(screens, "Local.kt").delete()
    // Fine where it binds as chosen (here: added import, nothing rebound).
    val svc3 = service(home(imports = "", body = "StyledText(text = \"a\")"))
    assertTrue(svc3.dryRun(batch(svc3, DocOp.SetProp(text(svc3).handle, "colorArgb", token("C.Ink", 0)))).ok)
  }

  @Test fun aTokensDirAboveTheScreensStillRoutesScreenEditsAsScreens() {
    val src = root // a natural `tokensDirs: ["src/commonMain/kotlin"]` contains screens/ and components/
    val isComponent = { f: File -> f.parentFile == components }
    val isFlow = { f: File -> f.parentFile.name == "flows" }
    fun route(f: File) = routeKtEvent(f, isComponent, isFlow, listOf(screens), null, listOf(src))
    assertEquals(listOf(KtRoute.SCREEN), route(File(screens, "home.kt")))
    assertEquals(listOf(KtRoute.COMPONENT), route(File(components, "Tokens.kt")))
    assertEquals(listOf(KtRoute.FLOW, KtRoute.TOKENS), route(File(File(root, "flows"), "main.kt")))
    assertEquals(listOf(KtRoute.TOKENS), route(File(File(root, "theme"), "Colors.kt")))
    assertEquals(listOf(KtRoute.OTHER), routeKtEvent(File("/elsewhere/logic/P.kt"), isComponent, isFlow, listOf(screens), null, listOf(src)))
  }

  @Test fun onlyChangedFilesAreReparsedAndIdentitiesAreRepoRelative() {
    service()
    val before = Tokens.parses.get()
    assertFalse(rebuild())
    assertEquals(before, Tokens.parses.get(), "nothing changed: nothing re-parsed")
    val f = File(components, "Tokens.kt")
    f.writeText(tokens(ink = "5"))
    f.setLastModified(f.lastModified() + 2000)
    assertTrue(rebuild())
    assertEquals(before + 1, Tokens.parses.get(), "only the changed file is re-parsed")
    assertEquals("components/Tokens.kt", Tokens.fileId(f, root))
    assertTrue("\"file\":\"components/Tokens.kt\"" in Tokens.toJson(project))
  }

  @Test fun undoAfterADeclarationChangedReResolvesOrRefuses() {
    val svc = service()
    val node = text(svc)
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "colorArgb", lit(1)))).ok) // detach
    val f = File(components, "Tokens.kt")
    f.writeText(tokens(ink = "77"))
    f.setLastModified(f.lastModified() + 2000)
    rebuild()
    assertTrue(svc.undo("t").ok)
    assertEquals(token("C.Ink", 77), text(svc).props["colorArgb"], "the restored token carries today's value")
    // Gone since: the undo is refused rather than restoring a reference that no longer resolves.
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "colorArgb", lit(2)))).ok)
    f.writeText(tokens(ink = "77").replace("const val Ink = 77", ""))
    f.setLastModified(f.lastModified() + 4000)
    rebuild()
    val undone = svc.undo("t")
    assertFalse(undone.ok)
    assertTrue("no longer exists" in undone.error!!, undone.error)
  }

  @Test fun anOlderEditorGetsLiteralsAndItsEditsKeepTheFilesReferences() {
    val svc = service()
    val old = Tokens.withoutTokens(svc.doc)
    val json = DocJson.encodeToString(dev.keliver.portal.document.UiDocument.serializer(), old)
    assertFalse("PropValue.Token" in json, json)
    assertEquals(lit(-14737633), ((old.root as DocNode.Widget).children.single() as DocNode.Widget).props["colorArgb"])
    // The old editor edits another prop; the server document — and the file — keep C.Ink.
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(text(svc).handle, "fontSize", lit(14)))).ok)
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline && "fontSize = 14" !in homeFile.readText()) Thread.sleep(50)
    assertTrue("StyledText(text = \"a\", colorArgb = C.Ink, fontSize = 14)" in homeFile.readText(), homeFile.readText())
  }

  @Test fun eventsCarryTheTokensVersionAndAnnounceSendsOne() {
    val svc = service()
    val out = ByteArrayOutputStream()
    svc.subscribe(out)
    val v = Tokens.version(project)
    assertTrue("\"tokens\":$v" in out.toString(), out.toString())
    val f = File(components, "Tokens.kt")
    f.writeText(tokens(extra = "const val Added = 1"))
    f.setLastModified(f.lastModified() + 2000)
    assertTrue(rebuild())
    svc.announce()
    assertNotNull(Regex("\"tokens\":${v + 1}").find(out.toString()), out.toString())
  }

  // ── re-review ──

  private fun withAccent() = """
package app.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText

private const val Accent = 5

@Composable
fun HomeScreen() {
  Column {
    StyledText(text = "a", colorArgb = Accent, fontSize = 12)
  }
}
"""

  @Test fun undoRedoAndDuplicateWorkWithAFilePrivateConstant() {
    val svc = service(withAccent())
    val node = text(svc)
    assertEquals(token("Accent", 5, "app.screens.Accent"), node.props["colorArgb"])
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "fontSize", lit(14)))).ok)
    assertTrue(svc.undo("t").ok, "undo on a screen using a file-private constant")
    assertTrue(svc.redo("t").ok)
    // Duplicate (insert a copy carrying the token).
    val copy = DocOp.InsertNode(svc.doc.root.handle, node.handle, node.copy(handle = Handle(0)))
    val ack = svc.submit(batch(svc, copy))
    assertTrue(ack.ok, ack.error)
  }

  @Test fun aFailedUndoKeepsItsEntry() {
    val svc = service()
    val node = text(svc)
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "colorArgb", lit(1)))).ok) // detach C.Ink
    val f = File(components, "Tokens.kt")
    f.writeText(tokens().replace("const val Ink = -14737633", ""))
    rebuild()
    assertFalse(svc.undo("t").ok)
    f.writeText(tokens())
    rebuild()
    assertTrue(svc.undo("t").ok, "the entry survived the failed attempt")
    assertEquals(token("C.Ink", -14737633), text(svc).props["colorArgb"])
  }

  @Test fun anUndoNeverRestoresATokenThatNoLongerFits() {
    val svc = service()
    val node = text(svc)
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "colorArgb", lit(1)))).ok)
    File(components, "Tokens.kt").writeText(tokens(ink = "\"now a String\""))
    rebuild()
    val undone = svc.undo("t")
    assertFalse(undone.ok)
    assertTrue("no longer fits" in undone.error!!, undone.error)
  }

  @Test fun twoOwnersWithOneSimpleNameAreRefusedInABatchAndAcrossTheWriteWindow() {
    File(root, "x").mkdirs()
    File(root, "q").mkdirs()
    val svc = service(home(imports = "", body = "StyledText(text = \"a\")\n    StyledText(text = \"b\")"))
    File(screens, "X.kt").writeText("package x\nobject C { const val Ink = 9 }\n")
    File(components, "Q.kt").writeText("package q\nobject C { const val Muted = 8 }\n")
    rebuild()
    val (a, b) = (svc.doc.root as DocNode.Widget).children.map { it as DocNode.Widget }
    val xInk = token("C.Ink", 9, "x.C")
    val qMuted = token("C.Muted", 8, "q.C")
    // One batch, two owners named C: refused (the imports would clash).
    val both = batch(svc, DocOp.SetProp(a.handle, "colorArgb", xInk), DocOp.SetProp(b.handle, "colorArgb", qMuted))
    assertFalse(svc.dryRun(both).ok)
    assertFalse(svc.submit(both).ok)
    // Two batches inside the write-back window: the second sees the first's pending import.
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(a.handle, "colorArgb", xInk))).ok)
    val second = svc.submit(batch(svc, DocOp.SetProp(b.handle, "colorArgb", qMuted)))
    assertFalse(second.ok)
    assertTrue("different C" in second.error!!, second.error)
  }

  @Test fun aSameSizeEditWithTheSameMtimeIsReparsedAndADeletedFileForgotten() {
    service()
    val f = File(components, "Tokens.kt")
    val stamp = f.lastModified()
    f.writeText(tokens(ink = "-14737634")) // same length, one digit different
    f.setLastModified(stamp)
    assertTrue(rebuild(), "a content change is seen even when size and mtime are unchanged")
    assertEquals(-14737634, Tokens.table(project).entries.single { it.decl.ref == "C.Ink" }.value)
    f.delete()
    assertTrue(rebuild())
    assertTrue(Tokens.table(project).entries.none { it.decl.ref == "C.Ink" })
  }

  @Test fun aTokenNamedLikeALocalOfTheTargetScreenIsRefused() {
    // The screen binds `Ink` itself: a bare `Ink` written there would mean the local.
    val text = """
package app.screens

import androidx.compose.runtime.Composable
import dev.keliver.layout.compose.Column
import dev.keliver.material.compose.StyledText

@Composable
fun HomeScreen() {
  val Ink = 1
  Column {
    StyledText(text = "a")
  }
}
"""
    val svc = service(text)
    File(components, "Top.kt").writeText("package app.components\n\nconst val Ink = 3\n")
    rebuild()
    fun widgets(n: DocNode): List<DocNode.Widget> =
      (n as? DocNode.Widget)?.let { listOf(it) + it.children.flatMap(::widgets) }.orEmpty()
    val styled = widgets(svc.doc.root).first { it.type == "StyledText" }
    assertRefused(svc, DocOp.SetProp(styled.handle, "colorArgb", token("Ink", 3, "app.components.Ink")), "screen's own")
  }
}
