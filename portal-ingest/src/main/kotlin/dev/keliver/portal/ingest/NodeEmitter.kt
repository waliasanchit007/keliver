package dev.keliver.portal.ingest

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Renders a single DocNode to canonical Kotlin using the SAME formatter as the
 * exporter (so surgical write-back stays byte-identical to a fresh export).
 * Trick: export a one-node throwaway screen, then lift the call PSI out of it.
 */
object NodeEmitter {
  /** The whole call expression (recursive, WITH children) — for inserting a new node. */
  fun callExpression(node: DocNode): KtCallExpression = liftCall(node)

  /** Just the `(...)` argument list of a node's call, children stripped — for prop/modifier edits. */
  fun argumentList(node: DocNode.Widget) = liftCall(node.copy(children = emptyList())).valueArgumentList

  private fun liftCall(node: DocNode): KtCallExpression {
    val throwaway = UiDocument("_", node, Contract(), version = 0, nextHandle = 0)
    val exported = exportKotlin(throwaway.toWidgetTree(), functionName = "Tmp")
    val file = PsiEnv.parse("Tmp.kt", exported)
    val fn = file.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "Tmp" }
    val stmt = (fn.bodyExpression as KtBlockExpression).statements.first()
    return stmt as KtCallExpression
  }
}
