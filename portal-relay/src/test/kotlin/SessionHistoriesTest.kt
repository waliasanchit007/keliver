import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** L2 (feedback round 5): a screen's cross-document undo order, kept by the relay for every browser. */
class SessionHistoriesTest {
  private val order = """{"undo":[["default","home","Card"],["default","home"]],"redo":[]}"""

  @Test fun aScreensHistoryIsKeptForAnyEditorThatAsks() {
    val h = SessionHistories()
    assertNull(h.get("default", "home"), "none kept since the relay started")
    assertTrue(h.put("default", "home", order))
    assertEquals(order, h.get("default", "home"))
    assertNull(h.get("default", "orders"), "each screen has its own")
    assertNull(h.get("other", "home"), "and each project")
  }

  @Test fun onlyABoundedJsonObjectIsKept() {
    val h = SessionHistories(maxChars = 100)
    assertFalse(h.put("default", "home", "not json"))
    assertFalse(h.put("default", "home", "[1,2]"), "an array is no history")
    assertFalse(h.put("default", "home", """{"undo":"${"x".repeat(200)}"}"""), "too long")
    assertNull(h.get("default", "home"), "nothing refused was kept")
  }
}
