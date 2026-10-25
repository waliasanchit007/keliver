package dev.keliver.portal.ingest

import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.mapOperandTree
import dev.keliver.portal.document.mapOperands
import dev.keliver.portal.document.toWidgetTree

/**
 * What writing a screen document to its `.kt` file does — the write-back
 * guarantee (K3 last review round): write-back never silently destroys
 * author code.
 *
 *  1. The change is made IN PLACE when it can be ([WriteBack.attempt]: only
 *     the changed statements and arguments, checked to read back as the
 *     document).
 *  2. Otherwise the whole file is rewritten from the document (the full
 *     export) ONLY when that is lossless: the file as it is must be exactly
 *     the export of its own tree (a trailing newline aside) — so it holds no
 *     comment, helper, RawCode, parameter name or formatting the exporter
 *     would drop — and the export must itself read back as the document.
 *  3. Otherwise the change is REFUSED: nothing is written, the file stays as
 *     it is, and [Refuse.reason] says why (the relay surfaces it).
 *
 * Every text written — after the Bindings interface pass — reads back as
 * the document. The file is written only when it is still what the relay
 * last wrote or read ([Reread] otherwise). A new file (nothing on disk) is
 * the export (RawCode as its own text). Line endings: a CRLF file stays
 * CRLF; a file that mixes endings keeps every line ending the change does not
 * touch, and a change that would rewrite a CRLF line of such a file is
 * refused ([LineEndings.restore]).
 */
object ScreenWriter {
  sealed interface Plan

  /** Write [text]; [how] it was made. */
  data class Write(val text: String, val how: How) : Plan

  /** Write nothing: the change cannot be written without losing code, because [reason]. */
  data class Refuse(val reason: String) : Plan

  /**
   * Write nothing: the file on disk is not the document the relay last wrote
   * it from or read it as (the base) — it changed since, or was never read.
   * Merging onto it would pair statements by position and could delete the
   * author's code; the caller re-reads the file instead ([reason] says why).
   */
  data class Reread(val reason: String) : Plan

  enum class How { SURGICAL, EXPORT, CREATED }

  /** Test seam: rewrites a text after the Bindings pass (forces a divergence). Per thread. */
  internal val afterContractForTest = ThreadLocal<((String) -> String)?>()

  private val PKG_RE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

  fun plan(
    /** The file's text as it is on disk; null = there is no file yet. */
    onDisk: String?,
    doc: UiDocument,
    /** The document the file was last written from or ingested as (identities; see [WriteBack.attempt]). */
    base: UiDocument?,
    /**
     * The exact text [base] was read from or written as (null = unknown). A
     * file that is still this text did not change, however it reads now —
     * a component or design token it uses may have changed since (S-c).
     */
    baseText: String? = null,
    components: ComponentRegistry = EmptyComponentRegistry,
    tokens: TokenTable = TokenTable.EMPTY,
    fileName: String,
    functionName: String,
    /** The package a NEW file declares (an existing file keeps its own). */
    packageName: String?,
    /**
     * U37: the text the file was last READ as (not one the relay wrote; null =
     * unknown). On a screen whose Bindings interface is not read whole in its
     * file, a field or action this text reads is vouched for, as one the file
     * on disk reads is ([ContractWriteBack.unvouched]) — so an undo that
     * brings back a read the last write deleted is written.
     */
    readText: String? = null,
    /**
     * E14: the file is a component's and the document is its body
     * ([Recognizer.recognize] `asComponent`). Written only surgically: a
     * component's signature is its contract with every screen that calls it,
     * so there is no Bindings pass, no full export and no new file.
     */
    asComponent: Boolean = false,
  ): Plan = planFor(
    onDisk,
    // The document as the CURRENT token table values it: a value that changed
    // since it was read or chosen is the one the file now reads as (S-c).
    withCurrentTokens(doc, tokens, fileName),
    base, baseText, components, tokens, fileName, functionName, packageName, readText, asComponent,
  )

  private fun planFor(
    onDisk: String?,
    doc: UiDocument,
    base: UiDocument?,
    baseText: String?,
    components: ComponentRegistry,
    tokens: TokenTable,
    fileName: String,
    functionName: String,
    packageName: String?,
    readText: String?,
    asComponent: Boolean,
  ): Plan {
    val tree = doc.toWidgetTree()
    val what = if (asComponent) "component" else "screen"
    // Every text written is read back — after the Bindings pass — as the document.
    fun readsBack(text: String): String? {
      val back = runCatching { Recognizer.recognize(fileName, text, components, tokens, asComponent) }.getOrNull()
        ?: return "it does not read as a $what"
      return if (WriteBack.sameTree(back.root, doc.root)) null
      else WriteBack.firstDifference(back.root, doc.root) ?: "it reads back differently"
    }
    if (onDisk == null) {
      if (asComponent) return Refuse("the component's file is gone")
      // H12: a new file is written as an author would — `Column {`, not `Column(\n) {}`.
      val text = export(doc, packageName, functionName, components, compact = true)
      readsBack(text)?.let { return Refuse("a new file written from the document would not read back as it: $it") }
      return Write(text, How.CREATED)
    }
    // Everything below works on LF text; the file keeps its own line endings.
    val lf = LineEndings.toLf(onDisk)
    fun restored(text: String, how: How): Plan = LineEndings.restore(onDisk, text)?.let { Write(it, how) }
      ?: Refuse("the file mixes CRLF and LF line endings and the change would rewrite a CRLF line")

    val asIs = runCatching { Recognizer.recognize(fileName, lf, components, tokens, asComponent) }.getOrNull()
      ?: return Refuse("the file has no @Composable $what function the editor can read, so it is not rewritten")
    // B1: write only onto the file the relay last wrote or read. A file that
    // changed since (an IDE save, an IDE undo of an edit that was read) pairs
    // with the document only by position — which deletes whatever the
    // document does not hold — so it is re-read, never merged onto.
    if (base == null) return Reread("the file has not been read since it was created or changed")
    // S-c: the file is compared as TEXT with what the base was read from or
    // written as. The same text is the same file: if it reads differently now,
    // a component or token it uses changed — the base is re-read from it under
    // the current tables (same shape, same handles). Another text is an
    // external change unless it still reads as the base (a comment-only edit).
    val fileBase = if (baseText != null && LineEndings.toLf(baseText) == lf) {
      if (WriteBack.sameTree(asIs.root, base.root)) base
      else rehandled(asIs.root, base.root)?.let { base.copy(root = it) }
        ?: return Reread("a component or design token the $what uses changed since it was last read")
    } else {
      if (!WriteBack.sameTree(asIs.root, base.root)) return Reread("the file changed since it was last read")
      base
    }
    // S5 (the relay refuses such a move as an op; this holds for any other way here, e.g. an undo).
    DeclarationScope.problem(fileBase, doc)?.let { return Refuse(it) }
    if (asComponent) {
      if (doc.contract != asIs.contract) return Refuse("a component's parameters are changed in code — every screen that calls it passes them")
      // Its `content()` call is where every caller's content goes: removing or repeating it is a change to the contract.
      if (slotCount(doc.root) != slotCount(asIs.root)) {
        return Refuse("a component's content slot (where its callers' content goes) is added or removed in code")
      }
      return when (val surgical = WriteBack.attempt(lf, doc, components, tokens, fileName, fileBase, asComponent = true)) {
        is WriteBack.Outcome.Merged -> readsBack(surgical.text)?.let { Refuse("the edited component would not read back as the document: $it") }
          ?: restored(surgical.text, How.SURGICAL)
        is WriteBack.Outcome.NotSurgical -> Refuse(surgical.reason)
      }
    }
    // U37: a member the screen would read that its file cannot vouch for — the interface
    // is not read whole (declared elsewhere, or extending a type declared elsewhere) and
    // the file neither declares nor reads it — is neither declared (it could redeclare an
    // inherited member) nor written as a bare reference (it may not compile).
    asIs.file?.let { ContractWriteBack.unvouched(it, tree, functionName, components, readText) }?.let { return Refuse(it) }

    val surgical = WriteBack.attempt(lf, doc, components, tokens, fileName, fileBase)
    if (surgical is WriteBack.Outcome.Merged) {
      val text = contract(surgical.text, tree, functionName, components)
      readsBack(text)?.let { return Refuse("the file with its Bindings interface updated would not read back as the document: $it") }
      return restored(text, How.SURGICAL)
    }
    val why = (surgical as WriteBack.Outcome.NotSurgical).reason

    // The full export only when it is lossless: the file is the export of its own tree.
    val pkg = PKG_RE.find(lf)?.groupValues?.get(1) ?: packageName
    val asIsDoc = UiDocument(doc.screen, asIs.root, asIs.contract, 0, 0)
    fun exportsAs(compact: Boolean) = export(asIsDoc, pkg, functionName, components, compact).trimEnd('\n') == lf.trimEnd('\n')
    // H12: a file the editor created is the export with its empty calls compacted — and stays so
    // (review 6: compact first — a file with no empty call yet is both, and stays compact).
    val compact = when {
      exportsAs(compact = true) -> true
      exportsAs(compact = false) -> false
      else -> return Refuse(why)
    }
    val text = export(doc, pkg, functionName, components, compact)
    readsBack(text)?.let { return Refuse("$why, and the full export would not read back as the document either: $it") }
    return restored(text, How.EXPORT)
  }

  private fun slotCount(n: DocNode): Int =
    if (n !is DocNode.Widget) 0 else (if (n.type == "Slot") 1 else 0) + n.children.sumOf(::slotCount)

  /** [fresh] (the file as it reads now) with [old]'s handles, node for node — null when their shapes differ. */
  private fun rehandled(fresh: DocNode, old: DocNode): DocNode? = when {
    fresh is DocNode.RawCode && old is DocNode.RawCode -> fresh.copy(handle = old.handle)
    fresh is DocNode.Widget && old is DocNode.Widget && fresh.type == old.type && fresh.children.size == old.children.size ->
      fresh.copy(handle = old.handle, children = fresh.children.indices.map { rehandled(fresh.children[it], old.children[it]) ?: return null })
    else -> null
  }

  /** [doc] with every design token valued as [tokens] values it now (one it no longer offers is left as it is). */
  private fun withCurrentTokens(doc: UiDocument, tokens: TokenTable, fileName: String): UiDocument {
    fun value(v: PropValue): PropValue = when (v) {
      is PropValue.Token -> tokens.usable(v.ref, v.import, fileName)
        ?.let { e -> runCatching { lit(e.value) }.getOrNull() }
        ?.let { v.copy(value = it) } ?: v
      is PropValue.Conditional -> v.mapOperands(::value).copy(whenTrue = v.whenTrue?.let(::value), whenFalse = v.whenFalse?.let(::value))
      is PropValue.Template -> v.copy(parts = v.parts.map(::value)) // K4: token parts / elements too
      is PropValue.ListOf -> v.copy(items = v.items.map(::value))
      // K7: an arithmetic value's token operands (the tree's shape kept).
      is PropValue.Arith -> v.mapOperandTree(::value)
      is PropValue.IntDp -> v.copy(value = if (v.value is PropValue.Arith) (v.value as PropValue.Arith).mapOperandTree(::value) else value(v.value))
      else -> v
    }
    fun node(n: DocNode): DocNode = when (n) {
      is DocNode.RawCode -> n
      is DocNode.Widget -> n.copy(
        props = n.props.mapValues { (_, v) -> value(v) },
        modifiers = n.modifiers.mapValues { (_, v) -> value(v) },
        children = n.children.map(::node),
      )
    }
    val root = node(doc.root)
    return if (root == doc.root) doc else doc.copy(root = root)
  }

  /**
   * The full export of [doc] as a screen file (package line, Bindings
   * interface kept in sync; RawCode as its own text — [NodeEmitter.screen]).
   */
  private fun export(doc: UiDocument, pkg: String?, functionName: String, components: ComponentRegistry, compact: Boolean = false): String {
    val header = pkg?.let { "package $it\n\n" } ?: ""
    return contract(header + NodeEmitter.screen(doc, functionName, components, compact), doc.toWidgetTree(), functionName, components)
  }

  /**
   * P2b-a: keep the Bindings interface in sync with the tree — new binds gain
   * defaulted, TODO(portal)-marked members so the guest keeps compiling;
   * marker-carrying members that ops un-required are removed.
   */
  private fun contract(text: String, tree: WidgetNode, functionName: String, components: ComponentRegistry): String =
    runCatching { ContractWriteBack.ensure(text, tree, functionName, components) }.getOrDefault(text)
      .let { afterContractForTest.get()?.invoke(it) ?: it }
}
