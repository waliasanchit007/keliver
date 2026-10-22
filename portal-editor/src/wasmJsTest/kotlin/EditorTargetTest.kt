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
    val card = home.open("StatsCard")
    assertEquals("project=default&component=StatsCard", card.query)
    assertEquals("default/component:StatsCard", card.key)
  }

  @Test fun theBreadcrumbIsTheScreenThenEachComponentOpenedInTurn() {
    val inner = home.open("StatsCard").open("StatColumn")
    assertEquals(listOf("home", "StatsCard", "StatColumn"), inner.crumbs)
    assertEquals(home.open("StatsCard"), inner.backTo(1))
    assertEquals(home, inner.backTo(0))
  }

  @Test fun openingAComponentAlreadyOnThePathGoesBackToItInsteadOfLooping() {
    val inner = home.open("Card").open("Row").open("Card")
    assertEquals(listOf("Card"), inner.components)
  }

  @Test fun onlyAProjectComponentsInstanceOpens() {
    val reg = MapComponentRegistry(listOf(ComponentSpec("StatsCard", emptyList(), emptyList(), emptyMap())))
    assertTrue(isComponentInstance(WidgetNode("StatsCard"), reg))
    assertFalse(isComponentInstance(WidgetNode("StyledText"), reg))
    assertFalse(isComponentInstance(null, reg))
  }
}
