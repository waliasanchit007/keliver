package dev.keliver.portal.ingest

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Renders a single DocNode to canonical Kotlin using the SAME formatter as the
 * exporter (so surgical write-back stays byte-identical to a fresh export).
 * Trick: export a one-node throwaway screen, then lift the statement PSI out.
 */
object NodeEmitter {
  /** The whole statement (call, or `if`/`forEach` for logic nodes) — for inserting a new node. */
  fun statement(node: DocNode): KtExpression = liftStatement(node)

  /** Just the `(...)` argument list of a widget call, children stripped — for prop/modifier edits. */
  fun argumentList(node: DocNode.Widget) = (liftStatement(node.copy(children = emptyList())) as KtCallExpression).valueArgumentList

  private fun liftStatement(node: DocNode): KtExpression {
    val throwaway = UiDocument("_", node, Contract(), version = 0, nextHandle = 0)
    val exported = exportKotlin(throwaway.toWidgetTree(), functionName = "Tmp")
    val file = PsiEnv.parse("Tmp.kt", exported)
    val fn = file.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "Tmp" }
    return (fn.bodyExpression as KtBlockExpression).statements.first()
  }
}
