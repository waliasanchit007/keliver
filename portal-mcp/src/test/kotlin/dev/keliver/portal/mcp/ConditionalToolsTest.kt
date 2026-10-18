package dev.keliver.portal.mcp

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** K3: agents see conditional values — in the op schema, and in usages of fields, actions and tokens. */
class ConditionalToolsTest {
  @Test fun theOpSchemaDescribesConditionals() {
    val catalog = Tools.registry.single { it.name == "get_catalog" }.call(JsonObject(emptyMap())).toString()
    assertTrue("PropValue.<Lit|Bind|Action|Token|Conditional>" in catalog, catalog)
    assertTrue("Conditional = {field, test?, negate?, whenTrue?, whenFalse?}" in catalog, catalog)
    assertTrue("Lit/Bind/Action/Token/Conditional" in Tools.registry.single { it.name == "get_document" }.descriptor.toString())
  }

  @Test fun findUsagesLooksInsideConditionsAndBranches() {
    val ink = PropValue.Token("C.Ink", lit(-14737633), "app.components.C")
    val doc = UiDocument(
      "home",
      DocNode.Widget(
        Handle(1), "Column",
        children = listOf(
          DocNode.Widget(Handle(2), "Button", props = mapOf(
            "containerArgb" to PropValue.Conditional("selected", whenTrue = ink, whenFalse = lit(-1)),
            "onClick" to PropValue.Conditional("enabled", whenTrue = PropValue.Action("tap")),
          )),
          DocNode.Widget(Handle(3), "Condition", props = mapOf("field" to lit("selected"))),
        ),
      ),
      Contract(), 0, 4,
    )
    assertEquals(
      listOf(
        "home: Button#2.containerArgb is chosen by field 'selected'",
        "home: Condition#3 tests field 'selected'",
      ),
      Tools.usagesIn("home", doc, "selected"),
    )
    assertEquals(listOf("home: Button#2.onClick wires action 'tap'"), Tools.usagesIn("home", doc, "tap"))
    assertEquals(1, Tools.usagesIn("home", doc, "C.Ink").size)
  }
}
