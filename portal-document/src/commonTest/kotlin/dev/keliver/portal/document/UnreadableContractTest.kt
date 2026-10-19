package dev.keliver.portal.document

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K4 (last review): a screen whose Bindings types ingest cannot read — the
 * interface is not in the file, or extends a type declared elsewhere — says
 * so in its contract ([Contract.unlistedFieldsUnknown]), and the document
 * refuses, when the op is made, a template reading a field it cannot type:
 * the file would not read back as the document, and a refused write-back
 * blocks every later edit of the screen.
 */
class UnreadableContractTest {
  private val elsewhere = "`SBindings` extends `ExternalBase`, declared in another file"
  private val open = Contract(fields = mapOf("name" to "String", "x" to "<unknown>"), unlistedFieldsUnknown = elsewhere)

  private fun doc(contract: Contract, vararg children: DocNode): UiDocument =
    UiDocument("t", DocNode.Widget(Handle(1), "Column", children = children.toList()), contract, 0, 10)

  private fun text(h: Long) = DocNode.Widget(Handle(h), "StyledText", props = mapOf("text" to lit("x")))
  private fun hi(field: String) = PropValue.Template(listOf(lit("Hi "), PropValue.Bind(field)))

  private fun refused(d: UiDocument, op: DocOp): String = assertNotNull(d.tryApply(op).error, "expected a refusal: $op")

  @Test fun aTemplateReadingAFieldTheContractCannotTypeIsRefusedWhereverItIsPlaced() {
    val d = doc(open, text(2))
    // Set.
    val msg = refused(d, DocOp.SetProp(Handle(2), "text", hi("title")))
    assertTrue("`title`" in msg && "not readable here" in msg && elsewhere in msg, msg)
    assertTrue("bind it directly or make this change in code" in msg, msg)
    // A brand-new field is no different: it may be inherited too (case E).
    refused(d, DocOp.SetProp(Handle(2), "text", hi("fresh")))
    // As a conditional's branch.
    refused(d, DocOp.SetProp(Handle(2), "text", PropValue.Conditional("on", whenTrue = hi("title"), whenFalse = lit("-"))))
    // Inserted.
    refused(d, DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to hi("title")))))
    refused(d, DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Column", children = listOf(DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to hi("title")))))))
    // A field declared without a type is unknown whatever the rest of the interface is.
    assertTrue("`x`" in refused(d, DocOp.SetProp(Handle(2), "text", hi("x"))))
    assertTrue("`x`" in refused(doc(Contract(fields = mapOf("x" to "<unknown>")), text(2)), DocOp.SetProp(Handle(2), "text", hi("x"))))
  }

  @Test fun whatTheContractCanTypeIsStillAllowed() {
    val d = doc(open, text(2))
    // A field declared in the file has its type, inherited or not.
    assertNull(d.tryApply(DocOp.SetProp(Handle(2), "text", hi("name"))).error)
    // A plain binding prints nothing: it is written whatever the field's type.
    assertNull(d.tryApply(DocOp.SetProp(Handle(2), "text", PropValue.Bind("title"))).error)
    // An item field is typed by its item, not the screen's contract.
    val repeat = DocNode.Widget(Handle(3), "Repeat", props = mapOf("items" to lit("rows"), "item" to lit("row")), children = listOf(text(4)))
    assertNull(doc(open, repeat).tryApply(DocOp.SetProp(Handle(4), "text", hi("row.title"))).error)
    // A list element holds the value itself (nothing printed): the compile types it.
    val box = DocNode.Widget(Handle(5), "StyledBox")
    assertNull(doc(open, box).tryApply(DocOp.SetProp(Handle(5), "gradientColorsArgb", PropValue.ListOf("li", listOf(PropValue.Bind("title"))))).error)
    // An interface read whole (the default): a field it does not list is new, and write-back declares it (case D).
    assertNull(doc(Contract(fields = mapOf("name" to "String")), text(2)).tryApply(DocOp.SetProp(Handle(2), "text", hi("fresh"))).error)
  }

  @Test fun aContractEditCannotClaimTheFilesTypesAreReadable() {
    val d = doc(open, text(2))
    val edited = d.apply(DocOp.ContractEdit(Contract(fields = mapOf("name" to "String")))).doc
    assertEquals(elsewhere, edited.contract.unlistedFieldsUnknown, "the file decides it, not an op")
    refused(edited, DocOp.SetProp(Handle(2), "text", hi("title")))
  }

  @Test fun anUndoThatWouldReinsertSuchATemplateIsRefused() {
    // Typed when it was placed; the contract no longer types it when a delete is undone.
    val typed = doc(open.copy(fields = open.fields + ("title" to "String")), text(2))
    val placed = typed.apply(DocOp.SetProp(Handle(2), "text", hi("title"))).doc
    val deleted = placed.apply(DocOp.DeleteNode(Handle(2)))
    val narrowed = deleted.doc.apply(DocOp.ContractEdit(open)).doc
    val undo = assertNotNull(deleted.inverse)
    assertTrue("`title`" in refused(narrowed, undo))
    assertNull(deleted.doc.tryApply(undo).error, "with the field typed, the undo applies")
  }

  @Test fun theSignalRidesTheWireAndOldJsonDecodesAsReadable() {
    assertEquals(open, DocJson.decodeFromString<Contract>(DocJson.encodeToString(open)))
    // The default is not written, so the wire of a readable screen is unchanged.
    val readable = Contract(fields = mapOf("name" to "String"), actions = listOf("go"))
    assertTrue("unlistedFieldsUnknown" !in DocJson.encodeToString(readable), DocJson.encodeToString(readable))
    val old = """{"fields":{"name":"String"},"actions":["go"],"actionParams":{}}"""
    assertNull(DocJson.decodeFromString<Contract>(old).unlistedFieldsUnknown)
    assertEquals(readable, DocJson.decodeFromString<Contract>(old))
    // The whole document too.
    val d = doc(open, text(2))
    assertEquals(d, DocJson.decodeFromString<UiDocument>(DocJson.encodeToString(d)))
    // The three-argument constructor older callers use is kept.
    assertEquals(Contract(mapOf("a" to "Int"), listOf("go"), mapOf("go" to "String")), Contract(fields = mapOf("a" to "Int"), actions = listOf("go"), actionParams = mapOf("go" to "String"), unlistedFieldsUnknown = null))
  }
}
