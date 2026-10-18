package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.DocOp
import dev.keliver.portal.document.UiDocument
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K3 fourth review, S5: a move can write code that reads back but does not
 * compile — a `val` moved after its use, or into another block. A code node
 * that declares something never moves to another block, and nothing that
 * mentions the declared name moves out of where it can see it.
 */
class DeclarationScopeTest {
  private val src = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        val label = "hi"
        StyledText(text = "a")
        Legacy(label)
        Row {
          Legacy(label.length)
        }
        Legacy(labelled)
      }
    }

    interface HomeScreenBindings
  """.trimIndent() + "\n"

  private val d: UiDocument = Recognizer.recognize("app/screens/HomeScreen.kt", src)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }
  private fun kids() = (d.root as DocNode.Widget).children
  private val decl get() = kids()[0]
  private val row get() = kids()[3] as DocNode.Widget

  private fun after(op: DocOp) = d.apply(op).doc

  @Test fun theDeclarationMovedAfterItsUseIsRefused() {
    val why = assertNotNull(DeclarationScope.problem(d, after(DocOp.MoveNode(decl.handle, d.root.handle, kids()[2].handle))))
    assertTrue("label" in why && "Legacy(label)" in why, why)
  }

  @Test fun theDeclarationMovedIntoAnotherBlockIsRefused() {
    val why = assertNotNull(DeclarationScope.problem(d, after(DocOp.MoveNode(decl.handle, row.handle, null))))
    assertTrue("another block" in why, why)
  }

  @Test fun aUseMovedAboveTheDeclarationIsRefused() {
    assertNotNull(DeclarationScope.problem(d, after(DocOp.MoveNode(kids()[2].handle, d.root.handle, null))))
    // A widget holding a use, too.
    assertNotNull(DeclarationScope.problem(d, after(DocOp.MoveNode(row.handle, d.root.handle, null))))
  }

  @Test fun movesThatKeepEveryUseInScopeAreFine() {
    // An unrelated node above it; a use into a later block (still in scope); the declaration
    // past a node that only mentions a longer name.
    assertNull(DeclarationScope.problem(d, after(DocOp.MoveNode(kids()[1].handle, d.root.handle, null))))
    assertNull(DeclarationScope.problem(d, after(DocOp.MoveNode(kids()[2].handle, row.handle, row.children[0].handle))))
    val noUses = d.apply(DocOp.DeleteNode(kids()[2].handle)).doc.let { it.apply(DocOp.DeleteNode(row.handle)).doc }
    val k = (noUses.root as DocNode.Widget).children
    assertNull(DeclarationScope.problem(noUses, noUses.apply(DocOp.MoveNode(k[0].handle, noUses.root.handle, k.last().handle)).doc))
  }

  // A forEach's item is declared by the forEach: what uses it stays inside.
  private val items = """
    package app.screens

    import androidx.compose.runtime.Composable
    import dev.keliver.layout.compose.Column
    import dev.keliver.layout.compose.Row
    import dev.keliver.material.compose.StyledText

    @Composable
    fun HomeScreen(b: HomeScreenBindings) {
      Column {
        b.notes.forEach { note ->
          Row {
            StyledText(text = note.title)
          }
          StyledText(text = "plain")
          Legacy(note)
        }
      }
    }

    interface HomeScreenBindings {
      val notes: List<Note>
    }

    interface Note {
      val title: String
    }
  """.trimIndent() + "\n"

  @Test fun whatUsesAForEachItemDoesNotMoveOutOfTheForEach() {
    val d = Recognizer.recognize("app/screens/HomeScreen.kt", items)!!.let { UiDocument("home", it.root, it.contract, 0, 1000) }
    val each = (d.root as DocNode.Widget).children[0] as DocNode.Widget
    val row = each.children[0] as DocNode.Widget
    // A bound widget (inside a Row) out to the top level.
    val why = assertNotNull(DeclarationScope.problem(d, d.apply(DocOp.MoveNode(row.children[0].handle, d.root.handle, null)).doc))
    assertTrue("note" in why && "forEach" in why, why)
    // The Row holding it, and code using the item, too.
    assertNotNull(DeclarationScope.problem(d, d.apply(DocOp.MoveNode(row.handle, d.root.handle, null)).doc))
    assertNotNull(DeclarationScope.problem(d, d.apply(DocOp.MoveNode(each.children[2].handle, d.root.handle, null)).doc))
    // What does not use it moves freely; what does moves within the forEach.
    assertNull(DeclarationScope.problem(d, d.apply(DocOp.MoveNode(each.children[1].handle, d.root.handle, null)).doc))
    assertNull(DeclarationScope.problem(d, d.apply(DocOp.MoveNode(row.children[0].handle, each.handle, null)).doc))
  }
}
