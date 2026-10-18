import dev.keliver.portal.ComponentEventSpec
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
import dev.keliver.portal.document.withoutConditionals
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K3: conditional values through the relay — ingested from the file, edited by
 * ops that write back only the changed branch, undone, gated (tokens in a
 * branch are the server's, a component's signature decides what a branch may
 * be) and served to an editor that predates them as their true branch.
 */
class ConditionalRelayTest {
  private val project = "k3-" + System.nanoTime()

  private val home = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.C
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(text = if (b.open) "Open" else "Closed", colorArgb = if (b.selected) C.Ink else C.White)
        if (!b.hidden) {
          StyledText(text = "shown")
        } else {
          StyledText(text = "hidden")
        }
      }
    }

    interface HomeScreenBindings {
      val open: Boolean
      val selected: Boolean
      val hidden: Boolean
    }
  """.trimIndent() + "\n"

  private fun app(): Pair<File, File> {
    val root = Files.createTempDirectory("k3-app").toFile().apply { deleteOnExit() }
    val screens = File(root, "screens").apply { mkdirs() }
    val components = File(root, "components").apply { mkdirs() }
    File(components, "Tokens.kt").writeText("package app.components\n\nobject C {\n  const val White = -1\n  const val Ink = -14737633\n}\n")
    File(screens, "home.kt").writeText(home)
    return screens to components
  }

  private val toggleRow = ComponentSpec(
    "ToggleRow",
    props = listOf(PropSpec("label", PropKind.Text, "Label"), PropSpec("note", PropKind.Text, "Note")),
    events = listOf(ComponentEventSpec("onTap", required = false)),
    paramTypes = mapOf("label" to "String", "note" to "String?", "onTap" to "(() -> Unit)?"),
  )

  // A non-nullable event (`onGo: () -> Unit`) and a nullable one with a payload.
  private val pill = ComponentSpec(
    "Pill",
    props = listOf(PropSpec("label", PropKind.Text, "Label")),
    events = listOf(ComponentEventSpec("onGo"), ComponentEventSpec("onPick", paramType = "Int", required = false)),
    paramTypes = mapOf("label" to "String", "onGo" to "() -> Unit", "onPick" to "((Int) -> Unit)?"),
  )

  private fun service(screens: File, components: File): DocumentService {
    Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile)
    return DocumentService.fromFileOrTree(
      screenKey = "$project/home",
      treeJson = null,
      onProjected = {},
      kotlinFile = File(screens, "home.kt"),
      functionName = "HomeScreen",
      packageName = "app.screens",
      components = { MapComponentRegistry(listOf(toggleRow, pill)) },
      tokens = { Tokens.table(project) },
      tokenFileId = "screens/home.kt",
      tokensVersion = { Tokens.version(project) },
    )
  }

  private fun text(svc: DocumentService) = (svc.doc.root as DocNode.Widget).children.first() as DocNode.Widget
  private fun batch(svc: DocumentService, vararg ops: DocOp) = OpBatch(svc.doc.version, OpEnvelope("t", 0L), ops.toList())

  private fun awaitFile(f: File, predicate: (String) -> Boolean): String {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      val t = f.readText()
      if (predicate(t)) return t
      Thread.sleep(50)
    }
    return f.readText()
  }

  @Test fun aBranchEditWritesBackOnlyThatBranchAndUndoRestoresIt() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    val node = text(svc)
    val label = assertIs<PropValue.Conditional>(node.props["text"])
    assertEquals(PropValue.Conditional("open", whenTrue = lit("Open"), whenFalse = lit("Closed")), label)

    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "text", label.copy(whenFalse = lit("Shut"))))).ok)
    assertEquals(home.replace("else \"Closed\"", "else \"Shut\""), awaitFile(file) { "Shut" in it })

    assertTrue(svc.undo("t").ok)
    assertEquals(home, awaitFile(file) { "Closed" in it })
  }

  @Test fun aTokenInABranchIsTheServersAndMustExist() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val node = text(svc)
    val color = node.props["colorArgb"] as PropValue.Conditional
    val stale = color.copy(whenFalse = PropValue.Token("C.Ink", lit(0), "app.components.C"))
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "colorArgb", stale))).ok)
    assertEquals(PropValue.Token("C.Ink", lit(-14737633), "app.components.C"), (text(svc).props["colorArgb"] as PropValue.Conditional).whenFalse)
    val unknown = color.copy(whenTrue = PropValue.Token("C.Nope", lit(1), "app.components.C"))
    assertNotNull(svc.dryRun(batch(svc, DocOp.SetProp(node.handle, "colorArgb", unknown))).error)
    // An editor that predates tokens and conditionals sees the true branch's literal.
    val old = Tokens.withoutTokens(svc.doc.withoutConditionals())
    assertEquals(lit(-14737633), (old.root as DocNode.Widget).children.first().let { (it as DocNode.Widget).props["colorArgb"] })
    // An editor with conditionals but without tokens: each branch's token becomes its literal.
    val noTokens = ((Tokens.withoutTokens(svc.doc).root as DocNode.Widget).children.first() as DocNode.Widget).props["colorArgb"]
    assertEquals(PropValue.Conditional("selected", whenTrue = lit(-14737633), whenFalse = lit(-14737633)), noTokens)
  }

  @Test fun aComponentsSignatureDecidesWhatABranchMayBe() {
    val (screens, components) = app()
    val svc = service(screens, components)
    fun insert(props: Map<String, PropValue>) =
      DocOp.InsertNode(svc.doc.root.handle, null, DocNode.Widget(Handle(0), "ToggleRow", props))
    val label = "label" to lit("L")
    val refused = listOf(
      // A value on an event, null on a non-nullable parameter, an action on a value parameter.
      insert(mapOf(label, "onTap" to PropValue.Conditional("open", whenTrue = lit("x"), whenFalse = null))),
      insert(mapOf("label" to PropValue.Conditional("open", whenTrue = lit("x"), whenFalse = null))),
      insert(mapOf("label" to PropValue.Conditional("open", whenTrue = lit("x"), whenFalse = PropValue.Action("go")))),
    )
    for (op in refused) assertFalse(svc.dryRun(batch(svc, op)).ok, op.toString())
    val accepted = insert(
      mapOf(
        label,
        "note" to PropValue.Conditional("open", whenTrue = lit("n"), whenFalse = null),
        "onTap" to PropValue.Conditional("open", whenTrue = PropValue.Action("go"), whenFalse = null),
      ),
    )
    assertTrue(svc.dryRun(batch(svc, accepted)).ok, svc.dryRun(batch(svc, accepted)).error)
  }

  @Test fun anElseBranchEditStaysInsideIt() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    val cond = (svc.doc.root as DocNode.Widget).children[1] as DocNode.Widget
    assertEquals("Condition", cond.type)
    val elseText = ((cond.children[1] as DocNode.Widget).children.single() as DocNode.Widget)
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(elseText.handle, "text", lit("gone")))).ok)
    assertEquals(home.replace("StyledText(text = \"hidden\")", "StyledText(text = \"gone\")"), awaitFile(file) { "gone" in it })
  }

  // Review B3: `onGo = if (b.x) b::go else null` does not compile when onGo is `() -> Unit`.
  @Test fun aNullBranchFitsOnlyANullableEvent() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val go = PropValue.Action("go")
    fun insert(props: Map<String, PropValue>) =
      DocOp.InsertNode(svc.doc.root.handle, null, DocNode.Widget(Handle(0), "Pill", mapOf("label" to lit("L")) + props))
    assertFalse(svc.dryRun(batch(svc, insert(mapOf("onGo" to PropValue.Conditional("open", whenTrue = go, whenFalse = null))))).ok)
    assertFalse(svc.dryRun(batch(svc, insert(mapOf("onGo" to PropValue.Conditional("open", whenTrue = null, whenFalse = go))))).ok)
    val both = insert(mapOf("onGo" to PropValue.Conditional("open", whenTrue = go, whenFalse = PropValue.Action("stop"))))
    assertTrue(svc.dryRun(batch(svc, both)).ok, svc.dryRun(batch(svc, both)).error)
    val nullable = insert(mapOf("onGo" to go, "onPick" to PropValue.Conditional("open", whenTrue = PropValue.Action("pick"), whenFalse = null)))
    assertTrue(svc.dryRun(batch(svc, nullable)).ok, svc.dryRun(batch(svc, nullable)).error)
    // The same through SetProp — what clearing one action name in the editor sends.
    assertTrue(svc.submit(batch(svc, insert(mapOf("onGo" to go)))).ok)
    val placed = (svc.doc.root as DocNode.Widget).children.first() as DocNode.Widget
    assertEquals("Pill", placed.type)
    val cleared = DocOp.SetProp(placed.handle, "onGo", PropValue.Conditional("open", whenTrue = go, whenFalse = null))
    assertFalse(svc.dryRun(batch(svc, cleared)).ok)
  }

  // Review nit: `() -> Unit?` is a non-null function returning `Unit?` — null does not fit it.
  @Test fun aFunctionTypeReturningANullableTakesNoNullBranch() {
    val (screens, components) = app()
    Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile)
    val tappy = ComponentSpec(
      "Tappy",
      props = emptyList(),
      events = listOf(ComponentEventSpec("onGo", required = false), ComponentEventSpec("onPick", required = false)),
      paramTypes = mapOf("onGo" to "() -> Unit?", "onPick" to "(() -> Unit)?"),
    )
    val svc = DocumentService.fromFileOrTree(
      "$project/home", null, {}, File(screens, "home.kt"), "HomeScreen", "app.screens",
      components = { MapComponentRegistry(listOf(tappy)) }, tokens = { Tokens.table(project) }, tokenFileId = "screens/home.kt",
    )
    fun insert(key: String) = DocOp.InsertNode(
      svc.doc.root.handle, null,
      DocNode.Widget(Handle(0), "Tappy", mapOf(key to PropValue.Conditional("open", whenTrue = PropValue.Action("go"), whenFalse = null))),
    )
    assertFalse(svc.dryRun(batch(svc, insert("onGo"))).ok)
    assertTrue(svc.dryRun(batch(svc, insert("onPick"))).ok, svc.dryRun(batch(svc, insert("onPick"))).error)
  }
}
