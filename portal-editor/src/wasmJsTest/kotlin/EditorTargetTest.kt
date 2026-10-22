import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.WidgetNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** E14: the document the editor has open — a screen, or a component opened from it — and the way back. */
class EditorTargetTest {
  private val home = EditTarget("default", "home")

  @Test fun aScreenIsAddressedByItsNameAndAComponentByItsOwn() {
    assertEquals("project=default&screen=home", home.query)
    assertEquals("default/home", home.key)
    val card = home.open("SummaryCard")
    assertEquals("project=default&component=SummaryCard", card.query)
    assertEquals("default/component:SummaryCard", card.key)
  }

  @Test fun theBreadcrumbIsTheScreenThenEachComponentOpenedInTurn() {
    val inner = home.open("SummaryCard").open("StatTile")
    assertEquals(listOf("home", "SummaryCard", "StatTile"), inner.crumbs)
    assertEquals(home.open("SummaryCard"), inner.backTo(1))
    assertEquals(home, inner.backTo(0))
  }

  @Test fun openingAComponentAlreadyOnThePathGoesBackToItInsteadOfLooping() {
    val inner = home.open("Card").open("Row").open("Card")
    assertEquals(listOf("Card"), inner.components)
  }

  @Test fun onlyAProjectComponentsInstanceOpens() {
    val reg = MapComponentRegistry(listOf(ComponentSpec("SummaryCard", emptyList(), emptyList(), emptyMap())))
    assertTrue(isComponentInstance(WidgetNode("SummaryCard"), reg))
    assertFalse(isComponentInstance(WidgetNode("StyledText"), reg))
    assertFalse(isComponentInstance(null, reg))
  }
}
