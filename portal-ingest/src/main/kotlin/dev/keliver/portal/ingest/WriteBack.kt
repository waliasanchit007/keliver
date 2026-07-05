package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.UiDocument
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Surgical write-back (design §4, spike S2): apply a target document onto the
 * CURRENT file text by editing only the PSI that actually changed — prop and
 * modifier edits replace just the call's argument list, child inserts/deletes
 * touch just those statements. Comments, formatting, and RawCode elsewhere
 * survive byte-exact. Returns null when the change isn't safely surgical
 * (type change, reorder, contract change) — caller falls back to full export.
 */
object WriteBack {
  fun merge(fileText: String, target: UiDocument): String? {
    val rec = Recognizer.recognize("Screen.kt", fileText) ?: return null
    val file = rec.file ?: return null
    val factory = PsiEnv.factory

    // Contract change isn't surgical yet — bail so the interface is regenerated.
    if (rec.contract != target.contract) return null

    val targetRoot = target.root as? DocNode.Widget ?: return null
    if (!mergeWidget(rec.root, targetRoot, rec.psiByHandle, factory)) return null
    return file.text
  }

  /** True = merged surgically; false = bail to full export. */
  private fun mergeWidget(
    parsed: DocNode,
    target: DocNode,
    psi: Map<Long, KtExpression>,
    factory: KtPsiFactory,
  ): Boolean {
    // Type/shape mismatch at a matched position → not surgical.
    if (parsed::class != target::class) return false

    if (parsed is DocNode.RawCode && target is DocNode.RawCode) {
      if (parsed.text != target.text) {
        val call = psi[parsed.handle.v] ?: return false
        call.replace(factory.createExpression(target.text.let { normalizeExpr(it) }))
      }
      return true
    }
    parsed as DocNode.Widget
    target as DocNode.Widget
    if (parsed.type != target.type) return false

    // M5 logic nodes (if/forEach): props change → full regen (bail); else recurse children.
    if (parsed.type == "Condition" || parsed.type == "Repeat") {
      if (parsed.props != target.props) return false
      val block = logicBlock(psi[parsed.handle.v]) ?: return false
      return mergeChildren(parsed.children, target.children, block, psi, factory)
    }

    val call = psi[parsed.handle.v] as? KtCallExpression ?: return false

    // Props / modifiers changed → replace ONLY the argument list.
    if (parsed.props != target.props || parsed.modifiers != target.modifiers) {
      val oldList = call.valueArgumentList ?: return false
      val newList = NodeEmitter.argumentList(target) ?: return false
      oldList.replace(newList)
    }

    // Children.
    if (parsed.children.isEmpty() && target.children.isEmpty()) return true
    val block = call.lambdaArguments.firstOrNull()
      ?.getLambdaExpression()?.bodyExpression as? KtBlockExpression
      ?: return target.children.isEmpty() // no lambda in file → only OK if no children wanted

    return mergeChildren(parsed.children, target.children, block, psi, factory)
  }

  private fun mergeChildren(
    parsedChildren: List<DocNode>,
    targetChildren: List<DocNode>,
    block: KtBlockExpression,
    psi: Map<Long, KtExpression>,
    factory: KtPsiFactory,
  ): Boolean {
    // Greedy in-order match by shape; require matched parsed indices strictly
    // increasing (else it's a reorder → bail).
    val consumed = BooleanArray(parsedChildren.size)
    val matchOf = arrayOfNulls<Int>(targetChildren.size) // targetIdx -> parsedIdx
    var lastParsed = -1
    for ((ti, tc) in targetChildren.withIndex()) {
      var pi = -1
      for (i in parsedChildren.indices) {
        if (!consumed[i] && sameShape(parsedChildren[i], tc)) { pi = i; break }
      }
      if (pi >= 0) {
        if (pi < lastParsed) return false // reorder — not surgical
        consumed[pi] = true
        matchOf[ti] = pi
        lastParsed = pi
      }
    }

    // Recurse matched pairs first (prop/child edits within kept nodes).
    for ((ti, tc) in targetChildren.withIndex()) {
      val pi = matchOf[ti] ?: continue
      if (!mergeWidget(parsedChildren[pi], tc, psi, factory)) return false
    }

    // Deletes: parsed children never matched.
    for (i in parsedChildren.indices) {
      if (!consumed[i]) {
        val stmt = psi[parsedChildren[i].handle.v] ?: return false
        deleteStatement(stmt)
      }
    }

    // Inserts: target children with no match, placed after the preceding kept sibling.
    for ((ti, tc) in targetChildren.withIndex()) {
      if (matchOf[ti] != null) continue
      val anchorTargetIdx = (ti - 1 downTo 0).firstOrNull { matchOf[it] != null }
      val anchorPsi = anchorTargetIdx?.let { psi[parsedChildren[matchOf[it]!!].handle.v] }
      val newStmt = NodeEmitter.statement(tc)
      insertStatement(block, newStmt, anchorPsi, factory)
    }
    return true
  }

  private fun sameShape(a: DocNode, b: DocNode): Boolean = when {
    a is DocNode.Widget && b is DocNode.Widget -> a.type == b.type
    a is DocNode.RawCode && b is DocNode.RawCode -> true
    else -> false
  }

  /** The children block of a logic node's PSI (if-then block / forEach lambda body). */
  private fun logicBlock(expr: KtExpression?): KtBlockExpression? = when (expr) {
    is KtIfExpression -> expr.then as? KtBlockExpression
    is KtDotQualifiedExpression ->
      (expr.selectorExpression as? KtCallExpression)?.lambdaArguments?.firstOrNull()
        ?.getLambdaExpression()?.bodyExpression as? KtBlockExpression
    else -> null
  }

  private fun insertStatement(
    block: KtBlockExpression,
    stmt: KtExpression,
    after: KtExpression?,
    factory: KtPsiFactory,
  ) {
    val newline = factory.createNewLine()
    if (after != null) {
      val nl = block.addAfter(newline, after)
      block.addAfter(stmt, nl)
    } else {
      val first = block.statements.firstOrNull()
      if (first != null) {
        val inserted = block.addBefore(stmt, first)
        block.addAfter(factory.createNewLine(), inserted)
      } else {
        block.addAfter(stmt, block.lBrace)
      }
    }
  }

  private fun deleteStatement(stmt: KtExpression) {
    // Remove the statement (PSI trims surrounding whitespace on delete).
    stmt.delete()
  }

  /** RawCode expressions may be block-bodied `if`/`for`; keep them expression-parseable. */
  private fun normalizeExpr(text: String): String = text
}
