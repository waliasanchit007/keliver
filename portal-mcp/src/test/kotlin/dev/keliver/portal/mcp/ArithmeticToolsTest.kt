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

/** K7: agents see Int arithmetic and Ints used as Dps — in the op schema, and in usages of fields and tokens. */
class ArithmeticToolsTest {
  @Test fun theOpSchemaDescribesArithmetic() {
    val catalog = Tools.registry.single { it.name == "get_catalog" }.call(JsonObject(emptyMap())).toString()
    assertTrue("PropValue.<Lit|Bind|Action|Token|Conditional|Template|ListOf|Arith|IntDp>" in catalog, catalog)
    assertTrue("Arith = {op, left, right} is Int arithmetic" in catalog, catalog)
    assertTrue("/ and % only by a non-zero Lit i" in catalog, catalog)
    assertTrue("IntDp = {value, form?} is an Int used as a Dp" in catalog, catalog)
    assertTrue("Arith/IntDp" in Tools.registry.single { it.name == "get_document" }.descriptor.toString())
  }

  @Test fun findUsagesLooksAtEveryOperand() {
    val gap = PropValue.Token("C.Gap", lit(8), "app.components.C")
    val doc = UiDocument(
      "home",
      DocNode.Widget(
        Handle(1), "Column",
        children = listOf(
          DocNode.Widget(Handle(2), "StyledText", props = mapOf("fontSize" to PropValue.Arith("+", PropValue.Arith("*", PropValue.Bind("size"), lit(2)), gap))),
          DocNode.Widget(Handle(3), "Spacer", props = mapOf("height" to PropValue.IntDp(PropValue.Bind("size"), "Dp"))),
        ),
      ),
      Contract(), 0, 4,
    )
    assertEquals(
      listOf("home: StyledText#2.fontSize binds field 'size'", "home: Spacer#3.height binds field 'size'"),
      Tools.usagesIn("home", doc, "size"),
    )
    assertEquals(listOf("home: StyledText#2.fontSize uses token 'C.Gap' (app.components.C.Gap)"), Tools.usagesIn("home", doc, "C.Gap"))
  }
}
