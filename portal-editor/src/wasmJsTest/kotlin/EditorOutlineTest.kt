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
    assertEquals("for order in orders", outlineHint(n(9, "Repeat", props = mapOf("items" to "orders", "item" to "order"))))
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

  // F3: a canvas drop in a gap goes between the children nearest the pointer.
  //  10 Box (root, 0..400 × 0..1000)
  //    11 Column (0..400 × 0..1000):  12 Text (y 0..100)   13 Card (y 140..300, empty)   14 Text (y 340..400)
  //    15 Row (y 500..560):  16 Text (x 0..100)   17 Text (x 200..300)
  private val page = n(10, "Box", n(11, "Column", n(12, "StyledText"), n(13, "Box"), n(14, "StyledText"), n(15, "Row", n(16, "StyledText"), n(17, "StyledText"))))
  private val boxes = mapOf(
    10 to DropBox(0.0, 0.0, 400.0, 1000.0), 11 to DropBox(0.0, 0.0, 400.0, 1000.0),
    12 to DropBox(0.0, 0.0, 400.0, 100.0), 13 to DropBox(0.0, 140.0, 400.0, 300.0), 14 to DropBox(0.0, 340.0, 400.0, 400.0),
    15 to DropBox(0.0, 500.0, 400.0, 560.0), 16 to DropBox(0.0, 500.0, 100.0, 560.0), 17 to DropBox(200.0, 500.0, 300.0, 560.0),
  )
  private val pageAccepts = { node: WidgetNode -> node.type in setOf("Box", "Column", "Row") }
  private fun drop(hit: Int, x: Double, y: Double) = canvasDropPlace(page, hit, x, y, { boxes[it.id] }, pageAccepts)

  @Test fun aDropInTheGapBetweenTwoSectionsLandsBetweenThemNotOnTopOfTheRoot() {
    // The gap between the text and the card: the hit test finds only the root (or the column).
    assertEquals(13 to DropZone.Before, drop(10, 200.0, 125.0), "nearer the card: before it")
    assertEquals(12 to DropZone.After, drop(11, 200.0, 105.0), "nearer the text: after it")
    assertEquals(14 to DropZone.After, drop(10, 200.0, 440.0), "below the last text, above the row")
  }

  @Test fun anEmptyContainerTakesTheDropInsideAndALeafBeforeOrAfterItself() {
    assertEquals(13 to DropZone.Inside, drop(13, 200.0, 220.0))
    assertEquals(12 to DropZone.Before, drop(12, 200.0, 20.0))
    assertEquals(12 to DropZone.After, drop(12, 200.0, 80.0))
  }

  @Test fun inARowTheGapIsReadAcrossNotDown() {
    assertEquals(17 to DropZone.Before, drop(15, 180.0, 530.0))
    assertEquals(16 to DropZone.After, drop(15, 120.0, 530.0))
    // On a child of a row, its left and right halves (not its top and bottom).
    assertEquals(16 to DropZone.After, drop(16, 90.0, 505.0))
    assertEquals(17 to DropZone.Before, drop(17, 210.0, 555.0))
    assertTrue(laidOutAcross(page, 16) { boxes[it.id] })
    assertTrue(!laidOutAcross(page, 12) { boxes[it.id] })
  }

  @Test fun aConditionWithoutBoundsOfItsOwnIsPlacedByWhatItDraws() {
    val tree = n(1, "Column", n(2, "StyledText"), n(3, "Condition", n(4, "StyledText")))
    val b = mapOf(1 to DropBox(0.0, 0.0, 100.0, 300.0), 2 to DropBox(0.0, 0.0, 100.0, 50.0), 4 to DropBox(0.0, 200.0, 100.0, 250.0))
    fun box(node: WidgetNode): DropBox? = b[node.id] ?: node.children.mapNotNull { box(it) }.reduceOrNull { x, y -> x union y }
    val accepts = { node: WidgetNode -> node.type == "Column" || node.type == "Condition" }
    assertEquals(3 to DropZone.Before, canvasDropPlace(tree, 1, 50.0, 180.0, ::box, accepts), "above the condition's content: before the condition")
  }
}
