import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.document.DP_CTOR_FORM
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
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
 * K7: Int arithmetic and Ints used as Dps through the relay — ingested from the
 * file, one operand edited and written back alone, undone (a token operand
 * re-read on the way back), token operands gated like any token, a component
 * parameter checked against its signature, and an editor that predates K7
 * served the computed literal (or nothing).
 */
class ArithmeticRelayTest {
  private val project = "k7-" + System.nanoTime()

  private val home = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.C
    import app.components.Pill
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Spacer
    import dev.keliver.material.compose.StyledText
    import dev.keliver.ui.Dp

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(text = "a", fontSize = b.size * 2 / 5 + C.Gap)
        Spacer(height = Dp(b.gap.toDouble()))
        Pill(label = "p", size = b.size - 4)
        StyledText(text = "c", fontSize = C.Gap * 3)
      }
    }

    interface HomeScreenBindings {
      val size: Int
      val gap: Int
      val label: String
    }
  """.trimIndent() + "\n"

  private val pill = ComponentSpec(
    name = "Pill",
    props = listOf(PropSpec("label", PropKind.Text, "Label"), PropSpec("size", PropKind.Int, "Size")),
    events = emptyList(),
    paramTypes = mapOf("label" to "String", "size" to "Int"),
    transparent = false,
    diagnostic = "opaque",
    packageName = "app.components",
  )

  private fun app(): Pair<File, File> {
    val root = Files.createTempDirectory("k7-app").toFile().apply { deleteOnExit() }
    val screens = File(root, "screens").apply { mkdirs() }
    val components = File(root, "components").apply { mkdirs() }
    File(components, "Tokens.kt").writeText("package app.components\n\nobject C {\n  const val Gap = 8\n  const val Wide = 24\n  const val Name = \"n\"\n}\n")
    File(screens, "home.kt").writeText(home)
    return screens to components
  }

  private fun service(screens: File, components: File): DocumentService {
    Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile)
    return DocumentService.fromFileOrTree(
      screenKey = "$project/home", treeJson = null, onProjected = {}, kotlinFile = File(screens, "home.kt"),
      functionName = "HomeScreen", packageName = "app.screens",
      components = { MapComponentRegistry(listOf(pill)) },
      tokens = { Tokens.table(project) },
      tokenFileId = "screens/home.kt",
      tokensVersion = { Tokens.version(project) },
    )
  }

  private fun child(svc: DocumentService, i: Int) = (svc.doc.root as DocNode.Widget).children[i] as DocNode.Widget
  private fun batch(svc: DocumentService, vararg ops: DocOp) = OpBatch(svc.doc.version, OpEnvelope("t", 0L), ops.toList())
  private fun a(op: String, l: PropValue, r: PropValue) = PropValue.Arith(op, l, r)
  private fun b(f: String) = PropValue.Bind(f)
  private fun token(ref: String, v: Any) = PropValue.Token(ref, lit(v), "app.components.C")

  private fun awaitFile(f: File, predicate: (String) -> Boolean): String {
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
      val t = f.readText()
      if (predicate(t)) return t
      Thread.sleep(50)
    }
    return f.readText()
  }

  @Test fun oneOperandIsWrittenAloneAndATokenOperandComesBackOnUndo() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    val node = child(svc, 0)
    val expr = a("+", a("/", a("*", b("size"), lit(2)), lit(5)), token("C.Gap", 8))
    assertEquals(expr, node.props["fontSize"])
    assertEquals(PropValue.IntDp(b("gap"), DP_CTOR_FORM), child(svc, 1).props["height"])
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "fontSize", a("+", a("/", a("*", b("size"), lit(2)), lit(5)), lit(6))))).ok)
    assertEquals(home.replace("/ 5 + C.Gap)", "/ 5 + 6)"), awaitFile(file) { "/ 5 + 6)" in it })
    assertTrue(svc.undo("t").ok)
    assertEquals(home, awaitFile(file) { "/ 5 + C.Gap)" in it })
    assertEquals(expr, child(svc, 0).props["fontSize"])
    assertTrue(reingestWhenIdle(svc))
  }

  @Test fun aTokenOperandIsTheServersAndMustBeAnInt() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val h = child(svc, 0).handle
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(h, "fontSize", a("*", b("size"), token("C.Wide", 99))))).ok)
    assertEquals(a("*", b("size"), token("C.Wide", 24)), child(svc, 0).props["fontSize"])
    assertNotNull(svc.dryRun(batch(svc, DocOp.SetProp(h, "fontSize", a("*", b("size"), token("C.Nope", 1))))).error)
    assertNotNull(svc.dryRun(batch(svc, DocOp.SetProp(h, "fontSize", a("*", b("size"), token("C.Name", 1))))).error)
  }

  @Test fun aComponentParameterTakesArithmeticOnlyWhereItIsAnInt() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val inst = child(svc, 2)
    assertEquals(a("-", b("size"), lit(4)), inst.props["size"])
    assertTrue(svc.dryRun(batch(svc, DocOp.SetProp(inst.handle, "size", a("*", b("size"), lit(2))))).ok)
    val text = svc.dryRun(batch(svc, DocOp.SetProp(inst.handle, "label", a("*", b("size"), lit(2)))))
    assertFalse(text.ok)
    assertTrue("Int parameter" in text.error.orEmpty(), text.error)
    val dp = svc.dryRun(batch(svc, DocOp.SetProp(inst.handle, "size", PropValue.IntDp(b("size")))))
    assertTrue("never a Dp" in dp.error.orEmpty(), dp.error)
    // A declared String operand is refused by the document.
    assertFalse(svc.dryRun(batch(svc, DocOp.SetProp(child(svc, 0).handle, "fontSize", a("+", b("label"), lit(1))))).ok)
  }

  @Test fun anEditorThatPredatesArithmeticGetsTheLiteralOrNothing() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val all = mapOf("tokens" to "1", "conditionals" to "1", "extendedConditions" to "1", "templates" to "1", "lists" to "1")
    val old = docView(svc.doc, all)
    fun prop(i: Int, k: String) = ((old.root as DocNode.Widget).children[i] as DocNode.Widget).props[k]
    assertNull(prop(0, "fontSize")) // reads a binding: left out
    assertNull(prop(1, "height"))
    assertEquals(lit(24), prop(3, "fontSize")) // C.Gap * 3, computed
    assertEquals(svc.doc, docView(svc.doc, all + ("arithmetic" to "1")))
    // An editor that predates tokens too: still the literal.
    assertEquals(lit(24), ((docView(svc.doc, emptyMap()).root as DocNode.Widget).children[3] as DocNode.Widget).props["fontSize"])
  }
}
