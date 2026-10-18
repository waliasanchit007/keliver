package dev.keliver.portal.ingest

import dev.keliver.portal.WidgetNode
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.serializeTree
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * K3: conditional prop values (`colorArgb = if (b.selected) C.Ink else C.White`)
 * and conditions beyond a plain Boolean (`!b.x`, `b.s.isNotEmpty()`, `else`)
 * are grammar in screens — recognized, written back surgically (a no-op is
 * byte-identical; an edit touches only the changed condition or branch) and
 * exported as compiling Kotlin.
 */
class ConditionalGrammarTest {
  private val tokensKt = """
    package app.components

    object C {
      const val White = -1
      const val Ink = -14737633
    }
  """.trimIndent()
  private val tokens = TokenTable.scan(listOf("Tokens.kt" to tokensKt))

  private val src = """
    package app.screens

    import androidx.compose.runtime.Composable
    import app.components.C
    import dev.keliver.Modifier
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.Button
    import dev.keliver.material.compose.Icon
    import dev.keliver.material.compose.StyledText
    import dev.keliver.ui.Margin
    import dev.keliver.ui.dp

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        // a comment that must survive
        StyledText(text = "Title", colorArgb = if (b.selected) C.Ink else C.White)
        Icon(name = if (b.open) "KeyboardArrowUp" else "KeyboardArrowDown")
        StyledText(text = "x", modifier = Modifier.margin(Margin(top = if (b.raised) 8.dp else 0.dp)))
        Row(margin = Margin(start = if (b.raised) 16.dp else 0.dp, top = 4.dp)) {
          StyledText(text = if (b.subtitle.isEmpty()) "none" else b.subtitle)
        }
        Button(text = "Go", onClick = if (b.enabled) b::tap else null)
        if (!b.hidden) {
          StyledText(text = "shown")
        } else {
          StyledText(text = "hidden")
        }
        if (b.subtitle.isNotEmpty()) {
          StyledText(text = b.subtitle)
        }
        if (b.note.isBlank()) {
          StyledText(text = "no note")
        }
      }
    }

    interface HomeScreenBindings {
      val selected: Boolean
      val open: Boolean
      val raised: Boolean
      val subtitle: String
      val enabled: Boolean
      val hidden: Boolean
      val note: String
      fun tap()
    }
  """.trimIndent()

  private fun recognize(text: String = src) =
    Recognizer.recognize("app/screens/HomeScreen.kt", text, tokens = tokens)!!

  private fun doc(text: String = src): UiDocument = recognize(text).let { UiDocument("home", it.root, it.contract, 0, 0) }

  private fun merge(target: UiDocument, text: String = src): String =
    assertNotNull(WriteBack.merge(text, target, tokens = tokens, fileName = "app/screens/HomeScreen.kt"))

  private fun column(d: UiDocument) = d.root as DocNode.Widget
  private fun child(d: UiDocument, i: Int) = column(d).children[i] as DocNode.Widget

  private fun containsRaw(n: DocNode): Boolean = when (n) {
    is DocNode.RawCode -> true
    is DocNode.Widget -> n.children.any(::containsRaw)
  }

  /** [edited] is [src] with exactly [old] replaced by [new] (once) — nothing else moved. */
  private fun assertOnly(edited: String, old: String, new: String) {
    assertEquals(1, src.split(old).size - 1, "the edit's anchor occurs once: $old")
    assertEquals(src.replace(old, new), edited)
  }

  @Test fun everyFormIsRecognizedWithoutRawCode() {
    val d = doc()
    assertTrue(!containsRaw(d.root), d.root.toString())
    val color = child(d, 0).props["colorArgb"]
    assertIs<PropValue.Conditional>(color)
    assertEquals("selected", color.field)
    assertEquals(PropValue.Token("C.Ink", lit(-14737633), "app.components.C"), color.whenTrue)
    assertEquals(PropValue.Token("C.White", lit(-1), "app.components.C"), color.whenFalse)
    assertEquals(PropValue.Conditional("open", whenTrue = lit("KeyboardArrowUp"), whenFalse = lit("KeyboardArrowDown")), child(d, 1).props["name"])
    assertEquals(
      PropValue.Conditional("raised", whenTrue = lit(8.0), whenFalse = lit(0.0)),
      child(d, 2).modifiers["Margin.margin.top"],
    )
    assertEquals(lit(0.0), child(d, 2).modifiers["Margin.margin.start"])
    val row = child(d, 3)
    assertEquals(PropValue.Conditional("raised", whenTrue = lit(16.0), whenFalse = lit(0.0)), row.props["margin.start"])
    assertEquals(lit(4.0), row.props["margin.top"])
    assertEquals(
      PropValue.Conditional("subtitle", test = "empty", whenTrue = lit("none"), whenFalse = PropValue.Bind("subtitle")),
      (row.children.single() as DocNode.Widget).props["text"],
    )
    assertEquals(PropValue.Conditional("enabled", whenTrue = PropValue.Action("tap"), whenFalse = null), child(d, 4).props["onClick"])

    val hidden = child(d, 5)
    assertEquals("Condition", hidden.type)
    assertEquals(mapOf("field" to lit("hidden"), "negate" to lit(true)), hidden.props)
    assertEquals(listOf("StyledText", "Else"), hidden.children.map { (it as DocNode.Widget).type })
    assertEquals(mapOf("field" to lit("subtitle"), "test" to lit("empty"), "negate" to lit(true)), child(d, 6).props)
    assertEquals(mapOf("field" to lit("note"), "test" to lit("blank")), child(d, 7).props)
  }

  @Test fun aNoOpWriteBackIsByteIdentical() {
    assertEquals(src, merge(doc()))
  }

  @Test fun editingOneBranchRewritesOnlyThatBranch() {
    val d = doc()
    val color = child(d, 0).props["colorArgb"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 0).handle, "colorArgb", color.copy(whenFalse = lit(-16777216)))).doc),
      "else C.White)", "else -16777216)",
    )
    val icon = child(d, 1).props["name"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 1).handle, "name", icon.copy(whenTrue = lit("Up")))).doc),
      "if (b.open) \"KeyboardArrowUp\"", "if (b.open) \"Up\"",
    )
    val onClick = child(d, 4).props["onClick"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 4).handle, "onClick", onClick.copy(whenTrue = PropValue.Action("go")))).doc),
      "if (b.enabled) b::tap", "if (b.enabled) b::go",
    )
    val row = child(d, 3)
    val text = (row.children.single() as DocNode.Widget)
    assertOnly(
      merge(d.apply(DocOp.SetProp(text.handle, "text", (text.props["text"] as PropValue.Conditional).copy(whenTrue = lit("—")))).doc),
      "\"none\" else", "\"—\" else",
    )
  }

  @Test fun aDpBranchKeepsItsSpellingInsideAMarginOrAModifier() {
    val d = doc()
    val top = child(d, 2).modifiers["Margin.margin.top"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetModifier(child(d, 2).handle, "Margin.margin.top", top.copy(whenTrue = lit(12.0)))).doc),
      "if (b.raised) 8.dp", "if (b.raised) 12.dp",
    )
    val start = child(d, 3).props["margin.start"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 3).handle, "margin.start", start.copy(whenFalse = lit(2.0)))).doc),
      "16.dp else 0.dp, top", "16.dp else 2.dp, top",
    )
  }

  @Test fun editingTheConditionRewritesOnlyTheCondition() {
    val d = doc()
    val color = child(d, 0).props["colorArgb"] as PropValue.Conditional
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 0).handle, "colorArgb", color.copy(negate = true))).doc),
      "if (b.selected) C.Ink", "if (!b.selected) C.Ink",
    )
    // A Condition node's condition: `!b.hidden` -> `b.hidden`, its blocks untouched.
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 5).handle, "negate", lit(false))).doc),
      "if (!b.hidden) {", "if (b.hidden) {",
    )
    assertOnly(
      merge(d.apply(DocOp.SetProp(child(d, 7).handle, "test", lit("empty"))).doc),
      "if (b.note.isBlank()) {", "if (b.note.isEmpty()) {",
    )
  }

  @Test fun editsInsideAnElseBranchStayInsideIt() {
    val d = doc()
    val elseNode = child(d, 5).children[1] as DocNode.Widget
    val hidden = elseNode.children.single() as DocNode.Widget
    assertOnly(
      merge(d.apply(DocOp.SetProp(hidden.handle, "text", lit("gone"))).doc),
      "StyledText(text = \"hidden\")", "StyledText(text = \"gone\")",
    )
    val inserted = d.apply(
      DocOp.InsertNode(elseNode.handle, hidden.handle, DocNode.Widget(Handle(0), "StyledText", mapOf("text" to lit("more")))),
    ).doc
    assertOnly(
      merge(inserted),
      "      StyledText(text = \"hidden\")\n",
      "      StyledText(text = \"hidden\")\n      StyledText(\n        text = \"more\",\n      )\n",
    )
  }

  @Test fun removingAnElseDeletesOnlyTheElseText() {
    val d = doc()
    val elseNode = child(d, 5).children[1] as DocNode.Widget
    assertOnly(merge(d.apply(DocOp.DeleteNode(elseNode.handle)).doc), "    } else {\n      StyledText(text = \"hidden\")\n    }\n", "    }\n")
  }

  @Test fun theFullExportCompilesAndReIngestsToTheSameTree() {
    val d = doc()
    val exported = exportKotlin(d.toWidgetTree(), functionName = "HomeScreen")
    val file = PsiEnv.parse("Exported.kt", exported)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isEmpty(), exported)
    assertTrue("colorArgb = if (b.selected) C.Ink else C.White," in exported, exported)
    assertTrue("name = if (b.open) \"KeyboardArrowUp\" else \"KeyboardArrowDown\"," in exported, exported)
    assertTrue("Modifier.margin(Margin(top = if (b.raised) 8.dp else 0.dp))" in exported, exported)
    assertTrue("margin = Margin(start = if (b.raised) 16.dp else 0.dp, top = 4.dp)," in exported, exported)
    assertTrue("text = if (b.subtitle.isEmpty()) \"none\" else b.subtitle," in exported, exported)
    assertTrue("onClick = if (b.enabled) b::tap else null," in exported, exported)
    assertTrue("if (!b.hidden) {" in exported && "} else {" in exported, exported)
    assertTrue("if (b.subtitle.isNotEmpty()) {" in exported && "if (b.note.isBlank()) {" in exported, exported)
    // The derived contract types each condition's field.
    for (member in listOf("val selected: Boolean", "val subtitle: String", "val note: String", "val hidden: Boolean", "fun tap()")) {
      assertTrue("  $member\n" in exported, "$member in\n$exported")
    }
    val again = Recognizer.recognize("app/screens/HomeScreen.kt", "package app.screens\n$exported", tokens = tokens)!!
    fun canonical(t: WidgetNode): WidgetNode = t.copy(id = 0, children = t.children.map(::canonical))
    assertEquals(
      serializeTree(canonical(d.toWidgetTree())),
      serializeTree(canonical(UiDocument("home", again.root, again.contract, 0, 0).toWidgetTree())),
    )
  }

  @Test fun formsOutsideTheGrammarStayRawCode() {
    fun rawOf(body: String): DocNode {
      val text = src.replace(Regex("(?s)  Column \\{.*\\n  }\\n}"), "  Column {\n    $body\n  }\n}")
      return (doc(text).root as DocNode.Widget).children.single()
    }
    // A screen contract cannot type a nullable field; else-if chains and nested ifs are not grammar.
    assertIs<DocNode.RawCode>(rawOf("if (b.subtitle != null) {\n      StyledText(text = \"x\")\n    }"))
    assertIs<DocNode.RawCode>(rawOf("if (b.hidden) {\n      StyledText(text = \"a\")\n    } else if (b.open) {\n      StyledText(text = \"b\")\n    }"))
    assertIs<DocNode.RawCode>(rawOf("StyledText(text = if (b.open) if (b.hidden) \"a\" else \"b\" else \"c\")"))
    assertIs<DocNode.RawCode>(rawOf("StyledText(text = if (b.subtitle.length > 3) \"a\" else \"b\")"))
    // A branch may be any value the prop takes by itself — here a binding.
    assertIs<DocNode.Widget>(rawOf("StyledText(text = if (b.open) \"a\" else b.subtitle)"))
  }
}
