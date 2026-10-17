package dev.keliver.portal.document

import dev.keliver.portal.Token
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.serializeTree
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K2: design-token references through the document, the wire and the V1 tree. */
class DesignTokenTest {
  private val ink = PropValue.Token("C.Ink", lit(-14737633), "app.components.C")
  private val logo = PropValue.Token("Assets.Logo", lit("https://example.com/logo.png"), "app.components.Assets")

  private fun doc(vararg children: DocNode): UiDocument =
    UiDocument("t", DocNode.Widget(Handle(1), "Column", children = children.toList()), Contract(), 0, 10)

  @Test fun tokenRoundTripsOnTheDocumentWire() {
    val node: DocNode = DocNode.Widget(Handle(2), "StyledText", props = mapOf("colorArgb" to ink, "text" to lit("x")))
    val json = DocJson.encodeToString(node)
    assertTrue("\"ref\":\"C.Ink\"" in json, json)
    assertEquals(node, DocJson.decodeFromString<DocNode>(json))
    val batch = OpBatch(1, OpEnvelope("s", 0), listOf(DocOp.SetProp(Handle(2), "colorArgb", ink)))
    assertEquals(batch, DocJson.decodeFromString<OpBatch>(DocJson.encodeToString(batch)))
  }

  @Test fun theTreeCarriesTheResolvedValueAndTheReference() {
    val d = doc(DocNode.Widget(Handle(2), "AsyncImage", props = mapOf("url" to logo), modifiers = mapOf("Background.colorArgb" to ink)))
    val tree = d.toWidgetTree()
    val image = tree.children.single()
    assertEquals(Token("Assets.Logo", "https://example.com/logo.png", "app.components.Assets"), image.props["url"])
    assertEquals(Token("C.Ink", -14737633, "app.components.C"), image.props["mod.Background.colorArgb"])
    // Editor -> server (duplicate / palette) lifts it back unchanged.
    val lifted = image.toDocNode()
    assertEquals(logo, lifted.props["url"])
    assertEquals(ink, lifted.modifiers["Background.colorArgb"])
  }

  @Test fun theDeviceTreeKeepsThePlainLiteralForOlderReaders() {
    val tree = doc(DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to logo, "colorArgb" to ink))).toWidgetTree()
    val json = serializeTree(tree)
    // The resolved literal rides under its usual tag, beside the reference.
    assertTrue("{\"i\":-14737633,\"token\":\"C.Ink\",\"tokenImport\":\"app.components.C\"}" in json, json)
    assertEquals(tree, deserializeTree(json))
  }

  @Test fun theExporterWritesTheReferenceAndImportsItsObject() {
    val tree = doc(DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to logo, "colorArgb" to ink))).toWidgetTree()
    val src = exportKotlin(tree)
    assertTrue("text = Assets.Logo," in src, src)
    assertTrue("colorArgb = C.Ink," in src, src)
    assertTrue("import app.components.Assets\n" in src, src)
    assertTrue("import app.components.C\n" in src, src)
  }

  @Test fun aTokenThatDoesNotFitThePropIsRefused() {
    val d = doc(DocNode.Widget(Handle(2), "StyledText", props = mapOf("text" to lit("x"))), DocNode.Widget(Handle(3), "Row"))
    // An Int token on a Text prop, on an enum (Constraint) prop, and on a Dp modifier argument.
    assertNotNull(d.tryApply(DocOp.SetProp(Handle(2), "text", ink)).error)
    assertNotNull(d.tryApply(DocOp.SetProp(Handle(3), "width", ink)).error)
    val gap = PropValue.Token("C.Gap", lit(8.0), "app.components.C")
    assertNotNull(d.tryApply(DocOp.SetModifier(Handle(2), "Width.width", gap)).error)
    assertNotNull(
      d.tryApply(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "StyledText", props = mapOf("text" to ink)))).error,
    )
    // Where it fits, it is accepted; replacing it with a literal (detach) is a plain SetProp.
    val set = d.apply(DocOp.SetProp(Handle(2), "colorArgb", ink))
    assertEquals(ink, (set.doc.find(Handle(2)) as DocNode.Widget).props["colorArgb"])
    val detached = set.doc.apply(DocOp.SetProp(Handle(2), "colorArgb", lit(-14737633)))
    assertEquals(lit(-14737633), (detached.doc.find(Handle(2)) as DocNode.Widget).props["colorArgb"])
    assertEquals(DocOp.SetProp(Handle(2), "colorArgb", ink), detached.inverse)
    assertNull(d.tryApply(DocOp.SetModifier(Handle(2), "Background.colorArgb", ink)).error)
    // A component instance's props are the server's to check (it knows signatures).
    assertNull(d.tryApply(DocOp.InsertNode(Handle(1), null, DocNode.Widget(Handle(0), "Badge2", props = mapOf("label" to ink)))).error)
  }

  @Test fun aLongHasNoLiteralTag() {
    // A Long token (`const val Big = 0xFFFFFFFF`) fits no prop; lit() refuses it rather than writing a String.
    kotlin.test.assertFailsWith<IllegalArgumentException> { lit(4294967295L) }
  }
}
