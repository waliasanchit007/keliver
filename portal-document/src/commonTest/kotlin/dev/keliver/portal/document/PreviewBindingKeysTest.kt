package dev.keliver.portal.document

import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.collectPreviewBindingKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewBindingKeysTest {
  @Test fun includesDirectConditionAndNamespacedRepeatBindings() {
    val tree = WidgetNode(
      "Column",
      children = listOf(
        WidgetNode("StyledText", mapOf("text" to Bind("title"))),
        WidgetNode("Condition", mapOf("field" to "enabled")),
        WidgetNode(
          "Repeat",
          mapOf("items" to "notes", "item" to "note"),
          listOf(
            WidgetNode("ListItem", mapOf(
              "headline" to Bind("note.title"),
              "supporting" to Bind("subtitle"),
            )),
          ),
        ),
      ),
    )

    assertEquals(
      setOf("title", "enabled", "notes", "notes.note.title", "subtitle"),
      collectPreviewBindingKeys(tree),
    )
  }
}
