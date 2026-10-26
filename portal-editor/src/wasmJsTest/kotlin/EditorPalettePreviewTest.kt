import dev.keliver.portal.WidgetNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Owner, 25 Oct: the palette's preview draws what a row adds — never a document's node. */
class EditorPalettePreviewTest {
  @Test fun anEmptyContainerPreviewsWithSampleItems() {
    val column = previewOf(WidgetNode("Column"), acceptsChildren = true)
    assertEquals(listOf("Item 1", "Item 2", "Item 3"), column.children.map { it.props["text"] })
    val button = previewOf(WidgetNode("Button", mapOf("text" to "Button")), acceptsChildren = false)
    assertTrue(button.children.isEmpty(), "a leaf is drawn as it is inserted")
    val filled = WidgetNode("Box", children = listOf(WidgetNode("Icon")))
    assertEquals(listOf("Icon"), previewOf(filled, acceptsChildren = true).children.map { it.type }, "a container with content keeps it")
  }

  @Test fun everyPreviewNodeHasAnIdNoDocumentHandleHas() {
    val p = previewOf(WidgetNode("Column", id = 5, children = listOf(WidgetNode("StyledText", id = 6))), acceptsChildren = true)
    fun ids(n: WidgetNode): List<Int> = listOf(n.id) + n.children.flatMap(::ids)
    assertTrue(ids(p).all { it < 0 }, "${ids(p)}")
    assertEquals(ids(p).size, ids(p).toSet().size, "and each its own")
  }
}
