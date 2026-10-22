import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E40: a new screen's or project's name is checked before anything is created. */
class EditorNamesTest {
  private val screens = listOf("home", "order_status")

  @Test fun aKotlinReadableNewNameIsAccepted() {
    assertNull(nameProblem("faq", screens, "screen"))
    assertNull(nameProblem("  order_review2 ", screens, "screen"))
  }

  @Test fun aNameThatCannotNameAFileAndAFunctionIsRefused() {
    assertTrue("letter" in nameProblem("9lives", screens, "screen")!!)
    assertTrue("letter" in nameProblem("my screen", screens, "screen")!!)
    assertTrue("letter" in nameProblem("a-b", screens, "screen")!!)
    assertEquals("Enter a name for the screen", nameProblem(" ", screens, "screen"))
  }

  @Test fun anExistingNameIsRefusedInAnyCase() {
    assertEquals("A screen named “home” already exists", nameProblem("Home", screens, "screen"))
  }
}
