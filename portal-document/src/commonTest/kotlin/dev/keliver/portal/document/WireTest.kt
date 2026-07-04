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

  @Test fun docNodeRoundTrips() {
    val doc: DocNode = DocNode.Widget(
      Handle(1), "Column",
      children = listOf(DocNode.RawCode(Handle(2), "if (b.loading) { }", kindHint = "condition")),
    )
    assertEquals(doc, DocJson.decodeFromString<DocNode>(DocJson.encodeToString(doc)))
  }
}
