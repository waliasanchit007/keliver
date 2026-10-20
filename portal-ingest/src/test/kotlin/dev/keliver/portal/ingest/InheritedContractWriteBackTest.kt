package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * U37: a screen whose Bindings interface extends a type declared in another
 * file (`interface HomeScreenBindings : BaseBindings`). A field or action the
 * screen reads that the interface does not declare in the file may be
 * inherited — contract write-back declared it anyway (`/** TODO(portal) */
 * val title: String get() = ""`), which redeclares an inherited member
 * without `override`: the file no longer compiled, on any edit of the screen.
 *
 * Contract write-back never declares a member such an interface may inherit
 * (nor one a supertype in the same file declares), and the writer refuses a
 * change that would make the screen read a member the file neither declares
 * nor already reads, when the interface is not read whole — the editor cannot
 * tell whether it exists, so it writes nothing that may not compile.
 */
class InheritedContractWriteBackTest {
  private val fileName = "app/screens/HomeScreen.kt"

  private val base = """
    package app.screens

    interface BaseBindings {
      val title: String
      val rows: List<Row>
      fun open()
    }

    interface Row {
      val label: String
    }
  """.trimIndent() + "\n"

  private fun screen(body: String, tail: String): String {
    val head = """
      package app.screens

      import androidx.compose.runtime.Composable
      import dev.keliver.layout.compose.Column
      import dev.keliver.material.compose.ListItem
      import dev.keliver.material.compose.StyledText

      @Composable
      fun HomeScreen(b: HomeScreenBindings) {
        Column {
    """.trimIndent()
    val rest = tail.trimIndent().let { if (it.isEmpty()) "" else "\n$it\n" }
    return head + "\n" + body.prependIndent("    ") + "\n  }\n}\n" + rest
  }

  private val inheriting = """
    interface HomeScreenBindings : BaseBindings {
      val name: String
    }
  """

  private fun doc(text: String): UiDocument =
    Recognizer.recognize(fileName, text)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }

  private fun ensured(text: String) = ContractWriteBack.ensure(text, doc(text).toWidgetTree(), "HomeScreen")

  private fun compiles(screenText: String, vararg others: Pair<String, String>) {
    val errors = KotlinCompileCheck.errors(mapOf("HomeScreen.kt" to screenText) + others)
    assertNull(errors, "the written file does not compile:\n$screenText")
  }

  private fun plan(onDisk: String, target: UiDocument, base: UiDocument) = ScreenWriter.plan(
    onDisk = onDisk, doc = target, base = base, baseText = onDisk,
    fileName = fileName, functionName = "HomeScreen", packageName = "app.screens",
  )

  private fun UiDocument.child(i: Int) = (root as DocNode.Widget).children[i]

  private fun insertText(d: UiDocument, value: PropValue) = d.apply(
    DocOp.InsertNode(d.root.handle, d.child(0).handle, DocNode.Widget(Handle(0), "StyledText", mapOf("text" to value))),
  ).doc

  @Test fun theCompileCheckRejectsWhatWriteBackUsedToWrite() {
    // The harness itself: the member write-back used to add does not compile.
    val broken = screen("StyledText(text = b.title)", inheriting)
      .replace("  val name: String\n}", "  val name: String\n  /** ${ContractWriteBack.MARKER}. */\n  val title: String get() = \"\"\n}")
    assertTrue("val title: String get()" in broken, broken)
    val errors = KotlinCompileCheck.errors(mapOf("HomeScreen.kt" to broken, "BaseBindings.kt" to base))
    assertTrue(errors != null && "title" in errors && "override" in errors, errors ?: "it compiled")
  }

  // ── ContractWriteBack ────────────────────────────────────────────────

  @Test fun anInheritedFieldIsNotRedeclared() {
    val file = screen("StyledText(text = b.title)\nStyledText(text = b.name)", inheriting)
    compiles(file, "BaseBindings.kt" to base)
    assertEquals(file, ensured(file))
  }

  @Test fun anInheritedActionIsNotRedeclared() {
    val file = screen("ListItem(headline = b.name, onClick = { b.open() })", inheriting)
    compiles(file, "BaseBindings.kt" to base)
    assertEquals(file, ensured(file))
  }

  @Test fun anInheritedListAndItsItemTypeAreNotDeclared() {
    val file = screen("b.rows.forEach { row ->\n  StyledText(text = row.label)\n}", inheriting)
    compiles(file, "BaseBindings.kt" to base)
    assertEquals(file, ensured(file))
  }

  @Test fun aSupertypeInTheSameFileIsReadWholeAndANewFieldIsStillDeclared() {
    val sameFile = """
      interface ScreenBase {
        val title: String
        fun open()
      }

      interface HomeScreenBindings : ScreenBase {
        val name: String
      }
    """
    val file = screen("StyledText(text = b.title)\nListItem(headline = b.name, onClick = { b.open() })\nStyledText(text = b.fresh)", sameFile)
    val out = ensured(file)
    val iface = out.substringAfter("interface HomeScreenBindings")
    assertTrue("val fresh: String get() = \"\"" in iface, out)
    assertTrue("title" !in iface && "fun open" !in iface, out)
    assertEquals(1, Regex("val title").findAll(out).count(), out)
    compiles(out)
  }

  // ── ScreenWriter: the reviewer's reproduction, and what is refused ──

  @Test fun anyEditOfAScreenReadingAnInheritedFieldStillCompiles() {
    val file = screen("StyledText(text = b.title)\nStyledText(text = \"y\")", inheriting)
    val d = doc(file)
    val edited = d.apply(DocOp.SetProp(d.child(1).handle, "text", lit("z"))).doc
    val w = assertIs<ScreenWriter.Write>(plan(file, edited, d))
    assertTrue("text = \"z\"" in w.text, w.text)
    assertTrue("val title" !in w.text && "TODO(portal)" !in w.text, w.text)
    compiles(w.text, "BaseBindings.kt" to base)
  }

  @Test fun anInheritedFieldTheFileAlreadyReadsCanBeBoundAgain() {
    val file = screen("StyledText(text = b.title)", inheriting)
    val d = doc(file)
    val w = assertIs<ScreenWriter.Write>(plan(file, insertText(d, PropValue.Bind("title")), d))
    assertEquals(2, Regex("""b\.title""").findAll(w.text).count(), w.text)
    assertTrue("val title" !in w.text, w.text)
    compiles(w.text, "BaseBindings.kt" to base)
  }

  @Test fun aFieldTheFileNeitherDeclaresNorReadsIsRefused() {
    val file = screen("StyledText(text = b.name)", inheriting)
    val d = doc(file)
    val r = assertIs<ScreenWriter.Refuse>(plan(file, insertText(d, PropValue.Bind("fresh")), d))
    assertTrue("`fresh`" in r.reason && "`HomeScreenBindings` extends `BaseBindings`, declared in another file" in r.reason, r.reason)
    // A field the interface declares here is still written.
    val w = assertIs<ScreenWriter.Write>(plan(file, insertText(d, PropValue.Bind("name")), d))
    compiles(w.text, "BaseBindings.kt" to base)
  }

  @Test fun anActionTheFileNeitherDeclaresNorCallsIsRefused() {
    val file = screen("StyledText(text = b.name)", inheriting)
    val d = doc(file)
    val item = DocNode.Widget(Handle(0), "ListItem", mapOf("headline" to lit("h"), "onClick" to PropValue.Action("fresh")))
    val edited = d.apply(DocOp.InsertNode(d.root.handle, d.child(0).handle, item)).doc
    val r = assertIs<ScreenWriter.Refuse>(plan(file, edited, d))
    assertTrue("`fresh`" in r.reason, r.reason)
  }

  @Test fun aFieldTheTextLastReadReadsIsVouchedFor() {
    // The relay read the file reading `b.title`, then wrote the delete of that read:
    // the undo bringing it back is written (the relay passes the text it last read).
    val read = screen("StyledText(text = b.title)\nStyledText(text = \"y\")", inheriting)
    val now = screen("StyledText(text = \"y\")", inheriting)
    val d = doc(now)
    val target = insertText(d, PropValue.Bind("title"))
    assertIs<ScreenWriter.Refuse>(plan(now, target, d))
    val w = assertIs<ScreenWriter.Write>(
      ScreenWriter.plan(
        onDisk = now, doc = target, base = d, baseText = now,
        fileName = fileName, functionName = "HomeScreen", packageName = "app.screens", readText = read,
      ),
    )
    assertTrue("val title" !in w.text, w.text)
    compiles(w.text, "BaseBindings.kt" to base)
  }

  @Test fun aNewFieldOfAnInterfaceNotInTheFileIsRefused() {
    // The interface the screen takes is declared elsewhere: nothing can be declared here.
    val file = screen("StyledText(text = b.title)", "")
    val d = doc(file)
    val r = assertIs<ScreenWriter.Refuse>(plan(file, insertText(d, PropValue.Bind("fresh")), d))
    assertTrue("`fresh`" in r.reason && "not declared in this file" in r.reason, r.reason)
    // One the file already reads is written.
    assertIs<ScreenWriter.Write>(plan(file, insertText(d, PropValue.Bind("title")), d))
  }

  // ── #135 review: what counts as a read, and a nested supertype in the same file ──

  @Test fun aNameThatShadowsTheBindingsParameterVouchesForNothing() {
    // `b` here is a lambda parameter, a local val or a local function's parameter — not the screen's bindings.
    val shadows = listOf(
      "listOf(\"x\").forEach { b -> StyledText(text = b.length.toString()) }",
      "listOf(\"x\" to 1).forEach { (b, _) -> StyledText(text = b.length.toString()) }",
      "run {\n  val b = \"x\"\n  StyledText(text = b.length.toString())\n}",
      "run {\n  fun f(b: String) = b.length\n  StyledText(text = f(\"x\").toString())\n}",
      "for (b in listOf(\"x\")) StyledText(text = b.length.toString())",
    )
    for (code in shadows) {
      val file = screen("StyledText(text = b.name)\n$code", inheriting)
      compiles(file, "BaseBindings.kt" to base)
      val d = doc(file)
      val r = assertIs<ScreenWriter.Refuse>(plan(file, insertText(d, PropValue.Bind("length")), d), code)
      assertTrue("`length`" in r.reason, r.reason)
    }
    // The screen's own `b` read before a shadowing local, or beside one, still vouches.
    val file = screen("StyledText(text = b.title)\nlistOf(\"x\").forEach { b -> StyledText(text = b.length.toString()) }", inheriting)
    val d = doc(file)
    compiles(assertIs<ScreenWriter.Write>(plan(file, insertText(d, PropValue.Bind("title")), d)).text, "BaseBindings.kt" to base)
  }

  @Test fun anActionTheFileReadsOnlyAsACallableReferenceIsVouchedFor() {
    val file = screen("ListItem(headline = b.name, onClick = b::open)", inheriting)
    compiles(file, "BaseBindings.kt" to base)
    val d = doc(file)
    val item = DocNode.Widget(Handle(0), "ListItem", mapOf("headline" to lit("h"), "onClick" to PropValue.Action("open")))
    val w = assertIs<ScreenWriter.Write>(plan(file, d.apply(DocOp.InsertNode(d.root.handle, d.child(0).handle, item)).doc, d))
    assertTrue("fun open" !in w.text && "TODO(portal)" !in w.text, w.text)
    compiles(w.text, "BaseBindings.kt" to base)
  }

  @Test fun aSupertypeNestedInADeclarationOfTheSameFileIsReadWhole() {
    val nested = """
      interface Outer {
        interface Base {
          val title: String
          fun open()
        }
      }

      interface HomeScreenBindings : Outer.Base {
        val name: String
      }
    """
    val file = screen("StyledText(text = b.title)\nListItem(headline = b.name, onClick = { b.open() })\nStyledText(text = b.fresh)", nested)
    assertNull(doc(file).contract.unlistedFieldsUnknown)
    assertEquals("String", doc(file).contract.fields["title"])
    val out = ensured(file)
    val iface = out.substringAfter("interface HomeScreenBindings")
    assertTrue("val fresh: String get() = \"\"" in iface, out)
    assertTrue("title" !in iface && "fun open" !in iface, out)
    compiles(out)
  }
}
