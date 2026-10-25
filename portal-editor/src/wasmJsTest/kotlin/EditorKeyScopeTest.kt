import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F1: a key that changes the tree acts only from the tree, so a field that went away cannot pass its keys to it. */
class EditorKeyScopeTest {
  @Test fun theCanvasAndOutlineAreTheTreeAndAFieldNeverIs() {
    assertEquals(KeyScope.Tree, keyScopeOf(onCanvasOrOutline = true, isField = false))
    assertEquals(KeyScope.Elsewhere, keyScopeOf(onCanvasOrOutline = true, isField = true)) // the inline text editor
    assertEquals(KeyScope.Elsewhere, keyScopeOf(onCanvasOrOutline = false, isField = false)) // a panel, the page body
  }

  @Test fun deletingDuplicatingCuttingAndPastingChangeTheTreeMovingDoesNot() {
    for (k in listOf("Delete", "Backspace")) assertTrue(changesTheTree(k, mod = false))
    for (k in listOf("d", "x", "v", "D")) assertTrue(changesTheTree(k, mod = true))
    assertFalse(changesTheTree("d", mod = false))
    assertFalse(changesTheTree("c", mod = true))
    assertFalse(changesTheTree("ArrowDown", mod = false))
  }

  @Test fun aBackspaceAfterAFieldWentAwayOrAHeldKeyDeletesNothing() {
    assertTrue(treeKeyAllowed(KeyScope.Tree, repeat = false))
    assertFalse(treeKeyAllowed(KeyScope.Elsewhere, repeat = false))
    assertFalse(treeKeyAllowed(KeyScope.Tree, repeat = true))
  }

  @Test fun theSameFieldGetsTheKeyboardBackAfterARerender() {
    assertEquals(2, refocusIndex("Text", 0, listOf("Width", null, "Text"), countBefore = 2))
    // Not unique or no label: by position, only when the panel has as many fields as before.
    assertEquals(1, refocusIndex("x", 1, listOf("x", "x"), countBefore = 2))
    assertNull(refocusIndex(null, 1, listOf("a", "b", "c"), countBefore = 2))
    assertNull(refocusIndex(null, 5, listOf("a"), countBefore = 1))
  }
}
