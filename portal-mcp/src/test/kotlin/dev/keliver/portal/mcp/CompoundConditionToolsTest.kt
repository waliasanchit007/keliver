package dev.keliver.portal.mcp

import dev.keliver.portal.document.CondTerm
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

/** K6: agents see equality and compound conditions — in the op schema, and in usages of fields and tokens. */
class CompoundConditionToolsTest {
  @Test fun theOpSchemaDescribesEqualityAndCompoundConditions() {
    val catalog = Tools.registry.single { it.name == "get_catalog" }.call(JsonObject(emptyMap())).toString()
    assertTrue("Conditional = {field, test?, negate?, whenTrue?, whenFalse?, operand?, more?, join?}" in catalog, catalog)
    assertTrue("= `field == operand` where operand is a Lit s/i/b or a Token" in catalog, catalog)
    assertTrue("field.1/test.1/negate.1/operand.1" in catalog, catalog)
  }

  @Test fun findUsagesLooksAtEveryTermAndEachOperand() {
    val accent = PropValue.Token("C.Accent", lit("XY"), "app.components.C")
    val doc = UiDocument(
      "home",
      DocNode.Widget(
        Handle(1), "Column",
        children = listOf(
          DocNode.Widget(Handle(2), "StyledText", props = mapOf(
            "text" to PropValue.Conditional("kind", "eq", whenTrue = lit("a"), whenFalse = lit("b"), operand = accent, more = listOf(CondTerm("open"))),
          )),
          DocNode.Widget(Handle(3), "Condition", props = mapOf("field" to lit("kind"), "test" to lit("eq"), "operand" to lit("x"), "field.1" to lit("open"))),
        ),
      ),
      Contract(), 0, 4,
    )
    assertEquals(
      listOf("home: StyledText#2.text is chosen by field 'open'", "home: Condition#3 tests field 'open'"),
      Tools.usagesIn("home", doc, "open"),
    )
    assertEquals(listOf("home: StyledText#2.text uses token 'C.Accent' (app.components.C.Accent)"), Tools.usagesIn("home", doc, "C.Accent"))
  }
}
