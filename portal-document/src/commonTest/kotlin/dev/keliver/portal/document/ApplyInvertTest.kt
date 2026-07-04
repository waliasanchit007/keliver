package dev.keliver.portal.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ApplyInvertTest {
  private fun doc(): UiDocument {
    val root = DocNode.Widget(Handle(1), "Column", children = listOf(
      DocNode.Widget(Handle(2), "StyledText", mapOf("text" to lit("hi"))),
      DocNode.Widget(Handle(3), "Button", mapOf("text" to lit("Buy"))),
    ))
    return UiDocument(screen = "main", root = root, contract = Contract(), version = 0, nextHandle = 10)
  }

  @Test fun insertAfterSiblingAndInvert() {
    val d = doc()
    val spacer = DocNode.Widget(Handle(0), "Spacer") // handle 0 = allocate server-side
    val r = d.apply(DocOp.InsertNode(Handle(1), after = Handle(2), node = spacer))
    val types = (r.doc.root as DocNode.Widget).children.map { (it as DocNode.Widget).type }
    assertEquals(listOf("StyledText", "Spacer", "Button"), types)
    val allocated = (r.doc.root as DocNode.Widget).children[1].handle
    assertEquals(Handle(10), allocated)
    val back = r.doc.apply(r.inverse!!)
    assertEquals(d.root, back.doc.root)
  }

  @Test fun deleteInvertRestoresPositionAndSubtree() {
    val d = doc()
    val r = d.apply(DocOp.DeleteNode(Handle(2)))
    assertEquals(1, (r.doc.root as DocNode.Widget).children.size)
    val back = r.doc.apply(r.inverse!!)
    assertEquals(d.root, back.doc.root)
  }

  @Test fun moveToFrontAndInvert() {
    val d = doc()
    val r = d.apply(DocOp.MoveNode(Handle(3), newParent = Handle(1), after = null))
    assertEquals(listOf(Handle(3), Handle(2)), (r.doc.root as DocNode.Widget).children.map { it.handle })
    val back = r.doc.apply(r.inverse!!)
    assertEquals(d.root, back.doc.root)
  }

  @Test fun setPropInvertRestoresOldOrRemoves() {
    val d = doc()
    val r1 = d.apply(DocOp.SetProp(Handle(2), "text", lit("yo")))
    val back1 = r1.doc.apply(r1.inverse!!)
    assertEquals(d.root, back1.doc.root)
    val r2 = d.apply(DocOp.SetProp(Handle(2), "fontSize", lit(22)))
    assertIs<DocOp.RemoveProp>(r2.inverse)
  }

  @Test fun errorsAreExplicit() {
    val d = doc()
    val err = d.tryApply(DocOp.DeleteNode(Handle(99)))
    assertNull(err.result)
    assertEquals("unknown handle 99", err.error)
    val cyc = d.tryApply(DocOp.MoveNode(Handle(1), newParent = Handle(2), after = null))
    assertEquals("cannot move a node into its own subtree", cyc.error)
  }

  @Test fun batchIsAtomicWithSingleInverse() {
    val d = doc()
    val bad = d.applyBatch(listOf(
      DocOp.SetProp(Handle(2), "text", lit("A")),
      DocOp.DeleteNode(Handle(99)),
    ))
    assertNull(bad.result)
    val ok = d.applyBatch(listOf(DocOp.SetProp(Handle(2), "text", lit("A")), DocOp.DeleteNode(Handle(3))))
    val undone = ok.result!!.doc.replay(ok.result!!.inverseBatch)
    assertEquals(d.root, undone.root)
  }
}
