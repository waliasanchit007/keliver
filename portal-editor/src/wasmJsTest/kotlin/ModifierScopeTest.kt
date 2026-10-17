import dev.keliver.portal.WidgetNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModifierScopeTest {
  private val tree = WidgetNode(
    "StyledBox", emptyMap(), id = 1,
    children = listOf(
      WidgetNode(
        "Row", emptyMap(), id = 2,
        children = listOf(
          WidgetNode("Condition", mapOf("field" to "show"), id = 3, children = listOf(
            WidgetNode("StyledText", mapOf("text" to "a"), id = 4),
          )),
          WidgetNode("Column", emptyMap(), id = 5, children = listOf(WidgetNode("StyledText", emptyMap(), id = 6))),
        ),
      ),
      WidgetNode("StyledText", mapOf("text" to "b"), id = 7),
    ),
  )

  @Test fun parentScopeLooksThroughLogicNodes() {
    assertEquals(null, scopeParentType(tree, 1))
    assertEquals("StyledBox", scopeParentType(tree, 2))
    assertEquals("Row", scopeParentType(tree, 4)) // through the Condition
    assertEquals("Column", scopeParentType(tree, 6))
    assertEquals(null, scopeParentType(tree, 99))
  }

  @Test fun scopedModifiersAreOfferedOnlyUnderTheirScopes() {
    val underRow = modifiersOfferedUnder("Row")
    assertTrue("Flex" in underRow && "Margin" in underRow && "VerticalAlignment" in underRow)
    assertFalse("HorizontalAlignment" in underRow) // RowScope has no horizontalAlignment
    val underBox = modifiersOfferedUnder("Box")
    assertTrue("HorizontalAlignment" in underBox && "LayoutSize" in underBox)
    assertFalse("Flex" in underBox)
    val elsewhere = modifiersOfferedUnder("StyledBox")
    assertFalse(listOf("Flex", "Margin", "Width", "LayoutSize").any { it in elsewhere })
    // Unscoped modifiers are offered everywhere, including at the root.
    assertTrue("Padding" in modifiersOfferedUnder(null) && "Size" in modifiersOfferedUnder(null))
  }
}
