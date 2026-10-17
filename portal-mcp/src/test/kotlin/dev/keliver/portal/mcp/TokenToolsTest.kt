package dev.keliver.portal.mcp

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** K2: agents see design tokens — a list_tokens tool, Token in the op schema, token usages. */
class TokenToolsTest {
  @Test fun listTokensIsATool() {
    val tool = Tools.registry.single { it.name == "list_tokens" }
    val description = tool.descriptor.toString()
    assertTrue("design tokens" in description && "PropValue.Token" in description, description)
  }

  @Test fun findUsagesReportsTokenReferencesInPropsAndModifiers() {
    val ink = PropValue.Token("C.Ink", lit(-14737633), "app.components.C")
    val doc = UiDocument(
      "home",
      DocNode.Widget(
        Handle(1), "Column",
        children = listOf(
          DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to lit("a"), "colorArgb" to ink)),
          DocNode.Widget(Handle(3), "StyledText", props = mapOf("text" to lit("b")), modifiers = mapOf("Background.colorArgb" to ink)),
        ),
      ),
      Contract(), 0, 4,
    )
    assertEquals(
      listOf(
        "home: StyledText#2.colorArgb uses token 'C.Ink' (app.components.C.Ink)",
        "home: StyledText#3.mod.Background.colorArgb uses token 'C.Ink' (app.components.C.Ink)",
      ),
      Tools.usagesIn("home", doc, "C.Ink"),
    )
    // By fully-qualified name, a same-named token of another package does not match.
    assertEquals(2, Tools.usagesIn("home", doc, "app.components.C.Ink").size)
    assertEquals(emptyList(), Tools.usagesIn("home", doc, "other.C.Ink"))
    assertEquals("app.components.TopGap", Tools.tokenFqName(PropValue.Token("TopGap", lit(4.0), "app.components.TopGap")))
  }
}
