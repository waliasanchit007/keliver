package dev.keliver.portal.document

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class WireTest {
  @Test fun opBatchRoundTripsPolymorphically() {
    val batch = OpBatch(
      baseVersion = 7,
      envelope = OpEnvelope(session = "agent:claude", atMillis = 123, label = "add button"),
      ops = listOf(
        DocOp.InsertNode(Handle(1), after = null, node = DocNode.Widget(Handle(9), "Button", mapOf("text" to lit("Buy")))),
        DocOp.SetProp(Handle(9), "text", PropValue.Bind("ctaLabel")),
        DocOp.ReplaceRaw(Handle(4), "if (b.x) { }"),
      ),
    )
    val json = DocJson.encodeToString(batch)
    assertEquals(batch, DocJson.decodeFromString<OpBatch>(json))
  }

  @Test fun actionArgSerializesAndOldJsonStillDecodes() {
    // Phase 2: single-arg actions ride the wire; pre-arg documents must still decode.
    val node: DocNode = DocNode.Widget(
      Handle(1), "Clickable",
      props = mapOf("onClick" to PropValue.Action("openNote", arg = "note.id")),
    )
    val json = DocJson.encodeToString(node)
    assertEquals(node, DocJson.decodeFromString<DocNode>(json))
    val old = json.replace(Regex(",?\"arg\":\"note.id\""), "")
    val decoded = DocJson.decodeFromString<DocNode>(old) as DocNode.Widget
    assertEquals(PropValue.Action("openNote"), decoded.props["onClick"])
  }

  @Test fun contractActionParamsDefaultKeepsOldJsonDecodable() {
    val c = Contract(fields = mapOf("draft" to "String"), actions = listOf("onDraftChange"), actionParams = mapOf("onDraftChange" to "String"))
    assertEquals(c, DocJson.decodeFromString<Contract>(DocJson.encodeToString(c)))
    val old = """{"fields":{"draft":"String"},"actions":["onDraftChange"]}"""
    assertEquals(Contract(mapOf("draft" to "String"), listOf("onDraftChange")), DocJson.decodeFromString<Contract>(old))
  }

  @Test fun stringListLitRoundTripsAndOldJsonStillDecodes() {
    // P4 STRING_LIST ("ls"): options lists (DropdownMenu/SegmentedButtonRow).
    val node: DocNode = DocNode.Widget(
      Handle(1), "SegmentedButtonRow",
      props = mapOf("options" to PropValue.Lit("ls", ls = listOf("Day", "Week", "Month"))),
    )
    val json = DocJson.encodeToString(node)
    assertEquals(node, DocJson.decodeFromString<DocNode>(json))
    val old = """{"kind":"dev.keliver.portal.document.DocNode.Widget","handle":1,"type":"Button","props":{"text":{"kind":"dev.keliver.portal.document.PropValue.Lit","tag":"s","s":"Buy"}}}"""
    assertEquals("Buy", ((DocJson.decodeFromString<DocNode>(old) as DocNode.Widget).props["text"] as PropValue.Lit).s)
  }

  @Test fun docNodeRoundTrips() {
    val doc: DocNode = DocNode.Widget(
      Handle(1), "Column",
      children = listOf(DocNode.RawCode(Handle(2), "if (b.loading) { }", kindHint = "condition")),
    )
    assertEquals(doc, DocJson.decodeFromString<DocNode>(DocJson.encodeToString(doc)))
  }
}
