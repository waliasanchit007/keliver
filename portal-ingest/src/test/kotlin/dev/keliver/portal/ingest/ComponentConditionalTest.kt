package dev.keliver.portal.ingest

import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.Cond
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.Expansion
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.Token
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.expandForPreview
import dev.keliver.portal.serializeTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K3: a component whose props or children are chosen by its parameters stays
 * TRANSPARENT, its preview decides each condition against the instance's
 * arguments (or the screen binding an argument is), and a component that is
 * opaque says exactly why.
 */
class ComponentConditionalTest {
  private val tokens = TokenTable.scan(
    listOf("Tokens.kt" to "package app.components\n\nobject C {\n  const val White = -1\n  const val Ink = -14737633\n}\n"),
  )

  private val toggleRowKt = """
    package app.components

    import androidx.compose.runtime.Composable
    import dev.keliver.Modifier
    import dev.keliver.layout.compose.Column
    import dev.keliver.material.compose.Clickable
    import dev.keliver.material.compose.Icon
    import dev.keliver.material.compose.StyledText
    import dev.keliver.ui.Margin
    import dev.keliver.ui.dp

    @Composable
    fun ToggleRow(
      label: String,
      selected: Boolean,
      subtitle: String = "",
      note: String? = null,
      raised: Boolean = false,
      onTap: () -> Unit,
      onLongTap: (() -> Unit)? = null,
    ) {
      Column {
        StyledText(text = label, colorArgb = if (selected) C.Ink else C.White)
        Icon(name = if (selected) "KeyboardArrowUp" else "KeyboardArrowDown")
        StyledText(text = "x", modifier = Modifier.margin(Margin(top = if (raised) 8.dp else 0.dp)))
        Clickable(onClick = if (selected) onTap else null) {
          StyledText(text = "tap")
        }
        if (!selected) {
          StyledText(text = "off")
        } else {
          StyledText(text = "on")
        }
        if (subtitle.isNotEmpty()) {
          StyledText(text = subtitle)
        }
        if (note != null) {
          StyledText(text = "has note")
        }
        if (onLongTap != null) {
          StyledText(text = "long-press")
        }
      }
    }
  """.trimIndent()

  private val toggleRow = Recognizer.recognizeComponent("app/components/ToggleRow.kt", toggleRowKt, tokens = tokens)!!.spec
  private val registry = MapComponentRegistry(listOf(toggleRow))

  private fun expand(vararg args: Pair<String, Any?>): WidgetNode =
    (expandForPreview(WidgetNode("ToggleRow", mapOf(*args)), registry) as Expansion.Transparent).tree

  private fun texts(n: WidgetNode): List<String> =
    (if (n.type == "StyledText") listOfNotNull(n.props["text"] as? String) else emptyList()) + n.children.flatMap(::texts)

  @Test fun everyConditionalFormKeepsTheComponentTransparent() {
    assertTrue(toggleRow.transparent, toggleRow.diagnostic)
    assertNull(toggleRow.diagnostic)
    // A nullable function parameter is an optional event.
    assertEquals(listOf("onTap" to true, "onLongTap" to false), toggleRow.events.map { it.name to it.required })
    val body = assertNotNull(toggleRow.body)
    val color = body.children[0].props["colorArgb"]
    assertEquals(
      Conditional(Cond("selected"), Token("C.Ink", -14737633, "app.components.C"), Token("C.White", -1, "app.components.C")),
      color,
    )
    assertEquals(Conditional(Cond("selected"), Action("onTap"), null), body.children[3].props["onClick"])
    assertEquals(Conditional(Cond("raised"), 8.0, 0.0), body.children[2].props["mod.Margin.margin.top"])
    // The body travels to the editor as JSON (/components) and back unchanged.
    assertEquals(body, deserializeTree(serializeTree(body)))
  }

  @Test fun theInstanceArgumentsDecideEachCondition() {
    val on = expand("label" to "A", "selected" to true, "onTap" to Action("open"))
    assertEquals(-14737633, (on.children[0].props["colorArgb"] as Token).value)
    assertEquals("KeyboardArrowUp", on.children[1].props["name"])
    assertEquals(0.0, on.children[2].props["mod.Margin.margin.top"]) // raised omitted -> default false
    assertEquals(Action("open"), on.children[3].props["onClick"])
    // `if (!selected) … else …` spliced in place; the omitted String/nullable params read their defaults.
    assertEquals(listOf("A", "x", "tap", "on"), texts(on))

    val off = expand("label" to "B", "selected" to false, "subtitle" to "sub", "note" to "n", "raised" to true, "onTap" to Action("open"), "onLongTap" to Action("long"))
    assertEquals(-1, (off.children[0].props["colorArgb"] as Token).value)
    assertEquals("KeyboardArrowDown", off.children[1].props["name"])
    assertEquals(8.0, off.children[2].props["mod.Margin.margin.top"])
    assertNull(off.children[3].props["onClick"]) // `else null`: no click handler
    assertEquals(listOf("B", "x", "tap", "off", "sub", "has note", "long-press"), texts(off))
  }

  @Test fun aScreenBindingArgumentLeavesTheConditionToThePreviewMocks() {
    val tree = expand("label" to "A", "selected" to Bind("isOn"), "subtitle" to Bind("sub"), "onTap" to Action("open"))
    // Re-pointed at the screen field the argument binds.
    assertEquals(Cond("isOn"), (tree.children[0].props["colorArgb"] as Conditional).cond)
    val cond = tree.children.first { it.type == "Condition" && it.props["field"] == "isOn" }
    assertEquals(true, cond.props["negate"])
    assertTrue(tree.children.any { it.type == "Condition" && it.props["field"] == "sub" && it.props["test"] == "empty" })
  }

  @Test fun aComponentPreviewedWithoutArgumentsUsesTheEditorSamples() {
    // No `selected` argument and no default: its editor sample (false) decides.
    val tree = expand("label" to "A", "onTap" to Action("open"))
    assertEquals(listOf("A", "x", "tap", "off"), texts(tree))
  }

  @Test fun aConditionHoldsTheWayAnUnmockedBindingRenders() {
    assertTrue(Cond("x").holds(null, present = false))
    assertFalse(Cond("x", negate = true).holds("true"))
    assertTrue(Cond("s", CondTest.Empty).holds(""))
    assertFalse(Cond("s", CondTest.Empty).holds(null, present = false))
    assertTrue(Cond("s", CondTest.Blank, negate = true).holds("a"))
    assertTrue(Cond("n", CondTest.Null).holds(null))
    assertFalse(Cond("n", CondTest.Null).holds(null, present = false))
    assertEquals("b.s.isNotBlank()", Cond("s", CondTest.Blank, negate = true).kotlin { "b.$it" })
    assertEquals("n != null", Cond("n", CondTest.Null, negate = true).kotlin { it })
  }

  @Test fun aNonGrammarRootArgumentIsNamedInsteadOfTheSlot() {
    val card = Recognizer.recognizeComponent(
      "PanelCard.kt",
      """
        import androidx.compose.runtime.Composable
        import dev.keliver.material.compose.StyledBox
        @Composable
        fun PanelCard(content: @Composable () -> Unit) {
          StyledBox(cornerRadiusDp = radius(), fillWidth = true) {
            content()
          }
        }
      """.trimIndent(),
    )!!.spec
    assertFalse(card.transparent)
    val d = assertNotNull(card.diagnostic)
    assertTrue("`StyledBox(…)` argument `cornerRadiusDp = radius()` is outside the portal grammar" in d, d)
    assertFalse("found 0" in d, d)
  }

  @Test fun aNullableContentSlotIsReported() {
    val card = Recognizer.recognizeComponent(
      "PanelCard.kt",
      """
        import androidx.compose.runtime.Composable
        import dev.keliver.layout.compose.Column
        @Composable
        fun PanelCard(title: String, footer: (@Composable () -> Unit)?) {
          Column { }
        }
      """.trimIndent(),
    )!!.spec
    assertFalse(card.transparent)
    assertTrue(card.diagnostic!!.startsWith("nullable content slots are not supported (found: footer)"), card.diagnostic)
  }

  @Test fun anUnsupportedConditionIsNamed() {
    val c = Recognizer.recognizeComponent(
      "NewBadge.kt",
      """
        import androidx.compose.runtime.Composable
        import dev.keliver.layout.compose.Column
        import dev.keliver.material.compose.StyledText
        @Composable
        fun NewBadge(count: Int) {
          Column {
            if (count > 0) {
              StyledText(text = "new")
            }
          }
        }
      """.trimIndent(),
    )!!.spec
    assertTrue(c.diagnostic!!.contains("condition `count > 0` is outside the portal grammar"), c.diagnostic)
  }
}
