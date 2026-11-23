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

/**
 * Click-to-select on the canvas: everything a component instance draws selects that instance,
 * including the widgets of a component nested inside its definition. A nested component's own id
 * is the DEFINITION's id, which can equal an unrelated screen node's handle (a click inside a
 * card selected an image elsewhere on the screen).
 */
class SelectionHandlesTest {
  @Test fun aNestedComponentInsideAnExpansionSelectsTheOuterInstance() {
    // A stats card (screen handle 12) whose definition holds a stat-column component with
    // definition-local id 2 — the same number as the screen's root column.
    val nested = WidgetNode("StatTile", emptyMap(), emptyList(), id = 2)
    val expansion = WidgetNode("StyledBox", emptyMap(), listOf(nested), id = 1)
    val tagged = tagWith(expansion, 12)
    val nestedTagged = tagged.children.single()
    assertEquals(12, nestedTagged.props["mod.SelectionTag.handle"])
    assertEquals(12, instanceHandle(nestedTagged), "not its definition id 2")
  }

  @Test fun aScreenLevelInstanceKeepsItsOwnHandle() {
    val instance = selectionTagged(WidgetNode("SummaryCard", emptyMap(), emptyList(), id = 12))
    assertEquals(12, instanceHandle(instance))
  }
}
