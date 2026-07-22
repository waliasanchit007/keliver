package dev.keliver.portal.render

import dev.keliver.portal.Action
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
}
