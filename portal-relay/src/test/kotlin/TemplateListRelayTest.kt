import dev.keliver.portal.ComponentSpec
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
import dev.keliver.portal.document.withoutLists
import dev.keliver.portal.document.withoutTemplates
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K4: templates and lists with references through the relay — ingested from
 * the file, edited by ops that write back only the changed part or element,
 * undone, gated (a token part or element is the server's and must mean its
 * declaration in the file; a component's signature decides where a template
 * fits) and served to an older editor as text / a resolved list.
 */
class TemplateListRelayTest {
  private val project = "k4-" + System.nanoTime()

  // § is `$`.
  private val home = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.C
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledBox
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(text = "Rating: §{b.rating} §{C.Unit}")
        StyledBox(gradientColorsArgb = listOf(C.Start, -1)) {
          StyledText(text = "x")
        }
      }
    }

    interface HomeScreenBindings {
      val rating: String
    }
  """.trimIndent().replace('§', '$') + "\n"

  private fun app(): Pair<File, File> {
    val root = Files.createTempDirectory("k4-app").toFile().apply { deleteOnExit() }
    val screens = File(root, "screens").apply { mkdirs() }
    val components = File(root, "components").apply { mkdirs() }
    File(components, "Tokens.kt").writeText("package app.components\n\nobject C {\n  const val Start = -2840\n  const val End = -5674\n  const val Unit = \"pts\"\n  const val Max = 5\n}\n")
    File(screens, "home.kt").writeText(home)
    return screens to components
  }

  private val row = ComponentSpec(
    "MenuRow",
    listOf(PropSpec("title", PropKind.Text, "Title"), PropSpec("tint", PropKind.Int, "Tint")),
    emptyList(),
    mapOf("title" to "String", "tint" to "Int"),
  )

  private fun service(screens: File, components: File): DocumentService {
    Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile)
    return DocumentService.fromFileOrTree(
      screenKey = "$project/home", treeJson = null, onProjected = {}, kotlinFile = File(screens, "home.kt"),
      functionName = "HomeScreen", packageName = "app.screens",
      components = { MapComponentRegistry(listOf(row)) },
      tokens = { Tokens.table(project) },
      tokenFileId = "screens/home.kt",
      tokensVersion = { Tokens.version(project) },
    )
  }

  private fun child(svc: DocumentService, i: Int) = (svc.doc.root as DocNode.Widget).children[i] as DocNode.Widget
  private fun batch(svc: DocumentService, vararg ops: DocOp) = OpBatch(svc.doc.version, OpEnvelope("t", 0L), ops.toList())
  private fun token(ref: String, v: Any, import: String = "app.components.C") = PropValue.Token(ref, lit(v), import)

  private fun awaitFile(f: File, predicate: (String) -> Boolean): String {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      val t = f.readText()
      if (predicate(t)) return t
      Thread.sleep(50)
    }
    return f.readText()
  }

  @Test fun aPartEditWritesBackOnlyThatPartAndUndoRestoresIt() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    val t = assertIs<PropValue.Template>(child(svc, 0).props["text"])
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(child(svc, 0).handle, "text", t.copy(parts = listOf(lit("Score: ")) + t.parts.drop(1))))).ok)
    assertEquals(home.replace("\"Rating: ", "\"Score: "), awaitFile(file) { "Score" in it })
    assertTrue(svc.undo("t").ok)
    assertEquals(home, awaitFile(file) { "Rating" in it })

    val g = assertIs<PropValue.ListOf>(child(svc, 1).props["gradientColorsArgb"])
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(child(svc, 1).handle, "gradientColorsArgb", g.copy(items = listOf(g.items[0], token("C.End", 0)))))).ok)
    assertEquals(home.replace("listOf(C.Start, -1)", "listOf(C.Start, C.End)"), awaitFile(file) { "C.End" in it })
    // The server's value replaced the client's.
    assertEquals(token("C.End", -5674), (child(svc, 1).props["gradientColorsArgb"] as PropValue.ListOf).items[1])
  }

  @Test fun tokensInsideAreTheServersAndMustExistAndFit() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val text = child(svc, 0)
    val t = text.props["text"] as PropValue.Template
    val unknown = t.copy(parts = t.parts + token("C.Nope", 1))
    assertNotNull(svc.dryRun(batch(svc, DocOp.SetProp(text.handle, "text", unknown))).error)
    // An Int token prints into a template; a String token does not fit an Int list.
    assertTrue(svc.dryRun(batch(svc, DocOp.SetProp(text.handle, "text", t.copy(parts = t.parts + token("C.Max", 0))))).ok)
    val bad = PropValue.ListOf("li", listOf(token("C.Unit", 1), lit(2)))
    val err = svc.dryRun(batch(svc, DocOp.SetProp(child(svc, 1).handle, "gradientColorsArgb", bad))).error
    assertTrue(err != null && "does not fit" in err, err)
  }

  @Test fun aTokenInsideMustMeanItsDeclarationInTheFile() {
    val (screens, components) = app()
    File(screens, "Local.kt").writeText("package x\n\nobject C { const val Muted = 8 }\n")
    val file = File(screens, "home.kt")
    file.writeText(home.replace("import app.components.C\n", "import x.C\n").replace(" \${C.Unit}", "").replace("listOf(C.Start, -1)", "listOf(1, -1)"))
    val svc = service(screens, components)
    val t = child(svc, 0).props["text"] as PropValue.Template
    val err = svc.dryRun(batch(svc, DocOp.SetProp(child(svc, 0).handle, "text", t.copy(parts = t.parts + token("C.Unit", "")))) ).error
    assertTrue(err != null && "imports a different C" in err, err)
    val err2 = svc.dryRun(batch(svc, DocOp.SetProp(child(svc, 1).handle, "gradientColorsArgb", PropValue.ListOf("li", listOf(token("C.End", 0)))))).error
    assertTrue(err2 != null && "imports a different C" in err2, err2)
  }

  @Test fun aComponentTakesATemplateOnlyOnAStringParameter() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val tmpl = PropValue.Template(listOf(lit("Hi "), PropValue.Bind("rating")))
    fun insert(props: Map<String, PropValue>) = DocOp.InsertNode(svc.doc.root.handle, null, DocNode.Widget(Handle(0), "MenuRow", props))
    assertTrue(svc.dryRun(batch(svc, insert(mapOf("title" to tmpl)))).ok)
    val err = svc.dryRun(batch(svc, insert(mapOf("tint" to tmpl)))).error
    assertTrue(err != null && "fits only a String parameter" in err, err)
    val err2 = svc.dryRun(batch(svc, insert(mapOf("title" to PropValue.ListOf("ls", listOf(PropValue.Bind("x"))))))).error
    assertTrue(err2 != null && "list" in err2, err2)
  }

  @Test fun anOlderEditorGetsTextAndAResolvedListAndItsEditsKeepTheReferences() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    val old = Tokens.withoutTokens(svc.doc.withoutTemplates().withoutLists())
    assertEquals(lit("Rating: {rating} pts"), (old.root as DocNode.Widget).children[0].let { (it as DocNode.Widget).props["text"] })
    assertEquals(PropValue.Lit("li", li = listOf(-2840, -1)), (old.root as DocNode.Widget).children[1].let { (it as DocNode.Widget).props["gradientColorsArgb"] })
    // A client with templates but no tokens: the token part is text.
    assertEquals(PropValue.Template(listOf(lit("Rating: "), PropValue.Bind("rating"), lit(" pts"))), (Tokens.withoutTokens(svc.doc).root as DocNode.Widget).children[0].let { (it as DocNode.Widget).props["text"] })
    // Its edit to another prop leaves the template and the list in the file.
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(child(svc, 0).handle, "fontSize", lit(14)))).ok)
    val written = awaitFile(file) { "fontSize" in it }
    assertTrue("\"Rating: \${b.rating} \${C.Unit}\", fontSize = 14" in written, written)
    assertTrue("listOf(C.Start, -1)" in written, written)
  }

  @Test fun theDocRouteServesEachKindOnlyToAClientThatAsks() {
    val (screens, components) = app()
    val svc = service(screens, components)
    fun text(q: Map<String, String>) = (docView(svc.doc, q).root as DocNode.Widget).children[0].let { (it as DocNode.Widget).props["text"] }
    fun list(q: Map<String, String>) = (docView(svc.doc, q).root as DocNode.Widget).children[1].let { (it as DocNode.Widget).props["gradientColorsArgb"] }
    val all = mapOf("tokens" to "1", "conditionals" to "1", "templates" to "1", "lists" to "1")
    assertIs<PropValue.Template>(text(all))
    assertIs<PropValue.ListOf>(list(all))
    assertEquals(lit("Rating: {rating} pts"), text(all - "templates"))
    assertEquals(PropValue.Lit("li", li = listOf(-2840, -1)), list(all - "lists"))
    // An editor of K3 (tokens + conditionals only) is served neither.
    assertEquals(lit("Rating: {rating} pts"), text(mapOf("tokens" to "1", "conditionals" to "1")))
    assertFalse(list(mapOf("tokens" to "1")) is PropValue.ListOf)
  }

  @Test fun aRedoReReadsATokenPartsValueAndIsBlockedWhenItIsGone() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val node = child(svc, 0)
    val t = node.props["text"] as PropValue.Template
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "text", t.copy(parts = t.parts + lit("/") + token("C.Max", 0))))).ok)
    assertTrue(svc.undo("t").ok)
    val tokensKt = File(components, "Tokens.kt")
    tokensKt.writeText(tokensKt.readText().replace("Max = 5", "Max = 6"))
    Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile)
    assertTrue(svc.redo("t").ok)
    assertEquals(token("C.Max", 6), (child(svc, 0).props["text"] as PropValue.Template).parts.last())
    assertTrue(svc.undo("t").ok)
    tokensKt.writeText(tokensKt.readText().replace("  const val Max = 6\n", ""))
    Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile)
    val redo = svc.redo("t")
    assertFalse(redo.ok)
    assertTrue("C.Max no longer exists" in redo.error!!, redo.error)
  }
}
