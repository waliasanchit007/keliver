package dev.keliver.portal.document

import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTest {
  private val doc = UiDocument(
    screen = "main",
    root = DocNode.Widget(Handle(1), "Column", children = listOf(
      DocNode.Widget(Handle(2), "StyledText", props = mapOf(
        "text" to PropValue.Bind("title"),
        "fontSize" to lit(22),
      )),
      DocNode.Widget(Handle(3), "Button",
        props = mapOf("text" to lit("Buy"), "onClick" to PropValue.Action("buy")),
        modifiers = mapOf("Padding.allDp" to lit(8))),
      DocNode.RawCode(Handle(4), "if (b.x) { }", kindHint = "condition"),
    )),
    contract = Contract(fields = mapOf("title" to "String"), actions = listOf("buy")),
    version = 3, nextHandle = 10,
  )

  @Test fun projectsWithMocks() {
    val tree: WidgetNode = doc.toWidgetTree(mocks = mapOf("title" to "Hello"))
    assertEquals("Column", tree.type)
    assertEquals("Hello", tree.children[0].props["text"])          // bind -> mock value
    assertEquals(22, tree.children[0].props["fontSize"])
    assertEquals(Action("buy"), tree.children[1].props["onClick"]) // V1 typed action value
    assertEquals(8, tree.children[1].props["mod.Padding.allDp"])   // V1 modifier convention
    assertEquals("RawCode", tree.children[2].type)
    assertEquals("condition", tree.children[2].props["kindHint"])
  }

  @Test fun unmockedBindStaysTypedForTheV1Pipeline() {
    val tree = doc.toWidgetTree()
    assertEquals(Bind("title"), tree.children[0].props["text"])
  }
}
