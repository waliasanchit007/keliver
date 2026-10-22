import kotlin.test.Test
import kotlin.test.assertEquals

/** Canvas zoom steps and fit-to-window. */
class EditorCanvasTest {
  @Test fun zoomStepsUpAndDownThroughTheLadderAndStopsAtItsEnds() {
    assertEquals(1.25, nextZoom(1.0, up = true))
    assertEquals(0.9, nextZoom(1.0, up = false))
    assertEquals(ZOOM_MAX, nextZoom(ZOOM_MAX, up = true))
    assertEquals(ZOOM_MIN, nextZoom(ZOOM_MIN, up = false))
    assertEquals(1.0, nextZoom(0.93, up = true), "an off-ladder level steps to the next rung")
  }

  @Test fun fitNeverEnlargesAndFitsTheTighterSide() {
    assertEquals(1.0, fitZoom(390, 780, 2000.0, 2000.0))
    assertEquals(0.5, fitZoom(390, 780, 2000.0, 390.0))
    assertEquals(0.25, fitZoom(390, 780, 10.0, 10.0), "never below the minimum")
    assertEquals(1.0, fitZoom(0, 780, 100.0, 100.0), "a degenerate frame keeps 100%")
  }
}
