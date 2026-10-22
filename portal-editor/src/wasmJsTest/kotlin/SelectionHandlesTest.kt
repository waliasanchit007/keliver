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
