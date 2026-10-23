import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.document.CondTerm
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K6: equality and compound conditions through the relay — ingested from the
 * file, a branch or one term edited and written back surgically, undone (a
 * compared token re-read on the way back), the operand token gated like any
 * token, an edit write-back would refuse refused when it is made, and a K3
 * editor served the true branch.
 */
class CompoundConditionRelayTest {
  private val project = "k6-" + System.nanoTime()

  private val home = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.C
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        StyledText(text = if (b.kind == "card" && b.open) "Open" else "Closed", colorArgb = if (b.tier == C.Top) C.Ink else C.White)
        if (b.kind != "cash" &&
          b.open) {
          StyledText(text = "shown")
        }
        b.rows.forEach { row ->
          StyledText(text = if (row.kind == "a" || b.open) "A" else "B")
        }
      }
    }

    interface HomeScreenBindings {
      val kind: String
      val open: Boolean
      val tier: Int
      val count: Int
      val rows: List<Row>
    }

    interface Row {
      val kind: String
      val n: Int
    }
  """.trimIndent() + "\n"

  private fun app(): Pair<File, File> {
    val root = Files.createTempDirectory("k6-app").toFile().apply { deleteOnExit() }
    val screens = File(root, "screens").apply { mkdirs() }
    val components = File(root, "components").apply { mkdirs() }
    File(components, "Tokens.kt").writeText("package app.components\n\nobject C {\n  const val White = -1\n  const val Ink = -14737633\n  const val Top = 3\n  const val Low = 1\n}\n")
    File(screens, "home.kt").writeText(home)
    return screens to components
  }

  private fun service(screens: File, components: File): DocumentService {
    Tokens.rebuild(project, listOf(screens, components), root = screens.parentFile)
    return DocumentService.fromFileOrTree(
      screenKey = "$project/home",
      treeJson = null,
      onProjected = {},
      kotlinFile = File(screens, "home.kt"),
      functionName = "HomeScreen",
      packageName = "app.screens",
      components = { EmptyComponentRegistry },
      tokens = { Tokens.table(project) },
      tokenFileId = "screens/home.kt",
      tokensVersion = { Tokens.version(project) },
    )
  }

  private fun child(svc: DocumentService, i: Int) = (svc.doc.root as DocNode.Widget).children[i] as DocNode.Widget
  private fun batch(svc: DocumentService, vararg ops: DocOp) = OpBatch(svc.doc.version, OpEnvelope("t", 0L), ops.toList())

  private fun awaitFile(f: File, predicate: (String) -> Boolean): String {
    // Generous: a write-back on a loaded machine (a parallel build) can take several seconds.
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
      val t = f.readText()
      if (predicate(t)) return t
      Thread.sleep(50)
    }
    return f.readText()
  }

  private val top = PropValue.Token("C.Top", lit(3), "app.components.C")

  @Test fun aBranchOfACompoundConditionalIsWrittenAloneAndUndone() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    val node = child(svc, 0)
    val label = assertIs<PropValue.Conditional>(node.props["text"])
    assertEquals(PropValue.Conditional("kind", "eq", whenTrue = lit("Open"), whenFalse = lit("Closed"), operand = lit("card"), more = listOf(CondTerm("open"))), label)
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(node.handle, "text", label.copy(whenFalse = lit("Shut"))))).ok)
    assertEquals(home.replace("else \"Closed\"", "else \"Shut\""), awaitFile(file) { "Shut" in it })
    assertTrue(svc.undo("t").ok)
    assertEquals(home, awaitFile(file) { "Closed" in it })
    assertTrue(reingestWhenIdle(svc))
  }

  @Test fun oneTermIsWrittenAloneAndACompareTokenComesBackOnUndo() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    // A Condition's operand, in a condition split over two lines: that term alone.
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(child(svc, 1).handle, "operand", lit("card")))).ok)
    assertEquals(home.replace("b.kind != \"cash\" &&", "b.kind != \"card\" &&"), awaitFile(file) { "!= \"card\"" in it })
    assertTrue(svc.undo("t").ok)
    assertEquals(home, awaitFile(file) { "\"cash\"" in it })
    assertTrue(reingestWhenIdle(svc))
    // A conditional value's token operand replaced by a literal, then undone: the token is re-read.
    val color = child(svc, 0).props["colorArgb"] as PropValue.Conditional
    assertEquals(top, color.operand)
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(child(svc, 0).handle, "colorArgb", color.copy(operand = lit(5))))).ok)
    assertEquals(home.replace("b.tier == C.Top", "b.tier == 5"), awaitFile(file) { "b.tier == 5" in it })
    assertTrue(svc.undo("t").ok, "undo restores the token operand")
    assertEquals(home, awaitFile(file) { "C.Top" in it })
    assertEquals(top, (child(svc, 0).props["colorArgb"] as PropValue.Conditional).operand)
  }

  @Test fun anOperandTokenIsTheServersAndMustExist() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val color = child(svc, 0).props["colorArgb"] as PropValue.Conditional
    val stale = color.copy(operand = PropValue.Token("C.Low", lit(99), "app.components.C"))
    assertTrue(svc.submit(batch(svc, DocOp.SetProp(child(svc, 0).handle, "colorArgb", stale))).ok)
    assertEquals(PropValue.Token("C.Low", lit(1), "app.components.C"), (child(svc, 0).props["colorArgb"] as PropValue.Conditional).operand)
    val unknown = color.copy(operand = PropValue.Token("C.Nope", lit(1), "app.components.C"))
    assertNotNull(svc.dryRun(batch(svc, DocOp.SetProp(child(svc, 0).handle, "colorArgb", unknown))).error)
  }

  @Test fun anEditWriteBackWouldRefuseIsRefusedWhenMade() {
    val (screens, components) = app()
    val svc = service(screens, components)
    // The item type declares `kind` a String; the document does not know item types — the preflight does.
    val repeat = child(svc, 2)
    val text = repeat.children.single() as DocNode.Widget
    val v = text.props["text"] as PropValue.Conditional
    val ack = svc.dryRun(batch(svc, DocOp.SetProp(text.handle, "text", v.copy(operand = lit(1)))))
    assertFalse(ack.ok)
    // K6 review: the document refuses an operand kind change on an item field first (it cannot read item types);
    // the write-back preflight would refuse it too.
    assertTrue("not readable" in ack.error.orEmpty() || "could not be written" in ack.error.orEmpty(), ack.error)
    // The same term compared with a String is written.
    assertTrue(svc.dryRun(batch(svc, DocOp.SetProp(text.handle, "text", v.copy(operand = lit("b"))))).ok)
    // A Boolean test on an item field the file's item type declares Int: only the preflight sees the item type.
    val itemBool = PropValue.Conditional("open", whenTrue = lit("A"), whenFalse = lit("B"), more = listOf(CondTerm("row.n")), join = "or")
    val pre = svc.dryRun(batch(svc, DocOp.SetProp(text.handle, "text", itemBool)))
    assertFalse(pre.ok)
    assertTrue("could not be written" in pre.error.orEmpty(), pre.error)
  }

  // K6 review: re-pointing a Boolean term at an Int field would write `if (b.kind != "cash" && b.count)`.
  @Test fun aTermOfTheWrongDeclaredTypeIsRefusedAndNothingIsWritten() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val file = File(screens, "home.kt")
    val ack = svc.submit(batch(svc, DocOp.SetProp(child(svc, 1).handle, "field.1", lit("count"))))
    assertFalse(ack.ok, "a Boolean term on `count`, declared Int")
    assertTrue("count" in ack.error.orEmpty(), ack.error)
    assertEquals(home, file.readText())
    // The operand of a field the file types String keeps its kind.
    assertFalse(svc.dryRun(batch(svc, DocOp.SetProp(child(svc, 1).handle, "operand", lit(1)))).ok)
  }

  @Test fun aK3EditorIsServedTheTrueBranchAndAnExtendedOneTheCondition() {
    val (screens, components) = app()
    val svc = service(screens, components)
    val k3 = docView(svc.doc, mapOf("tokens" to "1", "conditionals" to "1", "templates" to "1", "lists" to "1"))
    val k3Text = ((k3.root as DocNode.Widget).children[0] as DocNode.Widget)
    assertEquals(lit("Open"), k3Text.props["text"])
    assertEquals(PropValue.Token("C.Ink", lit(-14737633), "app.components.C"), k3Text.props["colorArgb"])
    // A Condition node reaches it unchanged.
    assertEquals(child(svc, 1), (k3.root as DocNode.Widget).children[1])
    val k6 = docView(svc.doc, mapOf("tokens" to "1", "conditionals" to "1", "templates" to "1", "lists" to "1", "extendedConditions" to "1"))
    assertEquals(svc.doc, k6)
  }
}
