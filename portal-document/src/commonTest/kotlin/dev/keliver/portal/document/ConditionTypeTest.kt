package dev.keliver.portal.document

import dev.keliver.portal.Cond
import dev.keliver.portal.CondJoin
import dev.keliver.portal.CondTest
import dev.keliver.portal.Conditional
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.condOf
import dev.keliver.portal.condOfTerms
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.serializeTree
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K6 review: a term's test fits its field's DECLARED type (a Boolean test a
 * `Boolean`, an emptiness test a `String` or a collection) — K3 single terms
 * too; an equality's operand keeps its kind where the document cannot read
 * the field's type; a Condition's join is set like a value's; and an older
 * device reader never decides an extended condition by its first term.
 */
class ConditionTypeTest {
  private val contract = Contract(fields = mapOf("count" to "Int", "note" to "String", "open" to "Boolean", "kind" to "String", "tags" to "List<String>", "maybe" to "String?"))

  private fun doc(vararg children: DocNode, c: Contract = contract): UiDocument =
    UiDocument("t", DocNode.Widget(Handle(1), "Column", children = children.toList()), c, 0, 20)

  private fun text(h: Long, v: PropValue = lit("x")) = DocNode.Widget(Handle(h), "StyledText", props = mapOf("text" to v))

  private fun cond(h: Long, c: Cond) = DocNode.Widget(Handle(h), "Condition", conditionNodeProps(c), children = listOf(text(h + 1)))

  @Test fun aBooleanTestReadsOnlyAFieldDeclaredBoolean() {
    val d = doc(text(2), cond(3, condOfTerms(listOf(Cond("kind", CondTest.Eq, negate = true, operand = "cash"), Cond("open")))), cond(5, Cond("open")))
    fun refused(op: DocOp) = assertNotNull(d.tryApply(op).error, op.toString())
    fun accepted(op: DocOp) = assertNull(d.tryApply(op).error, op.toString())
    // A further term re-pointed at an Int field would write `b.kind != "cash" && b.count`.
    refused(DocOp.SetProp(Handle(3), "field.1", lit("count")))
    accepted(DocOp.SetProp(Handle(3), "field.1", lit("open")))
    // K3 single terms too.
    refused(DocOp.SetProp(Handle(5), "field", lit("count")))
    refused(DocOp.SetProp(Handle(5), "field", lit("maybe")))
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("count", whenTrue = lit("a"), whenFalse = lit("b"))))
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("open", whenTrue = lit("a"), whenFalse = lit("b"), more = listOf(CondTerm("note")))))
    refused(DocOp.InsertNode(Handle(1), null, cond(0, Cond("note")).copy(handle = Handle(0), children = emptyList())))
    // An equality with a Boolean is a Boolean test.
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("count", "eq", operand = lit(true), whenTrue = lit("a"), whenFalse = lit("b"))))
    accepted(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("open", whenTrue = lit("a"), whenFalse = lit("b"))))
    // A field the contract does not declare is the contract's to derive.
    accepted(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("fresh", whenTrue = lit("a"), whenFalse = lit("b"))))
  }

  @Test fun anEmptinessTestReadsOnlyAStringOrACollection() {
    val d = doc(text(2), cond(3, condOfTerms(listOf(Cond("note", CondTest.Blank, negate = true), Cond("open")))))
    fun refused(op: DocOp) = assertNotNull(d.tryApply(op).error, op.toString())
    fun accepted(op: DocOp) = assertNull(d.tryApply(op).error, op.toString())
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("count", "empty", whenTrue = lit("a"), whenFalse = lit("b"))))
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("open", "blank", whenTrue = lit("a"), whenFalse = lit("b"))))
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("maybe", "empty", whenTrue = lit("a"), whenFalse = lit("b"))))
    refused(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("tags", "blank", whenTrue = lit("a"), whenFalse = lit("b"))))
    accepted(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("tags", "empty", whenTrue = lit("a"), whenFalse = lit("b"))))
    accepted(DocOp.SetProp(Handle(2), "text", PropValue.Conditional("note", "blank", whenTrue = lit("a"), whenFalse = lit("b"))))
    // Turning `b.note.isNotBlank()` into a Boolean test — by removing its test or setting it to "" — would write `!b.note`.
    refused(DocOp.RemoveProp(Handle(3), "test"))
    refused(DocOp.SetProp(Handle(3), "test", lit("")))
    accepted(DocOp.SetProp(Handle(3), "test", lit("empty")))
    refused(DocOp.SetProp(Handle(3), "field", lit("count")))
  }

  @Test fun anOperandKeepsItsKindWhereTheFieldsTypeIsNotReadable() {
    // The screen's interface extends one declared elsewhere: an unlisted field's type is unknown.
    val unreadable = Contract(unlistedFieldsUnknown = "it extends a type declared in another file")
    val eq = PropValue.Conditional("kind", "eq", operand = lit("card"), whenTrue = lit("a"), whenFalse = lit("b"))
    val repeat = DocNode.Widget(
      Handle(4), "Repeat", mapOf("items" to lit("rows"), "item" to lit("row")),
      children = listOf(text(5, PropValue.Conditional("row.kind", "eq", operand = lit("a"), whenTrue = lit("A"), whenFalse = lit("B")))),
    )
    val d = doc(text(2, eq), cond(6, Cond("kind", CondTest.Eq, operand = "card")), repeat, c = unreadable)
    fun refused(op: DocOp) = assertNotNull(d.tryApply(op).error, op.toString())
    fun accepted(op: DocOp) = assertNull(d.tryApply(op).error, op.toString())
    refused(DocOp.SetProp(Handle(2), "text", eq.copy(operand = lit(1))))
    accepted(DocOp.SetProp(Handle(2), "text", eq.copy(operand = lit("cash"))))
    refused(DocOp.SetProp(Handle(6), "operand", lit(1)))
    accepted(DocOp.SetProp(Handle(6), "operand", lit("cash")))
    // An item field's type is never in the contract.
    val item = (repeat.children.single() as DocNode.Widget).props["text"] as PropValue.Conditional
    refused(DocOp.SetProp(Handle(5), "text", item.copy(operand = lit(1))))
    accepted(DocOp.SetProp(Handle(5), "text", item.copy(operand = lit("b"))))
    // Where the document reads the type, the declared check decides; a field it derives may change kind.
    val readable = doc(text(2, eq.copy(field = "fresh")))
    assertNull(readable.tryApply(DocOp.SetProp(Handle(2), "text", eq.copy(field = "fresh", operand = lit(1)))).error)
  }

  @Test fun aConditionsJoinIsSetLikeAValuesAndUndone() {
    val d = doc(cond(3, condOfTerms(listOf(Cond("open"), Cond("note", CondTest.Empty)))), cond(5, Cond("open")))
    val or = d.apply(DocOp.SetProp(Handle(3), "join", lit("or")))
    assertEquals(CondJoin.Or, (or.doc.find(Handle(3)) as DocNode.Widget).condition()!!.join)
    assertEquals(d.root, or.doc.apply(assertNotNull(or.inverse)).doc.root)
    val and = or.doc.apply(DocOp.RemoveProp(Handle(3), "join"))
    assertEquals(CondJoin.And, (and.doc.find(Handle(3)) as DocNode.Widget).condition()!!.join)
    assertEquals(or.doc.root, and.doc.apply(assertNotNull(and.inverse)).doc.root)
    // Only `"or"` is a join's value, and one term has no join.
    assertNotNull(d.tryApply(DocOp.SetProp(Handle(3), "join", lit("and"))).error)
    assertNotNull(d.tryApply(DocOp.SetProp(Handle(3), "join", lit(""))).error)
    assertNotNull(d.tryApply(DocOp.SetProp(Handle(5), "join", lit("or"))).error)
  }

  @Test fun anOlderDeviceReaderNeverDecidesAnExtendedConditionByItsFirstTerm() {
    val extended = condOfTerms(listOf(Cond("kind", CondTest.Eq, negate = true, operand = "cash"), Cond("open")))
    val tree = WidgetNode(
      "Column",
      children = listOf(
        WidgetNode("StyledText", mapOf("text" to Conditional(extended, "A", "B"), "maxLines" to Conditional(Cond("open"), 1, 2))),
        WidgetNode("Condition", dev.keliver.portal.conditionProps(extended) { it }, listOf(WidgetNode("StyledText", mapOf("text" to "then")))),
      ),
    )
    val json = serializeTree(tree)
    assertEquals(tree.copy(id = 0, children = tree.children.map { it.copy(id = 0) }).let(::ids0), ids0(deserializeTree(json)))
    val o = Json.parseToJsonElement(json).jsonObject
    val kids = o.getValue("children").jsonArray.map { it.jsonObject }
    // An extended conditional VALUE is not under `cond` (a K3 reader decodes its true branch); a K3 one still is.
    val textValue = kids[0].getValue("props").jsonObject.getValue("text").jsonObject
    assertFalse("cond" in textValue, textValue.toString())
    assertEquals("A", (textValue["s"] as kotlinx.serialization.json.JsonPrimitive).content)
    assertTrue("cond" in kids[0].getValue("props").jsonObject.getValue("maxLines").jsonObject)
    // An extended Condition NODE has no `field`/`test` a K3 reader would decide by: it renders its then-branch.
    val condProps = kids[1].getValue("props").jsonObject
    assertFalse("field" in condProps || "test" in condProps, condProps.toString())
    assertNull(condOf(WidgetNode("Condition", condProps.mapValues { null })))
    assertEquals(extended, condOf(deserializeTree(json).children[1]))
    assertTrue(kids[1].values.isNotEmpty() && kids[1] is JsonObject)
  }

  private fun ids0(n: WidgetNode): WidgetNode = n.copy(id = 0, children = n.children.map(::ids0))
}
