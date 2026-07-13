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

    // Props / modifiers changed. P0 trust gate: prefer replacing ONLY the
    // changed arguments' VALUE expressions — that is byte-exact for everything
    // the user didn't touch (indentation, spacing, one-line vs multi-line).
    // Prop add/remove or modifier changes fall back to a whole-list replace,
    // re-indented to the call's depth (and kept single-line if the source was).
    if (parsed.props != target.props || parsed.modifiers != target.modifiers) {
      if (!editArgumentValues(call, parsed, target, factory)) {
        val oldList = call.valueArgumentList ?: return false
        var callText = NodeEmitter.statementText(target.copy(children = emptyList()), indentOf(call))
        if ('\n' !in oldList.text) callText = collapseCall(callText)
        val newCall = factory.createExpression(callText) as? KtCallExpression ?: return false
        val newList = newCall.valueArgumentList ?: return false
        oldList.replace(newList)
      }
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
      insertStatement(block, tc, anchorPsi, factory)
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
    node: DocNode,
    after: KtExpression?,
    factory: KtPsiFactory,
  ) {
    // P0 indent fix: every insertion carries "\n" + the destination indent, and
    // the statement text itself is emitted re-indented to that depth (the old
    // bare createNewLine() left inserted/shifted lines at column 0).
    if (after != null) {
      val indent = indentOf(after)
      val stmt = factory.createExpression(NodeEmitter.statementText(node, indent))
      val ws = block.addAfter(factory.createWhiteSpace("\n$indent"), after)
      block.addAfter(stmt, ws)
    } else {
      val first = block.statements.firstOrNull()
      if (first != null) {
        val indent = indentOf(first)
        val stmt = factory.createExpression(NodeEmitter.statementText(node, indent))
        val inserted = block.addBefore(stmt, first)
        block.addAfter(factory.createWhiteSpace("\n$indent"), inserted)
      } else {
        val braceIndent = indentOf(block)
        val indent = "$braceIndent  "
        val stmt = factory.createExpression(NodeEmitter.statementText(node, indent))
        val ws = block.addAfter(factory.createWhiteSpace("\n$indent"), block.lBrace)
        val inserted = block.addAfter(stmt, ws)
        block.addAfter(factory.createWhiteSpace("\n$braceIndent"), inserted)
      }
    }
  }

  /** Same prop KEYS, some values changed → replace just those value exprs. */
  private fun editArgumentValues(
    call: KtCallExpression,
    parsed: DocNode.Widget,
    target: DocNode.Widget,
    factory: KtPsiFactory,
  ): Boolean {
    if (parsed.modifiers != target.modifiers) return false
    if (parsed.props.keys != target.props.keys) return false
    // Canonical values come from the emitter so write-back matches a fresh export.
    val canonical = factory.createExpression(
      NodeEmitter.statementText(target.copy(children = emptyList()), ""),
    ) as? KtCallExpression ?: return false
    val newByName = canonical.valueArguments.associateBy(
      { it.getArgumentName()?.asName?.asString() },
      { it.getArgumentExpression() },
    )
    for ((k, v) in target.props) {
      if (parsed.props[k] == v) continue
      val arg = call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == k }
        ?: return false // positional/absent arg — not safely surgical here
      val newExpr = newByName[k] ?: return false
      val oldExpr = arg.getArgumentExpression() ?: return false
      oldExpr.replace(newExpr)
    }
    return true
  }

  /** `Foo(\n  a = 1,\n  b = 2,\n)` → `Foo(a = 1, b = 2)` (grammar props never hold raw newlines). */
  private fun collapseCall(text: String): String {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size <= 1) return text
    val body = lines.drop(1).dropLast(1).joinToString(" ").removeSuffix(",")
    return lines.first() + body + lines.last()
  }

  /** Leading whitespace of the line [element] starts on (its splice depth). */
  private fun indentOf(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement): String {
    val text = element.containingFile.text
    val off = element.textRange.startOffset
    val lineStart = text.lastIndexOf('\n', off - 1) + 1
    return text.substring(lineStart, off).takeIf { it.isBlank() } ?: ""
  }

  private fun deleteStatement(stmt: KtExpression) {
    // Remove the statement (PSI trims surrounding whitespace on delete).
    stmt.delete()
  }

  /** RawCode expressions may be block-bodied `if`/`for`; keep them expression-parseable. */
  private fun normalizeExpr(text: String): String = text
}
