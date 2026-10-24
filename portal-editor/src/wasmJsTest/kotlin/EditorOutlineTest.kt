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
    assertEquals("@meta", outlineHint(n(9, "Button", props = mapOf("text" to dev.keliver.portal.Bind("meta")))))
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

  @Test fun aListsRowsAreSeparateInstancesAndACardsWidgetsAreOne() {
    // Three cards, each a background holding two texts, 20 px apart.
    val rects = (0 until 3).flatMap { r ->
      val top = r * 120.0
      listOf(DropBox(0.0, top, 300.0, top + 100), DropBox(10.0, top + 10, 200.0, top + 40), DropBox(10.0, top + 50, 250.0, top + 90))
    }
    assertEquals(listOf(DropBox(0.0, 0.0, 300.0, 100.0), DropBox(0.0, 120.0, 300.0, 220.0), DropBox(0.0, 240.0, 300.0, 340.0)).map { listOf(it.left, it.top, it.right, it.bottom) },
      instanceBoxes(rects.reversed()).map { listOf(it.left, it.top, it.right, it.bottom) })
    // Rows that only touch are separate; an empty rect draws nothing.
    assertEquals(2, instanceBoxes(listOf(DropBox(0.0, 0.0, 100.0, 50.0), DropBox(0.0, 50.0, 100.0, 100.0), DropBox(0.0, 0.0, 0.0, 0.0))).size)
  }

  @Test fun aDropInACardsPaddingLandsInItsColumnNotOverIt() {
    // 1 Box (card, 0..300 × 0..220) holding 2 Column (10..290 × 10..200): 3 Text (y 10..60), 4 Button (y 150..200).
    val card = n(1, "Box", n(2, "Column", n(3, "StyledText"), n(4, "Button")))
    val b = mapOf(1 to DropBox(0.0, 0.0, 300.0, 220.0), 2 to DropBox(10.0, 10.0, 290.0, 200.0),
      3 to DropBox(10.0, 10.0, 290.0, 60.0), 4 to DropBox(10.0, 150.0, 290.0, 200.0))
    val accepts = { node: WidgetNode -> node.type == "Box" || node.type == "Column" }
    assertEquals(4 to DropZone.After, canvasDropPlace(card, 1, 150.0, 212.0, { b[it.id] }, accepts), "below the last button, in the padding")
    assertEquals(3 to DropZone.Before, canvasDropPlace(card, 1, 150.0, 4.0, { b[it.id] }, accepts), "above the first text, in the padding")
  }

  @Test fun aDropBelowALoneCardInATallColumnLandsAfterTheCardNotInsideIt() {
    // Review 5: 1 Column (the screen, 0..300 × 0..780) holding only 2 Box (a card, 16..284 × 16..236)
    // around 3 Column (26..274 × 26..226): 4 Text, 5 Button.
    val page = n(1, "Column", n(2, "Box", n(3, "Column", n(4, "StyledText"), n(5, "Button"))))
    val b = mapOf(1 to DropBox(0.0, 0.0, 300.0, 780.0), 2 to DropBox(16.0, 16.0, 284.0, 236.0),
      3 to DropBox(26.0, 26.0, 274.0, 226.0), 4 to DropBox(26.0, 26.0, 274.0, 76.0), 5 to DropBox(26.0, 176.0, 274.0, 226.0))
    val accepts = { node: WidgetNode -> node.type == "Box" || node.type == "Column" }
    assertEquals(2 to DropZone.After, canvasDropPlace(page, 1, 150.0, 400.0, { b[it.id] }, accepts), "well below the card: after it")
    assertEquals(5 to DropZone.After, canvasDropPlace(page, 2, 150.0, 232.0, { b[it.id] }, accepts), "in the card's padding: still inside it")
  }

  @Test fun aDropInTheCardsPaddingAroundAnEmptyColumnGoesIntoTheColumn() {
    val card = n(1, "Box", n(2, "Column"))
    val b = mapOf(1 to DropBox(0.0, 0.0, 300.0, 100.0), 2 to DropBox(10.0, 10.0, 290.0, 90.0))
    val accepts = { node: WidgetNode -> node.type == "Box" || node.type == "Column" }
    assertEquals(2 to DropZone.Inside, canvasDropPlace(card, 1, 150.0, 95.0, { b[it.id] }, accepts))
  }

  @Test fun aDropInTheCardsPaddingNeverGoesIntoAListsRowTemplate() {
    val card = n(1, "Box", n(2, "Repeat", n(3, "StyledText")))
    val b = mapOf(1 to DropBox(0.0, 0.0, 300.0, 220.0), 2 to DropBox(10.0, 10.0, 290.0, 210.0), 3 to DropBox(10.0, 10.0, 290.0, 60.0))
    val accepts = { node: WidgetNode -> node.type in setOf("Box", "Column", "Repeat") }
    assertEquals(2 to DropZone.After, canvasDropPlace(card, 1, 150.0, 215.0, { b[it.id] }, accepts), "after the list, not into every row")
  }

  @Test fun afterADeleteTheArrowsGoOnFromTheDeletedNodesPlace() {
    val tree = n(1, "Column", n(2, "StyledText"), n(3, "Box", n(4, "StyledText")), n(5, "Button"))
    assertEquals(3, placeAfterDelete(tree, 2), "its next sibling took its place")
    assertEquals(3, placeAfterDelete(tree, 5), "the last child: the one before it")
    assertEquals(3, placeAfterDelete(tree, 4), "an only child: its parent")
    assertEquals(null, placeAfterDelete(tree, 1), "the root has no place")
  }
}
