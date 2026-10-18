package dev.keliver.portal.render

import dev.keliver.portal.Action
import dev.keliver.portal.Bind
import dev.keliver.portal.ListOf
import dev.keliver.portal.WidgetNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewBindingsTest {
  @Test fun resolvesEventLiteralAndRowArguments() {
    assertNull(Action("tap").previewArg("ignored"))
    assertEquals("typed text", Action("change", "it").previewArg("typed text"))
    assertEquals("PROFILE", Action("open", "\"PROFILE\"").previewArg(null))
    assertEquals("3", Action("pick", "3").previewArg(null))
    assertEquals("note-42", Action("openNote", "note-42").previewArg(null))
  }

  @Test fun aMockedIntListElementIsADecimalOrAColour() {
    // As the device wire carries it (Serialize): a decimal, #RRGGBB (opaque) or
    // #AARRGGBB; anything else is left out, not shown as 0 (transparent black).
    val node = WidgetNode("StyledBox", mapOf("gradientColorsArgb" to ListOf("li", listOf(1, Bind("accent"), "#00FF00", "x"))))
    try {
      for ((mock, expected) in listOf(
        "255" to listOf(1, 255, 0xFF00FF00.toInt()),
        "#FF0000" to listOf(1, 0xFFFF0000.toInt(), 0xFF00FF00.toInt()),
        "#80FF0000" to listOf(1, 0x80FF0000.toInt(), 0xFF00FF00.toInt()),
        "red" to listOf(1, 0xFF00FF00.toInt()),
      )) {
        PreviewBindings.mocks["accent"] = mock
        assertEquals(expected, node.intListB("gradientColorsArgb"), mock)
      }
      // An unmocked binding previews as 0, as before.
      PreviewBindings.mocks.remove("accent")
      assertEquals(listOf(1, 0, 0xFF00FF00.toInt()), node.intListB("gradientColorsArgb"))
    } finally {
      PreviewBindings.mocks.remove("accent")
    }
  }
}
