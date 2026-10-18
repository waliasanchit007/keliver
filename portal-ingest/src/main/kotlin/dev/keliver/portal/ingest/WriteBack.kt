package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Surgical write-back (design §4, spike S2): apply a target document onto the
 * CURRENT file text by editing only the PSI that actually changed — prop and
 * modifier edits replace just the call's argument list, child inserts/deletes
 * touch just those statements, a moved node is cut and placed with its exact
 * source text. Comments, formatting, and RawCode elsewhere survive
 * byte-exact. [attempt] says why when the change isn't safely surgical; the
 * merged text is returned only when it reads back as [target] (the safety
 * net — [sameTree]): a surgical result is written only once it is checked.
 */
object WriteBack {
  /** What [attempt] made of a change. */
  sealed interface Outcome {
    /** The file with the change made in place — checked: it reads back as the target. */
    data class Merged(val text: String) : Outcome

    /** Not written surgically, and why (for the write-back status the editor shows). */
    data class NotSurgical(val reason: String) : Outcome
  }

  /** [attempt]'s text, or null when the change is not surgical. */
  fun merge(
    fileText: String,
    target: UiDocument,
    components: dev.keliver.portal.ComponentRegistry = dev.keliver.portal.EmptyComponentRegistry,
    tokens: TokenTable = TokenTable.EMPTY,
    fileName: String = "Screen.kt",
    base: UiDocument? = null,
  ): String? = (attempt(fileText, target, components, tokens, fileName, base) as? Outcome.Merged)?.text

  fun attempt(
    fileText: String,
    target: UiDocument,
    components: dev.keliver.portal.ComponentRegistry = dev.keliver.portal.EmptyComponentRegistry,
    /** K2: the app's design tokens — the SAME table the document was ingested with. */
    tokens: TokenTable = TokenTable.EMPTY,
    /** The file's name as the token table knows it (a private top-level constant is file-scoped). */
    fileName: String = "Screen.kt",
    /**
     * The document the file was last written from (or ingested as), with the
     * handles [target] shares. When its tree is the file's, every node pairs
     * with its statement only by identity (the same handle): an edit, an
     * insert beside a same-type sibling, a delete and a move are each what
     * they are. Null (or a base that is not the file's tree) = position and
     * shape decide, and a reorder is not surgical.
     */
    base: UiDocument? = null,
  ): Outcome {
    fun no(reason: String) = Outcome.NotSurgical(reason)
    val rec = Recognizer.recognize(fileName, fileText, components, tokens)
      ?: return no("the file has no @Composable screen function the editor can read")
    val file = rec.file ?: return no("the file could not be parsed")
    val factory = PsiEnv.factory

    // P2b-a: contract drift no longer forces a full regen — ContractWriteBack
    // maintains the Bindings interface as a separate pass after every write.

    val targetRoot = target.root as? DocNode.Widget ?: return no("the document's root is not a widget")
    val written = LinkedHashSet<String>()
    val ids = base?.let { identitiesOf(rec.root, it.root) }
    needed.set(written)
    lookup.set(rec.tokenLookup)
    conds.set(rec.condParser)
    bindings.set(rec.bindingsParam)
    why.set(null)
    moves.set(ids?.let { MoveContext(rec, fileName, it, targetRoot) })
    try {
      // Mutable: a replaced statement's handle is re-pointed at its replacement,
      // so a later insert anchored on it never lands relative to dead PSI.
      val ok = try {
        mergeWidget(rec.root, targetRoot, rec.psiByHandle.toMutableMap(), factory, components, parentType = null)
      } catch (e: Exception) {
        bail("the edit could not be spliced into the file (${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()})")
      } catch (e: AssertionError) {
        bail("the edit could not be spliced into the file (${e.message?.lineSequence()?.firstOrNull()})")
      }
      if (!ok) return no(why.get() ?: "the change is not a surgical edit")
    } finally {
      needed.remove()
      lookup.remove()
      conds.remove()
      bindings.remove()
      why.remove()
      moves.remove()
    }
    // K1: text write-back added may name symbols the file never imported
    // (`Margin(all = 6.dp)` → dp, `.width(Dp(…))` → Dp, a new widget call).
    // Each splice reports its imports STRUCTURALLY (exporter / grammar).
    val out = ensureImports(file.text, written).let { afterSpliceForTest.get()?.invoke(it) ?: it }
    // Safety net (K3 review): the surgical text is written only when it reads
    // back as the target — one more parse per write.
    val back = runCatching { Recognizer.recognize(fileName, out, components, tokens) }.getOrNull()
      ?: return no("the edited file would no longer read as a screen")
    if (!sameTree(back.root, targetRoot)) {
      val diff = firstDifference(back.root, targetRoot) ?: "it reads back differently"
      println("write-back: surgical merge of $fileName does not read back as the document: $diff")
      return no("the edited file would not read back as the document: $diff")
    }
    // The parse was of the LF text: a CRLF file is written back CRLF.
    val restored = LineEndings.restore(fileText, out)
      ?: return no("the file mixes CRLF and LF line endings and the edit touches a CRLF line")
    return Outcome.Merged(restored)
  }

  /** Why the current merge is not surgical (the first reason recorded); per thread, per merge. */
  private val why = ThreadLocal<String?>()

  /** Records [reason] (the first one wins) and answers false: not surgical. */
  private fun bail(reason: String): Boolean {
    if (why.get() == null) why.set(reason)
    return false
  }

  /**
   * Where [a] (what the file would read as) and [b] (the document) first
   * differ, in words — null when they do not.
   */
  internal fun firstDifference(a: DocNode, b: DocNode, path: String = ""): String? {
    fun name(n: DocNode) = when (n) {
      is DocNode.RawCode -> "code `${n.text.trim().replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(57) + "…" else it }}`"
      is DocNode.Widget -> n.type
    }
    val here = if (path.isEmpty()) name(b) else "$path › ${name(b)}"
    if (a is DocNode.RawCode || b is DocNode.RawCode || (a as DocNode.Widget).type != (b as DocNode.Widget).type) {
      return if (sameTree(a, b)) null else "$here would read as ${name(a)}"
    }
    if (if (a.type == "Condition") condKey(a) != condKey(b) else a.props != b.props) return "$here: its properties would read differently"
    if (!sameModifiers(a.modifiers, b.modifiers)) return "$here: its modifiers would read differently"
    if (a.explicitId != b.explicitId) return "$here: its id would read differently"
    if (a.children.size != b.children.size) return "$here would have ${a.children.size} children, not ${b.children.size}"
    return a.children.indices.firstNotNullOfOrNull { firstDifference(a.children[it], b.children[it], here) }
  }

  /** Test seam: rewrites a merge's text before it is checked (forces a divergence). Per thread. */
  internal val afterSpliceForTest = ThreadLocal<((String) -> String)?>()

  /**
   * The same document tree, handles aside: widget type, props, modifiers,
   * explicit id and children, in order; RawCode by its text (trimmed — the
   * recognizer keeps a statement's text without surrounding whitespace). A
   * Condition's props compare as its condition ([condKey]: an absent `test`
   * is the Boolean one, an absent `negate` is false). Modifiers compare in
   * ORDER too ([sameModifiers]) — `background().padding()` is not
   * `padding().background()`.
   */
  internal fun sameTree(a: DocNode, b: DocNode): Boolean = when {
    a is DocNode.RawCode && b is DocNode.RawCode -> a.text.trim() == b.text.trim()
    a is DocNode.Widget && b is DocNode.Widget ->
      a.type == b.type &&
        (if (a.type == "Condition") condKey(a) == condKey(b) else a.props == b.props) &&
        sameModifiers(a.modifiers, b.modifiers) && a.explicitId == b.explicitId &&
        a.children.size == b.children.size && a.children.indices.all { sameTree(a.children[it], b.children[it]) }
    else -> false
  }

  /**
   * The same modifiers in the same chain order: equal values, and the same
   * modifier NAMES in first-occurrence order (what the exporter and the
   * recognizer write and read). The order of one modifier's own keys
   * (`Border.widthDp`, `Border.colorArgb`) is not chain order and is ignored.
   */
  internal fun sameModifiers(a: Map<String, PropValue>, b: Map<String, PropValue>): Boolean =
    a == b && modifierOrder(a) == modifierOrder(b)

  private fun modifierOrder(m: Map<String, PropValue>): List<String> = m.keys.map { it.substringBefore('.') }.distinct()

  /** The imports this merge's splices need (for [ensureImports]); per thread, per merge. */
  private val needed = ThreadLocal<MutableSet<String>>()

  /** K2: design-token resolution for the file being merged; per thread, per merge. */
  private val lookup = ThreadLocal<TokenLookup?>()

  /** K3: condition reading for the file being merged; per thread, per merge. */
  private val conds = ThreadLocal<CondParser?>()

  /** The screen's bindings parameter name (null = none, `b` assumed); per thread, per merge. */
  private val bindings = ThreadLocal<String?>()

  /**
   * A condition as the file spells it: a screen field through the file's own
   * bindings parameter (`bindings.open`), an item field as it is. Inserted
   * nodes and changed values spell it the same way ([NodeEmitter]'s
   * `bindingsParam`).
   */
  private fun condText(c: dev.keliver.portal.Cond): String {
    val param = bindings.get() ?: "b"
    return c.kotlin { if ('.' in it) it else "$param.$it" }
  }

  /**
   * Each parsed node's handle in [base] — by position, and only when [base]'s
   * tree IS the parsed one ([sameTree]); otherwise null (no identities: the
   * file changed since [base] was written, or the export read back otherwise).
   */
  private fun identitiesOf(parsed: DocNode, base: DocNode): Map<Long, Long>? {
    if (!sameTree(parsed, base)) return null
    val out = HashMap<Long, Long>()
    fun zip(p: DocNode, b: DocNode) {
      out[p.handle.v] = b.handle.v
      if (p is DocNode.Widget && b is DocNode.Widget) p.children.indices.forEach { zip(p.children[it], b.children[it]) }
    }
    zip(parsed, base)
    return out
  }

  private fun need(imports: Set<String>) {
    needed.get()?.addAll(imports)
  }

  /**
   * Adds `import <fq>` for each of [fqs] the file lacks — skipping ones already
   * imported, covered by a star import, in the file's own package, or whose
   * simple name the file already imports from elsewhere (never clash with them).
   * Two of [fqs] that share a simple name from different owners (`x.C`,
   * `q.C`) are both written: the relay's gate and undo/redo refuse such a
   * document before it gets here, and if one is forced past them the result
   * is a loud conflicting-import compile error — never a silent choice of
   * which `C` the references mean (writing neither could let another `C` in
   * scope bind them).
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

  /** True = merged surgically; false = not surgical ([bail] says why). */
  private fun mergeWidget(
    parsed: DocNode,
    target: DocNode,
    psi: MutableMap<Long, KtExpression>,
    factory: KtPsiFactory,
    components: dev.keliver.portal.ComponentRegistry,
    /** The layout parent scoped modifiers resolve against (see Recognizer.parseModifierChain). */
    parentType: String?,
  ): Boolean {
    // Type/shape mismatch at a matched position → not surgical.
    if (parsed::class != target::class) return bail("a node changed between code and widget")

    if (parsed is DocNode.RawCode && target is DocNode.RawCode) {
      if (parsed.text != target.text) {
        val stmt = psi[parsed.handle.v] ?: return bail("the code's statement was not found")
        // One statement, parsed in a block (it may be a declaration, which is no expression).
        val replacement = statementOf(factory, target.text)
          ?: return bail("the new code is not exactly one Kotlin statement")
        val replaced = stmt.replace(replacement)
        (replaced as? KtExpression)?.let { psi[parsed.handle.v] = it }
      }
      return true
    }
    parsed as DocNode.Widget
    target as DocNode.Widget
    if (parsed.type != target.type) return bail("a ${parsed.type} became a ${target.type}")

    if (parsed.type == "Condition") return mergeCondition(parsed, target, psi, factory, components, parentType)
    // M5 logic nodes (forEach): props change → full regen (bail); else recurse children.
    if (parsed.type == "Repeat") {
      if (parsed.props != target.props) return bail("a forEach's list or item name changed")
      val block = logicBlock(psi[parsed.handle.v]) ?: return bail("the forEach's block was not found")
      return mergeChildren(parsed.children, target.children, block, psi, factory, components, parentType)
    }

    val call = psi[parsed.handle.v] as? KtCallExpression ?: return bail("the ${parsed.type} call was not found")

    // Props / modifiers changed. P0 trust gate: prefer replacing ONLY the
    // changed arguments' VALUE expressions — that is byte-exact for everything
    // the user didn't touch (indentation, spacing, one-line vs multi-line).
    // Prop add/remove or modifier changes fall back to a whole-list replace,
    // re-indented to the call's depth (and kept single-line if the source was).
    if (parsed.props != target.props || !sameModifiers(parsed.modifiers, target.modifiers)) {
      if (!editArgumentValues(call, parsed, target, factory, components, parentType)) {
        val oldList = call.valueArgumentList ?: return false
        val emitted = NodeEmitter.statement(target.copy(children = emptyList()), indentOf(call), components, parentType, bindings.get())
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
      // No lambda in the file → only OK if no children are wanted.
      ?: return target.children.isEmpty() || bail("${target.type} has no `{ … }` block in the file to put children in")

    return mergeChildren(parsed.children, target.children, block, psi, factory, components, parsed.type)
  }

  /**
   * K3: a `Condition` (`if (c) { … } else { … }`). A changed condition
   * rewrites only the condition's text; each branch merges like any children
   * block. Adding an `else` inserts just ` else {\n<indent>}` after the then
   * block (its children then go in as inserts); removing one deletes just the
   * text from the `else` keyword through its block's `}`. The `if` itself is
   * never replaced — its PSI stays valid as an anchor for its siblings.
   */
  private fun mergeCondition(
    parsed: DocNode.Widget,
    target: DocNode.Widget,
    psi: MutableMap<Long, KtExpression>,
    factory: KtPsiFactory,
    components: dev.keliver.portal.ComponentRegistry,
    parentType: String?,
  ): Boolean {
    val ifExpr = psi[parsed.handle.v] as? KtIfExpression ?: return false
    val isElse = { n: DocNode -> n is DocNode.Widget && n.type == dev.keliver.portal.ELSE_TYPE }
    val parsedElse = parsed.children.firstOrNull(isElse) as DocNode.Widget?
    val targetElse = target.children.firstOrNull(isElse) as DocNode.Widget?
    if (parsed.props != target.props) {
      val tree = UiDocument("_", target.copy(children = emptyList()), dev.keliver.portal.document.Contract(), 0, 0).toWidgetTree()
      val cond = dev.keliver.portal.condOf(tree) ?: return false
      val condition = ifExpr.condition ?: return false
      condition.replace(factory.createExpression(condText(cond)))
    }
    val thenBlock = ifExpr.then as? KtBlockExpression ?: return false
    // Else first: it sits after the then block, so its edits move no offset the then block needs.
    when {
      parsedElse != null && targetElse != null -> {
        val elseBlock = ifExpr.`else` as? KtBlockExpression ?: return false
        if (!mergeChildren(parsedElse.children, targetElse.children, elseBlock, psi, factory, components, parentType)) return false
      }
      parsedElse != null -> if (!removeElse(ifExpr)) return false
      targetElse != null -> {
        val elseBlock = addElse(ifExpr, factory) ?: return false
        if (!mergeChildren(emptyList(), targetElse.children, elseBlock, psi, factory, components, parentType)) return false
      }
    }
    return mergeChildren(
      parsed.children.filterNot(isElse), target.children.filterNot(isElse), thenBlock, psi, factory, components, parentType,
    )
  }

  /** Deletes `else { … }` (and the whitespace before `else`) from [ifExpr]; nothing else moves. */
  private fun removeElse(ifExpr: KtIfExpression): Boolean {
    val keyword = ifExpr.elseKeyword ?: return false
    val body = ifExpr.`else`?.parent?.takeIf { it.parent == ifExpr } ?: return false
    val first = keyword.prevSibling?.takeIf { it is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace } ?: keyword
    ifExpr.node.removeRange(first.node, body.node.treeNext)
    return true
  }

  /** Appends ` else {\n<indent>}` to [ifExpr] (which has no else); returns the new else block. */
  private fun addElse(ifExpr: KtIfExpression, factory: KtPsiFactory): KtBlockExpression? {
    if (ifExpr.elseKeyword != null) return null
    val thenBody = ifExpr.then?.parent?.takeIf { it.parent == ifExpr } ?: return null
    val template = factory.createExpression("if (x) {\n} else {\n${indentOf(ifExpr)}}") as? KtIfExpression ?: return null
    val keyword = template.elseKeyword ?: return null
    val body = template.`else`?.parent ?: return null
    val first = keyword.prevSibling ?: return null
    ifExpr.node.addChildren(first.node, body.node.treeNext, thenBody.node.treeNext)
    return ifExpr.`else` as? KtBlockExpression
  }

  private fun mergeChildren(
    parsedChildren: List<DocNode>,
    targetChildren: List<DocNode>,
    block: KtBlockExpression,
    psi: MutableMap<Long, KtExpression>,
    factory: KtPsiFactory,
    components: dev.keliver.portal.ComponentRegistry,
    parentType: String?,
  ): Boolean {
    moves.get()?.let { return mergeChildrenById(it, parsedChildren, targetChildren, block, psi, factory, components, parentType) }
    // No identities: greedy in-order match by shape; require matched parsed
    // indices strictly increasing (else it's a reorder → not surgical).
    val consumed = BooleanArray(parsedChildren.size)
    val matchOf = arrayOfNulls<Int>(targetChildren.size) // targetIdx -> parsedIdx
    var lastParsed = -1
    for ((ti, tc) in targetChildren.withIndex()) {
      var pi = -1
      for (i in parsedChildren.indices) {
        if (consumed[i]) continue
        if (sameShape(parsedChildren[i], tc)) { pi = i; break }
      }
      if (pi >= 0) {
        if (pi < lastParsed) return bail("children were reordered, and which node is which is not known (no base document)")
        consumed[pi] = true
        matchOf[ti] = pi
        lastParsed = pi
      }
    }
    pairEditedConditions(parsedChildren, targetChildren, consumed, matchOf)

    // Recurse matched pairs first (prop/child edits within kept nodes).
    for ((ti, tc) in targetChildren.withIndex()) {
      val pi = matchOf[ti] ?: continue
      if (!mergeWidget(parsedChildren[pi], tc, psi, factory, components, parentType)) return false
    }

    // Deletes: parsed children never matched.
    for (i in parsedChildren.indices) {
      if (!consumed[i]) {
        val stmt = psi[parsedChildren[i].handle.v] ?: return bail("a deleted node's statement was not found")
        deleteStatement(stmt)
      }
    }

    // Inserts: each target child with no match goes right after the statement
    // before it in the TARGET — a kept one, or the one just inserted — so a run
    // of inserts after one kept sibling (or at the block start) keeps its order.
    var previous: KtExpression? = null
    for ((ti, tc) in targetChildren.withIndex()) {
      val pi = matchOf[ti]
      previous = if (pi != null) psi[parsedChildren[pi].handle.v] ?: return bail("a kept node's statement was not found")
      else insertStatement(block, tc, previous, factory, components, parentType) ?: return bail("a new ${label(tc)} could not be inserted")
    }
    return true
  }

  /**
   * Children by IDENTITY ([MoveContext]: the base document is the file's
   * tree). A target child is the parsed child with its handle (kept), a node
   * the file holds elsewhere (moved here), or new (inserted). Of the kept
   * ones, the longest run already in target order stays put; every other is
   * moved: cut with its exact source text (its own leading comment lines and
   * same-line trailing comment) and placed after the target sibling before
   * it, as an insert is. A parsed child the target holds nowhere is deleted;
   * one it holds elsewhere is cut here (and placed there).
   */
  private fun mergeChildrenById(
    m: MoveContext,
    parsedChildren: List<DocNode>,
    targetChildren: List<DocNode>,
    block: KtBlockExpression,
    psi: MutableMap<Long, KtExpression>,
    factory: KtPsiFactory,
    components: dev.keliver.portal.ComponentRegistry,
    parentType: String?,
  ): Boolean {
    val here = HashMap<Long, Int>() // base handle -> index in parsedChildren
    parsedChildren.forEachIndexed { i, p -> m.ids[p.handle.v]?.let { here[it] = i } }
    val matchOf = arrayOfNulls<Int>(targetChildren.size)
    targetChildren.forEachIndexed { ti, tc -> matchOf[ti] = here[tc.handle.v] }
    val stays = longestIncreasing(matchOf)
    val kept = BooleanArray(parsedChildren.size)
    stays.forEach { ti -> kept[matchOf[ti]!!] = true }

    // Kept nodes first (edits inside them).
    for (ti in stays) {
      if (!mergeWidget(parsedChildren[matchOf[ti]!!], targetChildren[ti], psi, factory, components, parentType)) return false
    }
    // Leaving nodes: moved (elsewhere, or later in this block) are cut with their comments; the rest deleted.
    for (i in parsedChildren.indices) {
      if (kept[i]) continue
      val h = parsedChildren[i].handle.v
      val stmt = m.oldLoc[h] ?: psi[h] ?: return bail("a removed node's statement was not found")
      val baseHandle = m.ids[parsedChildren[i].handle.v]
      if (baseHandle != null && baseHandle in m.targetHandles) {
        if (!cutStatement(stmt)) return bail("a moved ${label(parsedChildren[i])} does not stand on lines of its own")
      } else {
        deleteStatement(stmt)
      }
    }
    // Then every target child in order, anchored on the one before it.
    var previous: KtExpression? = null
    val stayed = stays.toHashSet()
    for ((ti, tc) in targetChildren.withIndex()) {
      previous = when {
        ti in stayed -> psi[parsedChildren[matchOf[ti]!!].handle.v] ?: return bail("a kept node's statement was not found")
        tc.handle.v in m.parsedByBase -> {
          val source = m.parsedByBase.getValue(tc.handle.v)
          val placed = placeMoved(m, block, source, previous, psi, factory) ?: return bail("a moved ${label(tc)} could not be placed")
          if (!mergeWidget(source, tc, psi, factory, components, parentType)) return false
          psi[source.handle.v] ?: placed
        }
        else -> {
          if (m.holdsKnown(tc)) return bail("a new ${label(tc)} holds a node moved into it")
          insertStatement(block, tc, previous, factory, components, parentType) ?: return bail("a new ${label(tc)} could not be inserted")
        }
      }
    }
    return true
  }

  private fun label(n: DocNode) = when (n) {
    is DocNode.RawCode -> "code statement"
    is DocNode.Widget -> n.type
  }

  /** Indices of [matchOf] (non-null) forming the longest strictly increasing run (ties: the earliest). */
  private fun longestIncreasing(matchOf: Array<Int?>): List<Int> {
    val idx = matchOf.indices.filter { matchOf[it] != null }
    if (idx.isEmpty()) return emptyList()
    val len = IntArray(idx.size) { 1 }
    val prev = IntArray(idx.size) { -1 }
    for (j in idx.indices) for (i in 0 until j) {
      if (matchOf[idx[i]]!! < matchOf[idx[j]]!! && len[i] + 1 > len[j]) { len[j] = len[i] + 1; prev[j] = i }
    }
    var best = len.indices.maxByOrNull { len[it] }!!
    // maxByOrNull keeps the first maximum: prefer the run ending earliest.
    val out = ArrayList<Int>()
    while (best >= 0) { out += idx[best]; best = prev[best] }
    return out.reversed()
  }

  /**
   * One merge's identity context: parsed handle -> base handle ([ids]), the
   * parsed node each base handle is ([parsedByBase]), every handle the target
   * holds, and the file's text as parsed (a moved node is placed with ITS
   * text, taken before any splice).
   */
  private class MoveContext(rec: Recognized, private val fileName: String, val ids: Map<Long, Long>, target: DocNode) {
    val parsedByBase: Map<Long, DocNode> = HashMap<Long, DocNode>().also { out ->
      fun walk(n: DocNode) {
        ids[n.handle.v]?.let { out[it] = n }
        if (n is DocNode.Widget) n.children.forEach(::walk)
      }
      walk(rec.root)
    }
    val targetHandles: Set<Long> = HashSet<Long>().also { out ->
      fun walk(n: DocNode) {
        out += n.handle.v
        if (n is DocNode.Widget) n.children.forEach(::walk)
      }
      walk(target)
    }
    private val text: String = rec.file!!.text
    private val ranges: Map<Long, org.jetbrains.kotlin.com.intellij.openapi.util.TextRange> =
      rec.psiByHandle.mapValues { it.value.textRange }
    private val classes: Map<Long, Class<*>> = rec.psiByHandle.mapValues { it.value.javaClass }
    private val pristine: KtFile by lazy { PsiEnv.parse(fileName, text) }

    /**
     * Where a node stands at its OLD place when that is not its `psi` entry:
     * a moved node's `psi` is its new statement, but its old one is still to
     * be cut — and a node inside a moved copy stands in that copy until its
     * own block is merged.
     */
    val oldLoc = HashMap<Long, KtExpression>()

    /** Nodes already placed at their new position (their `psi` is final). */
    val placed = HashSet<Long>()

    /** Whether [n]'s subtree (below it) holds a node the file already has. */
    fun holdsKnown(n: DocNode): Boolean = n is DocNode.Widget && n.children.any { it.handle.v in parsedByBase || holdsKnown(it) }

    /** [node]'s statement as the file had it, with its comments and its subtree's offsets in it. */
    fun source(node: DocNode): MovedSource? {
      val range = ranges[node.handle.v] ?: return null
      val stmt = org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtRange(
        pristine, range.startOffset, range.endOffset, KtExpression::class.java,
      )?.let { e -> generateSequence(e as org.jetbrains.kotlin.com.intellij.psi.PsiElement) { it.parent }
        .takeWhile { it.textRange == range }.lastOrNull { it is KtExpression && it.parent is KtBlockExpression } as? KtExpression }
        ?: return null
      // Each subtree node's place among the statement's expressions in pre-order:
      // re-indenting changes only whitespace, so the copy's structure is the same.
      val order = expressionsOf(stmt)
      val inner = ArrayList<Pair<Long, Int>>()
      var lost = false
      fun walk(n: DocNode) {
        ranges[n.handle.v]?.let { r ->
          val at = order.indexOfFirst { it.textRange == r && it.javaClass == classes[n.handle.v] }
          if (at >= 0) inner += n.handle.v to at else lost = true
        }
        if (n is DocNode.Widget) n.children.forEach(::walk)
      }
      walk(node)
      if (lost) return null
      // Line starts inside a string or a comment are content, not indentation.
      val base = stmt.textRange.startOffset
      val keep = (org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(stmt, org.jetbrains.kotlin.psi.KtStringTemplateExpression::class.java) +
        org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(stmt, org.jetbrains.kotlin.com.intellij.psi.PsiComment::class.java))
        .map { it.textRange.shiftLeft(base) }
      return MovedSource(
        text = stmt.text,
        indent = lineIndent(text, range.startOffset),
        leading = leadingComments(stmt).map { it.text },
        trailing = trailingComment(stmt)?.text,
        inner = inner,
        content = keep,
      )
    }
  }

  /** [root] and every expression in it, in source (pre-)order. */
  private fun expressionsOf(root: org.jetbrains.kotlin.com.intellij.psi.PsiElement): List<KtExpression> {
    val out = ArrayList<KtExpression>()
    fun walk(e: org.jetbrains.kotlin.com.intellij.psi.PsiElement) {
      if (e is KtExpression) out += e
      var c = e.firstChild
      while (c != null) { walk(c); c = c.nextSibling }
    }
    walk(root)
    return out
  }

  /** A moved statement's source: its text, its line's indent, its comments, its subtree's places in it. */
  private class MovedSource(
    val text: String,
    val indent: String,
    val leading: List<String>,
    val trailing: String?,
    /** Subtree handle -> index in [expressionsOf] the statement. */
    val inner: List<Pair<Long, Int>>,
    /** Ranges (in [text]) of strings and comments, whose lines are never re-indented. */
    val content: List<org.jetbrains.kotlin.com.intellij.openapi.util.TextRange>,
  ) {
    /** [text] with each line after the first moved from [indent] to [to] depth (strings and comments untouched). */
    fun at(to: String): String {
      if (to == indent) return text
      val out = StringBuilder()
      var offset = 0
      text.split('\n').forEachIndexed { i, line ->
        if (i > 0) out.append('\n')
        val inContent = content.any { offset > it.startOffset && offset < it.endOffset }
        out.append(if (i > 0 && !inContent && line.startsWith(indent)) to + line.removePrefix(indent) else line)
        offset += line.length + 1
      }
      return out.toString()
    }
  }

  private fun lineIndent(text: String, offset: Int): String {
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    return text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
  }

  /** The per-merge identity context ([MoveContext]); null = no identities. Per thread. */
  private val moves = ThreadLocal<MoveContext?>()

  /**
   * The comments directly above [stmt] on lines of their own (no blank line
   * between), in source order — they belong to it and move with it.
   */
  private fun leadingComments(stmt: org.jetbrains.kotlin.com.intellij.psi.PsiElement): List<org.jetbrains.kotlin.com.intellij.psi.PsiComment> {
    val out = ArrayList<org.jetbrains.kotlin.com.intellij.psi.PsiComment>()
    var cur: org.jetbrains.kotlin.com.intellij.psi.PsiElement = stmt
    while (true) {
      val ws = cur.prevSibling
      val c = when {
        ws is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace && ws.text.count { it == '\n' } == 1 -> ws.prevSibling
        else -> null
      } as? org.jetbrains.kotlin.com.intellij.psi.PsiComment ?: break
      // The comment must start its own line (not trail the statement before it).
      val before = c.prevSibling
      val ownLine = before == null || (before is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace && '\n' in before.text)
      if (!ownLine) break
      out.add(0, c)
      cur = c
    }
    return out
  }

  /** The comment after [stmt] on its own line (`A() // why`), if any. */
  private fun trailingComment(stmt: org.jetbrains.kotlin.com.intellij.psi.PsiElement): org.jetbrains.kotlin.com.intellij.psi.PsiComment? {
    val ws = stmt.nextSibling
    val c = if (ws is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace && '\n' !in ws.text) ws.nextSibling else ws
    return c as? org.jetbrains.kotlin.com.intellij.psi.PsiComment
  }

  /**
   * Removes [stmt] with its own comments and the line break before them, so
   * the lines it stood on are gone. False when it shares a line with other
   * code (it is not cut).
   */
  private fun cutStatement(stmt: KtExpression): Boolean {
    val parent = stmt.parent
    val leading = leadingComments(stmt)
    val first: org.jetbrains.kotlin.com.intellij.psi.PsiElement = leading.firstOrNull() ?: stmt
    val last: org.jetbrains.kotlin.com.intellij.psi.PsiElement = trailingComment(stmt) ?: stmt
    val prev = first.prevSibling
    val next = last.nextSibling
    val startsLine = prev == null || (prev is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace && '\n' in prev.text)
    val endsLine = next == null || (next is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace && '\n' in next.text)
    if (!startsLine || !endsLine) return false
    when {
      prev != null -> parent.node.removeRange(prev.node, last.node.treeNext)
      next != null -> parent.node.removeRange(first.node, next.node.treeNext)
      else -> {
        parent.node.removeRange(first.node, last.node.treeNext)
        // The only statement of a lambda body: its line break lives outside the block.
        (parent.prevSibling as? org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace)?.let { it.parent.node.removeChild(it.node) }
      }
    }
    return true
  }

  /**
   * Places [source]'s statement (as the file had it, with its comments) in
   * [block] after [after] (null = first), re-indented to its new depth, and
   * re-points [psi] at the new statement and everything in it.
   */
  private fun placeMoved(
    m: MoveContext,
    block: KtBlockExpression,
    source: DocNode,
    after: KtExpression?,
    psi: MutableMap<Long, KtExpression>,
    factory: KtPsiFactory,
  ): KtExpression? {
    val src = m.source(source) ?: return null
    val inserted = place(block, after, factory) { indent -> statementOf(factory, src.at(indent)) } ?: return null
    val order = expressionsOf(inserted)
    for ((h, at) in src.inner) {
      val e = order.getOrNull(at) ?: return null
      if (h == source.handle.v) {
        // Its old statement is cut where it stood: remember it before psi moves on.
        if (h !in m.oldLoc) psi[h]?.let { m.oldLoc[h] = it }
        psi[h] = e
        m.placed += h
      } else {
        m.oldLoc[h] = e // inside the copy: that is where it now stands
        if (h !in m.placed) psi[h] = e
      }
    }
    val indent = indentOf(inserted)
    // Comments: the leading ones above it, the trailing one after it on its line.
    for (c in src.leading) {
      block.addBefore(factory.createComment(c), inserted)
      block.addBefore(factory.createWhiteSpace("\n$indent"), inserted)
    }
    src.trailing?.let { t ->
      val comment = block.addAfter(factory.createComment(t), inserted)
      block.addBefore(factory.createWhiteSpace(" "), comment)
    }
    return inserted
  }

  /**
   * Same node for in-order matching: the same widget type — and, for a K3
   * `Condition`, the same condition (a Condition with another condition is
   * another `if`, inserted beside it, never a rewrite of it). Else-ness may
   * differ: adding/removing the else branch merges surgically.
   */
  private fun sameShape(a: DocNode, b: DocNode): Boolean = when {
    a is DocNode.Widget && b is DocNode.Widget ->
      a.type == b.type && (a.type != "Condition" || condKey(a) == condKey(b))
    a is DocNode.RawCode && b is DocNode.RawCode -> true
    else -> false
  }

  /**
   * A Condition's (field, test, negate), normalized: an absent (or empty)
   * test is the Boolean one, an absent negate is false. A test name this
   * build does not know compares by its wire string — two different unknown
   * tests are different conditions.
   */
  private fun condKey(w: DocNode.Widget): Triple<String?, String?, Boolean> {
    fun s(k: String) = (w.props[k] as? PropValue.Lit)?.s
    val negate = (w.props["negate"] as? PropValue.Lit)?.b == true
    val test = s("test")
    return Triple(s("field"), dev.keliver.portal.CondTest.fromWire(test)?.name ?: "?$test", negate)
  }

  private fun isCondition(n: DocNode) = n is DocNode.Widget && n.type == "Condition"

  /**
   * K3, WITHOUT identities (no `base`, or a base that is not the file's tree):
   * a Condition whose CONDITION was edited matches no parsed `if` by
   * [sameShape]. Between two kept neighbours (a "gap"), when the unmatched
   * Conditions on both sides are equally many they are taken to be the same
   * nodes, in order — the edit stays a surgical condition rewrite. Any other
   * gap (a new Condition, or an insert and an edit together) leaves them
   * unmatched: inserts and deletes. Position is all this knows, so a delete
   * plus an unrelated insert in one gap pairs as an edit (the deleted `if`'s
   * comments stay, under the new condition), and an edit to a deleted
   * neighbour's condition keeps that neighbour's `if`. The text compiles and
   * reads back as the target either way; with identities (the relay always
   * passes its base) neither happens.
   */
  private fun pairEditedConditions(
    parsedChildren: List<DocNode>,
    targetChildren: List<DocNode>,
    consumed: BooleanArray,
    matchOf: Array<Int?>,
  ) {
    var ti = 0
    var pi = 0
    while (ti <= targetChildren.size) {
      // The gap ends at the next matched target (or the end); its parsed side ends at that match.
      val tEnd = (ti until targetChildren.size).firstOrNull { matchOf[it] != null } ?: targetChildren.size
      val pEnd = if (tEnd < targetChildren.size) matchOf[tEnd]!! else parsedChildren.size
      val ts = (ti until tEnd).filter { isCondition(targetChildren[it]) }
      val ps = (pi until pEnd).filter { !consumed[it] && isCondition(parsedChildren[it]) }
      if (ts.isNotEmpty() && ts.size == ps.size) {
        ts.zip(ps).forEach { (t, p) -> matchOf[t] = p; consumed[p] = true }
      }
      ti = tEnd + 1
      pi = pEnd + 1
    }
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
  ): KtExpression? = place(block, after, factory) { indent ->
    // P0 indent fix: the statement text is emitted re-indented to the destination depth.
    val emitted = NodeEmitter.statement(node, indent, components, parentType, bindings.get())
    need(emitted.imports)
    statementOf(factory, emitted.text)
  }

  /**
   * Adds the statement [make] builds (given its line's indent) to [block]:
   * after [after] — and after its same-line trailing comment — or, with no
   * [after], first: before the first statement's own leading comments (they
   * stay with it), or after the comments of a block that has only comments.
   * Every placement carries "\n" + the destination indent.
   */
  private fun place(
    block: KtBlockExpression,
    after: KtExpression?,
    factory: KtPsiFactory,
    make: (indent: String) -> KtExpression?,
  ): KtExpression? {
    if (after != null) {
      val indent = indentOf(after)
      val stmt = make(indent) ?: return null
      // Statement first, then the whitespace before it: in an `if` block the
      // whitespace before `}` is inside the block, and a new whitespace leaf
      // added next to it merges with it (the statement then landed after `}`).
      val inserted = block.addAfter(stmt, trailingComment(after) ?: after)
      block.addBefore(factory.createWhiteSpace("\n$indent"), inserted)
      return inserted as? KtExpression
    }
    val first = block.statements.firstOrNull()
    if (first != null) {
      val indent = indentOf(first)
      val stmt = make(indent) ?: return null
      val inserted = block.addBefore(stmt, leadingComments(first).firstOrNull() ?: first)
      block.addAfter(factory.createWhiteSpace("\n$indent"), inserted)
      return inserted as? KtExpression
    }
    val lastComment = generateSequence(block.lastChild) { it.prevSibling }
      .firstOrNull { it is org.jetbrains.kotlin.com.intellij.psi.PsiComment }
    if (lastComment != null) {
      val indent = indentOf(lastComment)
      val stmt = make(indent) ?: return null
      val inserted = block.addAfter(stmt, lastComment)
      block.addBefore(factory.createWhiteSpace("\n$indent"), inserted)
      return inserted as? KtExpression
    }
    // An inline lambda block starts at the `{`, so indentOf(block) is empty:
    // the text before it also contains the component call. Derive the closing
    // brace indentation from that owning call instead.
    val braceIndent = block.text.substringAfterLast('\n')
      .takeWhile { it == ' ' || it == '\t' }
      .takeIf { '\n' in block.text }
      ?: owningCall(block)?.let(::indentOf)
      ?: lineLeadingIndent(block)
    val indent = "$braceIndent  "
    val stmt = make(indent) ?: return null
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
    return inserted as? KtExpression
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
      NodeEmitter.statementText(target.copy(children = emptyList()), "", components, parentType, bindings.get()),
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
      val oldValue = parsed.props[name]
      val newValue = target.props[name]
      val text = (if (name in margins) {
        val sideKeys = dev.keliver.portal.MARGIN_SIDES.map { "$name.$it" }
        MarginSyntax.surgical(oldExpr.text, sideValues(parsed.props, sideKeys), sideValues(target.props, sideKeys), conds.get(), ::condText)
          ?: canonicalText
      } else if (oldValue is dev.keliver.portal.document.PropValue.Conditional &&
        newValue is dev.keliver.portal.document.PropValue.Conditional
      ) {
        // K3: only the changed part — the condition, or one branch.
        ConditionalSyntax.surgical(oldExpr.text, oldValue, newValue, canonicalText, ::condText) ?: canonicalText
      } else {
        // A Dp keeps the author's spelling (`16.dp` stays `.dp`, `Dp(16.0)` stays Dp()).
        val v = (newValue as? dev.keliver.portal.document.PropValue.Lit)?.d
        if (v != null && MarginSyntax.parseDp(oldExpr.text) != null) MarginSyntax.dpLike(oldExpr.text, v) else canonicalText
      }) ?: return false
      edits += oldExpr to text
      imports += FragmentImports.value(text)
      // K2: a token written as its reference needs its declaring object (structural).
      keys.forEach { imports += FragmentImports.token(target.props[it]) }
    }
    if (!sameModifiers(parsed.modifiers, target.modifiers)) {
      val oldExpr = sourceArg("modifier") ?: return false
      // K2: the canonical chain may reference a token the FILE cannot resolve yet
      // (its import is added below), so the target's own tokens resolve first.
      val tokens = targetTokens(target.modifiers.values, lookup.get())
      val text = ModifierChain.surgical(
        oldExpr.text, parsed.modifiers, target.modifiers, parentType, newByName["modifier"]?.text, tokens, conds.get(), ::condText,
      ) ?: return false
      edits += oldExpr to text
      imports += FragmentImports.chain(text, parentType, tokens, conds.get())
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

  /**
   * [text] as ONE statement (an expression or a declaration — RawCode may be
   * `val x = …`), or null when it is not exactly one statement with nothing
   * else (a trailing comment is not part of a statement).
   */
  internal fun statementOf(factory: KtPsiFactory, text: String): KtExpression? {
    val block = runCatching { factory.createBlock(text) }.getOrNull() ?: return null
    val stmt = block.statements.singleOrNull() ?: return null
    val others = generateSequence(block.lBrace?.nextSibling) { it.nextSibling }
      .takeWhile { it != block.rBrace }
      .filter { it != stmt && it !is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace }
    if (others.any()) return null
    if (stmt.text != text.trim()) return null
    return stmt
  }
}
