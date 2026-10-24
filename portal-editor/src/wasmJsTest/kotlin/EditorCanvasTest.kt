import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

  @Test fun aPresetIsTwoPositiveSizesOrNothing() {
    assertEquals(390 to 780, parsePreset("390x780"))
    assertEquals(null, parsePreset("390x"))
    assertEquals(null, parsePreset("0x780"))
    assertEquals(null, parsePreset("390x780x2"))
    assertEquals(null, parsePreset("wide"))
  }

  @Test fun theOverlayTagNamesTheBoxAndOnTheSelectionItsSize() {
    assertEquals("Column", overlayLabel("Column", component = false, size = null))
    assertEquals("StatTile · component  120×48", overlayLabel("StatTile", component = true, size = 119.6 to 48.2))
  }

  @Test fun aPinchOrModifiedWheelZoomsSmoothlyWithinTheRange() {
    assertTrue(wheelZoom(1.0, -10.0) > 1.0, "a wheel up (pinch out) zooms in")
    assertTrue(wheelZoom(1.0, 10.0) < 1.0)
    assertEquals(ZOOM_MAX, wheelZoom(1.9, -1000.0))
    assertEquals(ZOOM_MIN, wheelZoom(0.26, 1000.0))
  }

  @Test fun zoomShortcutsStepFitAndReset() {
    val fit = { 0.8 }
    assertEquals(nextZoom(1.0, up = true), zoomForKeys("Equal", "=", mod = true, shift = false, current = 1.0, fit = fit))
    assertEquals(nextZoom(1.0, up = false), zoomForKeys("Minus", "-", mod = true, shift = false, current = 1.0, fit = fit))
    assertEquals(0.8, zoomForKeys("Digit1", "!", mod = false, shift = true, current = 1.0, fit = fit))
    assertEquals(1.0, zoomForKeys("Digit0", ")", mod = false, shift = true, current = 0.5, fit = fit))
    assertEquals(null, zoomForKeys("KeyD", "d", mod = true, shift = false, current = 1.0, fit = fit), "⌘D is not a zoom")
  }

  @Test fun theSelectionTagNeverCoversATopEdgeNode() {
    assertEquals(TagPlace.Above, tagPlace(top = 40.0, bottom = 80.0, frameHeight = 780.0))
    assertEquals(TagPlace.Below, tagPlace(top = 0.0, bottom = 40.0, frameHeight = 780.0), "the headline at the very top")
    assertEquals(TagPlace.Inside, tagPlace(top = 0.0, bottom = 775.0, frameHeight = 780.0), "a box filling the frame")
  }

  @Test fun aMouseNotchZoomsOneStepNotToDouble() {
    assertTrue(wheelZoom(1.0, -300.0) in 1.1..1.15, "a notch reported as 300 px")
    assertTrue(wheelZoom(1.0, -3.0, lines = true) in 1.1..1.15, "a notch reported as 3 lines")
    assertEquals(1.0 * kotlin.math.exp(0.01), wheelZoom(1.0, -4.0), 1e-9) // a pinch's small delta is as before
  }

  @Test fun anOpenedComponentSitsInsetOnAStageNothingSelects() {
    val body = selectionTagged(dev.keliver.portal.WidgetNode("Column", id = 7))
    val stage = componentStage(body)
    assertEquals(listOf(body), stage.children, "the component's body is drawn whole, inside the stage")
    assertEquals(16.0, stage.props["margin.start"])
    assertEquals(24.0, stage.props["margin.top"], "not flush with the frame's top edge")
    assertTrue("mod.SelectionTag.handle" !in stage.props, "a click on the stage selects nothing")
    assertTrue(stage.id < 0, "the stage's id is no document handle")
  }

  @Test fun aSmallComponentOpensZoomedInAndATallOneAsBefore() {
    // L10: the frame's width and the component's drawn height decide, at most 200%.
    assertEquals(2.0, componentZoom(390, 172.0, 1000.0, 860.0), "a short component in a wide canvas: 200%")
    assertEquals(1.53, componentZoom(390, 172.0, 600.0, 860.0), "as wide as the canvas allows")
    assertEquals(1.43, componentZoom(390, 600.0, 1000.0, 860.0), "as tall as it allows")
    assertEquals(1.0, componentZoom(390, 1400.0, 1000.0, 860.0), "a tall component is never zoomed out")
    assertEquals(1.0, componentZoom(0, 172.0, 1000.0, 860.0), "nothing measured: 100%")
  }
}
