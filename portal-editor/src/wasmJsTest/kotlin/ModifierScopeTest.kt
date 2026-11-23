/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import dev.keliver.portal.WidgetNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModifierScopeTest {
  private val tree = WidgetNode(
    "StyledBox",
    emptyMap(),
    id = 1,
    children = listOf(
      WidgetNode(
        "Row",
        emptyMap(),
        id = 2,
        children = listOf(
          WidgetNode(
            "Condition",
            mapOf("field" to "show"),
            id = 3,
            children = listOf(
              WidgetNode("StyledText", mapOf("text" to "a"), id = 4),
            ),
          ),
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
