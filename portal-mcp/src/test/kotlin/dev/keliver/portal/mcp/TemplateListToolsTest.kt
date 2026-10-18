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

/** K4: agents see templates and lists with references — in the op schema, and in usages of fields and tokens. */
class TemplateListToolsTest {
  @Test fun theOpSchemaDescribesTemplatesAndLists() {
    val catalog = Tools.registry.single { it.name == "get_catalog" }.call(JsonObject(emptyMap())).toString()
    assertTrue("PropValue.<Lit|Bind|Action|Token|Conditional|Template|ListOf>" in catalog, catalog)
    assertTrue("Template = {parts}" in catalog, catalog)
    assertTrue("ListOf = {tag, items}" in catalog, catalog)
    assertTrue("Template/ListOf" in Tools.registry.single { it.name == "get_document" }.descriptor.toString())
  }

  @Test fun findUsagesLooksInsideTemplatesAndLists() {
    val start = PropValue.Token("C.Start", lit(-2840), "app.components.C")
    val doc = UiDocument(
      "home",
      DocNode.Widget(
        Handle(1), "Column",
        children = listOf(
          DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to PropValue.Template(listOf(lit("Hi "), PropValue.Bind("name"))))),
          DocNode.Widget(Handle(3), "StyledBox", props = mapOf("gradientColorsArgb" to PropValue.ListOf("li", listOf(start, PropValue.Bind("accent"))))),
        ),
      ),
      Contract(), 0, 4,
    )
    assertEquals(listOf("home: StyledText#2.text binds field 'name'"), Tools.usagesIn("home", doc, "name"))
    assertEquals(listOf("home: StyledBox#3.gradientColorsArgb binds field 'accent'"), Tools.usagesIn("home", doc, "accent"))
    assertEquals(1, Tools.usagesIn("home", doc, "app.components.C.Start").size)
  }
}
