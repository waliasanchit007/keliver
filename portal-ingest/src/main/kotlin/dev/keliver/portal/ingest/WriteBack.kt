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
  fun merge(
    fileText: String,
    target: UiDocument,
    components: dev.keliver.portal.ComponentRegistry = dev.keliver.portal.EmptyComponentRegistry,
    /** K2: the app's design tokens — the SAME table the document was ingested with. */
    tokens: TokenTable = TokenTable.EMPTY,
    /** The file's name as the token table knows it (a private top-level constant is file-scoped). */
    fileName: String = "Screen.kt",
  ): String? {
    val rec = Recognizer.recognize(fileName, fileText, components, tokens) ?: return null
    val file = rec.file ?: return null
    val factory = PsiEnv.factory

    // P2b-a: contract drift no longer forces a full regen — ContractWriteBack
    // maintains the Bindings interface as a separate pass after every write.

    val targetRoot = target.root as? DocNode.Widget ?: return null
    val written = LinkedHashSet<String>()
    needed.set(written)
    lookup.set(rec.tokenLookup)
    try {
      if (!mergeWidget(rec.root, targetRoot, rec.psiByHandle, factory, components, parentType = null)) return null
    } finally {
      needed.remove()
      lookup.remove()
    }
    // K1: text write-back added may name symbols the file never imported
    // (`Margin(all = 6.dp)` → dp, `.width(Dp(…))` → Dp, a new widget call).
    // Each splice reports its imports STRUCTURALLY (exporter / grammar).
    return ensureImports(file.text, written)
  }

  /** The imports this merge's splices need (for [ensureImports]); per thread, per merge. */
  private val needed = ThreadLocal<MutableSet<String>>()

  /** K2: design-token resolution for the file being merged; per thread, per merge. */
  private val lookup = ThreadLocal<TokenLookup?>()

  private fun need(imports: Set<String>) {
    needed.get()?.addAll(imports)
  }

  /**
   * Adds `import <fq>` for each of [fqs] the file lacks — skipping ones already
   * imported, covered by a star import, in the file's own package, or whose
   * simple name the file already imports from elsewhere (never create a clash).
   * Each lands at its sorted position among the imports. Adds only.
   */
  internal fun ensureImports(text: String, fqs: Set<String>): String {
    if (fqs.isEmpty()) return text
    // `import a.b.C`, `import a.b.*`, `import a.b.C as D` — with backticked
    // segments, a trailing `;` and/or a trailing comment.
    val importRe = Regex(
      """^import\s+([\w.`]+?)(\.\*)?(?:\s+as\s+(`?\w+`?))?\s*;?\s*(?://.*|/\*.*)?$""",
      RegexOption.MULTILINE,
    )
    val exact = mutableSetOf<String>()
    val stars = mutableSetOf<String>()
    val simpleNames = mutableSetOf<String>()
    for (m in importRe.findAll(text)) {
      val path = m.groupValues[1].replace("`", "")
      val alias = m.groupValues[3].replace("`", "")
      if (m.groupValues[2].isNotEmpty()) {
        stars += path
      } else {
        exact += path
        simpleNames += alias.ifEmpty { path.substringAfterLast('.') }
      }
    }
    val pkg = Regex("""^package\s+([\w.`]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.replace("`", "")
    val missing = fqs.filter { fq ->
      val owner = fq.substringBeforeLast('.')
      fq !in exact && owner !in stars && owner != pkg && fq.substringAfterLast('.') !in simpleNames
    }.sorted()
    if (missing.isEmpty()) return text
    val lines = text.lines().toMutableList()
    val importIdx = lines.indices.filter { lines[it].startsWith("import ") }
    if (importIdx.isNotEmpty()) {
      for (fq in missing) {
        val line = "import $fq"
        val current = lines.indices.filter { lines[it].startsWith("import ") }
        lines.add(current.firstOrNull { lines[it] > line } ?: (current.last() + 1), line)
      }
      return lines.joinToString("\n")
    }
    // No imports yet: after the package line, else after any @file: annotations
    // (they must stay first), else at the top — set off by blank lines.
    val anchor = lines.indexOfFirst { it.startsWith("package ") }
      .takeIf { it >= 0 }
      ?: lines.indexOfLast { it.trimStart().startsWith("@file:") }.takeIf { it >= 0 }
    val block = missing.map { "import $it" }.toMutableList()
    var at = if (anchor == null) 0 else anchor + 1
    if (anchor != null) {
      if (at < lines.size && lines[at].isBlank()) at++ else block.add(0, "")
    }
    if (at >= lines.size || lines[at].isNotBlank()) block.add("")
    lines.addAll(at, block)
    return lines.joinToString("\n")
  }

  /** True = merged surgically; false = bail to full export. */
  private fun mergeWidget(
    parsed: DocNode,
    target: DocNode,
    psi: Map<Long, KtExpression>,
    factory: KtPsiFactory,
    components: dev.keliver.portal.ComponentRegistry,
    /** The layout parent scoped modifiers resolve against (see Recognizer.parseModifierChain). */
    parentType: String?,
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
      return mergeChildren(parsed.children, target.children, block, psi, factory, components, parentType)
    }

    val call = psi[parsed.handle.v] as? KtCallExpression ?: return false

    // Props / modifiers changed. P0 trust gate: prefer replacing ONLY the
    // changed arguments' VALUE expressions — that is byte-exact for everything
    // the user didn't touch (indentation, spacing, one-line vs multi-line).
    // Prop add/remove or modifier changes fall back to a whole-list replace,
    // re-indented to the call's depth (and kept single-line if the source was).
    if (parsed.props != target.props || parsed.modifiers != target.modifiers) {
      if (!editArgumentValues(call, parsed, target, factory, components, parentType)) {
        val oldList = call.valueArgumentList ?: return false
        val emitted = NodeEmitter.statement(target.copy(children = emptyList()), indentOf(call), components, parentType)
        var callText = emitted.text
        if ('\n' !in oldList.text) callText = collapseCall(callText)
        val newCall = factory.createExpression(callText) as? KtCallExpression ?: return false
        val newList = newCall.valueArgumentList ?: return false
        oldList.replace(newList)
        need(emitted.imports)
      }
    }

    // Children.
    if (parsed.children.isEmpty() && target.children.isEmpty()) return true
    val block = call.lambdaArguments.firstOrNull()
      ?.getLambdaExpression()?.bodyExpression as? KtBlockExpression
      ?: return target.children.isEmpty() // no lambda in file → only OK if no children wanted

    return mergeChildren(parsed.children, target.children, block, psi, factory, components, parsed.type)
  }

  private fun mergeChildren(
    parsedChildren: List<DocNode>,
    targetChildren: List<DocNode>,
    block: KtBlockExpression,
    psi: Map<Long, KtExpression>,
    factory: KtPsiFactory,
    components: dev.keliver.portal.ComponentRegistry,
    parentType: String?,
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
      if (!mergeWidget(parsedChildren[pi], tc, psi, factory, components, parentType)) return false
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
      insertStatement(block, tc, anchorPsi, factory, components, parentType)
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
    components: dev.keliver.portal.ComponentRegistry,
    parentType: String?,
  ) {
    fun text(indent: String) = NodeEmitter.statement(node, indent, components, parentType).also { need(it.imports) }.text
    // P0 indent fix: every insertion carries "\n" + the destination indent, and
    // the statement text itself is emitted re-indented to that depth (the old
    // bare createNewLine() left inserted/shifted lines at column 0).
    if (after != null) {
      val indent = indentOf(after)
      val stmt = factory.createExpression(text(indent))
      val ws = block.addAfter(factory.createWhiteSpace("\n$indent"), after)
      block.addAfter(stmt, ws)
    } else {
      val first = block.statements.firstOrNull()
      if (first != null) {
        val indent = indentOf(first)
        val stmt = factory.createExpression(text(indent))
        val inserted = block.addBefore(stmt, first)
        block.addAfter(factory.createWhiteSpace("\n$indent"), inserted)
      } else {
        // An inline lambda block starts at the `{`, so indentOf(block) is empty:
        // the text before it also contains the component call. Derive the closing
        // brace indentation from that owning call instead.
        val braceIndent = block.text.substringAfterLast('\n')
          .takeWhile { it == ' ' || it == '\t' }
          .takeIf { '\n' in block.text }
          ?: owningCall(block)?.let(::indentOf)
          ?: lineLeadingIndent(block)
        val indent = "$braceIndent  "
        val stmt = factory.createExpression(text(indent))
        val inserted = block.addAfter(stmt, block.lBrace)
        val leading = factory.createWhiteSpace("\n$indent")
        val trailing = factory.createWhiteSpace("\n$braceIndent")
        // Lambda-body whitespace lives beside the body node in Kotlin PSI, not
        // necessarily beside its first statement, so walk to the preceding leaf.
        val previous = org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil.prevLeaf(inserted)
        if (previous is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace) previous.replace(leading)
        else block.addBefore(leading, inserted)
        val next = inserted.nextSibling
        if (next is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace) next.replace(trailing)
        else block.addAfter(trailing, inserted)
      }
    }
  }

  /**
   * Same ARGUMENTS, some values changed → replace just those value exprs.
   *
   * K1: a Margin prop's four wire sides are ONE argument; when only some sides
   * changed, only those sides' value texts inside the `Margin(…)` are
   * rewritten (falling back to the canonical Margin expression when the
   * author's form can't express the change). A modifier change with the same
   * modifier set rewrites only the changed modifiers' arguments inside the
   * chain; an added/removed modifier replaces just the `modifier = …` value.
   * Each edit is offset-based text surgery on one argument expression.
   */
  private fun editArgumentValues(
    call: KtCallExpression,
    parsed: DocNode.Widget,
    target: DocNode.Widget,
    factory: KtPsiFactory,
    components: dev.keliver.portal.ComponentRegistry,
    parentType: String?,
  ): Boolean {
    val margins = dev.keliver.portal.widgetSpec(target.type)
      ?.let { dev.keliver.portal.marginProps(it.props) }.orEmpty()
    fun argName(key: String) = if (key.substringBefore('.') in margins) key.substringBefore('.') else key
    val parsedArgs = parsed.props.keys.groupBy(::argName)
    val targetArgs = target.props.keys.groupBy(::argName)
    if (parsedArgs.keys != targetArgs.keys) return false
    // Canonical values come from the emitter so write-back matches a fresh export.
    val canonical = factory.createExpression(
      NodeEmitter.statementText(target.copy(children = emptyList()), "", components, parentType),
    ) as? KtCallExpression ?: return false
    val newByName = canonical.valueArguments.associateBy(
      { it.getArgumentName()?.asName?.asString() },
      { it.getArgumentExpression() },
    )
    fun sourceArg(name: String) = call.valueArguments
      .firstOrNull { it.getArgumentName()?.asName?.asString() == name }
      ?.getArgumentExpression() // null = positional/absent arg — not safely surgical here
    val edits = mutableListOf<Pair<KtExpression, String>>()
    val imports = LinkedHashSet<String>()
    for (name in targetArgs.keys) {
      val keys = (parsedArgs.getValue(name) + targetArgs.getValue(name)).distinct()
      if (keys.all { parsed.props[it] == target.props[it] }) continue
      val oldExpr = sourceArg(name) ?: return false
      val canonicalText = newByName[name]?.text
      val text = if (name in margins) {
        val sideKeys = dev.keliver.portal.MARGIN_SIDES.map { "$name.$it" }
        MarginSyntax.surgical(oldExpr.text, sidesOf(parsed.props, sideKeys), sidesOf(target.props, sideKeys))
          ?: canonicalText
      } else {
        // A Dp keeps the author's spelling (`16.dp` stays `.dp`, `Dp(16.0)` stays Dp()).
        val v = (target.props[name] as? dev.keliver.portal.document.PropValue.Lit)?.d
        if (v != null && MarginSyntax.parseDp(oldExpr.text) != null) MarginSyntax.dpLike(oldExpr.text, v) else canonicalText
      } ?: return false
      edits += oldExpr to text
      imports += FragmentImports.value(text)
      // K2: a token written as its reference needs its declaring object (structural).
      keys.forEach { imports += FragmentImports.token(target.props[it]) }
    }
    if (parsed.modifiers != target.modifiers) {
      val oldExpr = sourceArg("modifier") ?: return false
      // K2: the canonical chain may reference a token the FILE cannot resolve yet
      // (its import is added below), so the target's own tokens resolve first.
      val tokens = targetTokens(target.modifiers.values, lookup.get())
      val text = ModifierChain.surgical(
        oldExpr.text, parsed.modifiers, target.modifiers, parentType, newByName["modifier"]?.text, tokens,
      ) ?: return false
      edits += oldExpr to text
      imports += FragmentImports.chain(text, parentType, tokens)
      target.modifiers.values.forEach { imports += FragmentImports.token(it) }
    }
    for ((oldExpr, text) in edits) {
      if (oldExpr.text != text) oldExpr.replace(factory.createExpression(text))
    }
    need(imports)
    return true
  }

  /** A lookup that knows [values]' tokens by their reference first, then the file's own resolution. */
  private fun targetTokens(values: Collection<dev.keliver.portal.document.PropValue>, file: TokenLookup?): TokenLookup {
    val own = values.filterIsInstance<dev.keliver.portal.document.PropValue.Token>().associateBy { it.ref }
    return TokenLookup { t -> own[t.trim()] ?: file?.lookup(t) }
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

  private fun lineLeadingIndent(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement): String {
    val text = element.containingFile.text
    val off = element.textRange.startOffset
    val lineStart = text.lastIndexOf('\n', off - 1) + 1
    return text.substring(lineStart, off).takeWhile { it == ' ' || it == '\t' }
  }

  private fun owningCall(block: KtBlockExpression): KtCallExpression? {
    var current = block.parent
    while (current != null && current !is KtCallExpression) current = current.parent
    return current as? KtCallExpression
  }

  private fun deleteStatement(stmt: KtExpression) {
    // Remove the statement (PSI trims surrounding whitespace on delete).
    stmt.delete()
  }

  /** RawCode expressions may be block-bodied `if`/`for`; keep them expression-parseable. */
  private fun normalizeExpr(text: String): String = text
}
