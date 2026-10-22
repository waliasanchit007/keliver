import dev.keliver.portal.WidgetNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The outline: readable rows, collapsible containers, and drops that land where they are aimed. */
class EditorOutlineTest {
  private fun n(id: Int, type: String, vararg children: WidgetNode, props: Map<String, Any?> = emptyMap()) =
    WidgetNode(type, props, children.toList(), id = id)

  //  1 Column
  //    2 Text   3 Box(4 Text)   5 Text
  private val tree = n(1, "Column", n(2, "StyledText"), n(3, "Box", n(4, "StyledText")), n(5, "StyledText"))
  private val accepts = { node: WidgetNode -> node.type == "Column" || node.type == "Box" }

  @Test fun rowsSayWhatTheirNodeDoes() {
    assertEquals("if loading", outlineHint(n(9, "Condition", props = mapOf("field" to "loading"))))
    assertEquals("if !open", outlineHint(n(9, "Condition", props = mapOf("field" to "open", "negate" to true))))
    assertEquals("for deal in deals", outlineHint(n(9, "Repeat", props = mapOf("items" to "deals", "item" to "deal"))))
    assertEquals("↕16", outlineHint(n(9, "Spacer", props = mapOf("height" to 16.0))))
    assertEquals("“Hello”", outlineHint(n(9, "StyledText", props = mapOf("text" to "Hello"))))
    assertEquals("else", outlineHint(n(9, "Else")))
  }

  @Test fun collapsedContainersHideTheirRows() {
    assertEquals(listOf(1, 2, 3, 4, 5), visibleOrder(tree, emptySet()))
    assertEquals(listOf(1, 2, 3, 5), visibleOrder(tree, setOf(3)))
  }

  @Test fun theRowsQuartersDecideBeforeAfterAndInside() {
    assertEquals(DropZone.Before, dropZone(2.0, 24.0, true))
    assertEquals(DropZone.Inside, dropZone(12.0, 24.0, true))
    assertEquals(DropZone.After, dropZone(22.0, 24.0, true))
    assertEquals(DropZone.After, dropZone(13.0, 24.0, false), "a leaf takes no children: its halves")
  }

  @Test fun aDropLandsBeforeAfterOrInsideItsRow() {
    assertEquals(1 to null, dropPlace(tree, 2, DropZone.Before, accepts), "before the first child = first")
    assertEquals(1 to 3, dropPlace(tree, 3, DropZone.After, accepts))
    assertEquals(1 to 2, dropPlace(tree, 3, DropZone.Before, accepts))
    assertEquals(3 to 4, dropPlace(tree, 3, DropZone.Inside, accepts), "inside = after its last child")
    assertNull(dropPlace(tree, 2, DropZone.Inside, accepts), "a text takes no children")
    assertNull(dropPlace(tree, 1, DropZone.Before, accepts), "the root has no siblings")
  }

  @Test fun aMoveThatChangesNothingOrLoopsIsNotSent() {
    assertNull(moveOp(tree, 3, 3 to 4), "into itself")
    assertNull(moveOp(tree, 1, 3 to null), "the root")
    assertNull(moveOp(tree, 5, 1 to 3), "already right after its previous sibling")
    assertNull(moveOp(tree, 3, 1 to 3), "after itself")
    val op = moveOp(tree, 5, 1 to null)
    assertTrue(op is DocOp.MoveNode)
    assertEquals(DocOp.MoveNode(Handle(5), Handle(1), null), op)
  }
}
