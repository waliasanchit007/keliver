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

  @Test fun aComponentIsOnlyShownWhenTheRelaySentThatComponent() {
    val card = home.open("SummaryCard")
    assertTrue(card.isAnsweredBy("default/component:SummaryCard"))
    // K1: a relay that cannot open components sends its active screen instead.
    assertFalse(card.isAnsweredBy("default/home"))
    assertFalse(card.isAnsweredBy("default/component:StatTile"))
    // A screen is shown as the relay names it (older relays name screens their own way).
    assertTrue(home.isAnsweredBy("home"))
  }

  @Test fun onlyAProjectComponentsInstanceOpens() {
    val reg = MapComponentRegistry(listOf(ComponentSpec("SummaryCard", emptyList(), emptyList(), emptyMap())))
    assertTrue(isComponentInstance(WidgetNode("SummaryCard"), reg))
    assertFalse(isComponentInstance(WidgetNode("StyledText"), reg))
    assertFalse(isComponentInstance(null, reg))
  }
}

/** G1: undo follows the session's edits across the screen and the components opened from it. */
class SessionHistoryTest {
  private val home = EditTarget("default", "home")
  private val card = home.open("Card")

  @Test fun undoStepsTheNewestEditWhereverItWasMade() {
    val h = SessionHistory()
    h.edited(home) // an older screen edit
    h.edited(card) // then an edit inside the component
    assertTrue(h.undoElsewhere(home))
    assertEquals(card, h.undoTarget(home), "back on the screen, the component's edit is undone first")
    h.undone(card)
    assertEquals(home, h.undoTarget(home))
    assertEquals(card, h.redoTarget(home))
    h.redone(card)
    assertEquals(card, h.undoTarget(home))
  }

  @Test fun aNewEditClearsRedoAndAnEmptyHistoryUndoesTheOpenDocument() {
    val h = SessionHistory()
    assertEquals(home, h.undoTarget(home), "after a reload: the open document's own stack")
    h.edited(card); h.undone(card)
    h.edited(home)
    assertFalse(h.redoElsewhere(home))
    assertEquals(home, h.redoTarget(home))
  }

  @Test fun aComponentIsOneDocumentHoweverItWasOpenedAndAFailedStepMovesNothing() {
    val h = SessionHistory()
    h.edited(home.open("Row").open("Card"))
    assertFalse(h.undoElsewhere(home.open("Card")), "Card opened directly is the same document")
    h.skipped(card, undo = true)
    assertEquals(home, h.undoTarget(home), "a step the relay could not take is dropped, not redone")
    assertEquals(home, h.redoTarget(home))
    h.edited(card); h.clear()
    assertEquals(home, h.undoTarget(home), "another screen starts over")
  }

  @Test fun theHistoryIsKeptAcrossAReload() {
    val h = SessionHistory()
    h.edited(home); h.edited(home.open("Card").open("Row")); h.undone(home.open("Card").open("Row"))
    val back = SessionHistory().also { it.load(h.encode()) }
    assertEquals(home, back.undoTarget(home))
    assertEquals(home.open("Card").open("Row"), back.redoTarget(home))
    assertEquals(home, SessionHistory().also { it.load("not json") }.undoTarget(home))
  }
}
