import dev.keliver.portal.ComponentSpec
import dev.keliver.portal.MapComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.PropSpec
import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.OpEnvelope
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** K1 review (N-2 + component modifiers): the client-op gate is the same for dryRun and submit. */
class DocumentServiceGateTest {
  private fun d(v: Double) = PropValue.Lit("d", d = v)

  private val initial = UiDocument(
    "s",
    DocNode.Widget(
      Handle(1), "Column",
      children = listOf(
        DocNode.Widget(Handle(2), "Row", children = listOf(
          DocNode.Widget(Handle(3), "StyledText", mapOf("text" to PropValue.Lit("s", s = "a")), modifiers = mapOf("Flex.value" to d(1.0))),
          DocNode.Widget(Handle(4), "Plain", mapOf("title" to PropValue.Lit("s", s = "p"))),
          DocNode.Widget(Handle(5), "Weighted", mapOf("title" to PropValue.Lit("s", s = "w"))),
        )),
        DocNode.Widget(Handle(6), "StyledBox"),
      ),
    ),
    Contract(), 0, 7,
  )

  private fun component(name: String, withModifier: Boolean) = ComponentSpec(
    name = name,
    props = listOf(PropSpec("title", PropKind.Text, "Title")),
    events = emptyList(),
    paramTypes = mapOf("title" to "String") + (if (withModifier) mapOf("modifier" to "Modifier") else emptyMap()),
  )

  private fun service(): DocumentService {
    val dir = Files.createTempDirectory("doc-gate").toFile().apply { deleteOnExit() }
    val registry = MapComponentRegistry(listOf(component("Plain", false), component("Weighted", true)))
    return DocumentService("s", initial, onProjected = {}, kotlinFile = dir.resolve("S.kt"), components = { registry })
  }

  private fun batch(svc: DocumentService, vararg ops: DocOp) =
    OpBatch(svc.doc.version, OpEnvelope("t", 0L), ops.toList())

  @Test fun internalOpsAreRefusedByDryRunAndSubmit() {
    val stripping = initial.apply(DocOp.MoveNode(Handle(3), Handle(6), null)).inverse!! // MoveBack
    val reinsert = initial.apply(DocOp.DeleteNode(Handle(6))).inverse!! // InsertExisting
    for (op in listOf(stripping, reinsert)) {
      val svc = service()
      val dry = svc.dryRun(batch(svc, op))
      val sub = svc.submit(batch(svc, op))
      assertFalse(dry.ok, "dryRun accepted ${op::class.simpleName}")
      assertFalse(sub.ok, "submit accepted ${op::class.simpleName}")
      assertEquals("internal op not accepted", dry.error)
      assertEquals(dry.error, sub.error)
    }
  }

  @Test fun componentInstancesTakeModifiersOnlyWithAModifierParameter() {
    val svc = service()
    val refused = DocOp.SetModifier(Handle(4), "Flex.value", d(1.0))
    assertFalse(svc.dryRun(batch(svc, refused)).ok)
    val sub = svc.submit(batch(svc, refused))
    assertFalse(sub.ok)
    assertTrue("modifier: Modifier" in sub.error!!, sub.error)
    // An inserted instance carrying modifiers is refused too.
    val insert = DocOp.InsertNode(Handle(2), null, DocNode.Widget(Handle(0), "Plain", modifiers = mapOf("Flex.value" to d(1.0))))
    assertFalse(svc.dryRun(batch(svc, insert)).ok)
    assertFalse(svc.submit(batch(svc, insert)).ok)
    // A component that declares `modifier: Modifier` accepts it.
    val ok = DocOp.SetModifier(Handle(5), "Flex.value", d(1.0))
    assertTrue(svc.dryRun(batch(svc, ok)).ok)
    assertTrue(svc.submit(batch(svc, ok)).ok)
  }
}
