package dev.keliver.portal.ingest

import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.exportKotlin

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
 * A new file (nothing on disk) is the export. Line endings: a CRLF file stays
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

  private val PKG_RE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

  fun plan(
    /** The file's text as it is on disk; null = there is no file yet. */
    onDisk: String?,
    doc: UiDocument,
    /** The document the file was last written from or ingested as (identities; see [WriteBack.attempt]). */
    base: UiDocument?,
    components: ComponentRegistry = EmptyComponentRegistry,
    tokens: TokenTable = TokenTable.EMPTY,
    fileName: String,
    functionName: String,
    /** The package a NEW file declares (an existing file keeps its own). */
    packageName: String?,
  ): Plan {
    val tree = doc.toWidgetTree()
    if (onDisk == null) return Write(export(tree, packageName, functionName, components), How.CREATED)
    // Everything below works on LF text; the file keeps its own line endings.
    val lf = LineEndings.toLf(onDisk)
    fun restored(text: String, how: How): Plan = LineEndings.restore(onDisk, text)?.let { Write(it, how) }
      ?: Refuse("the file mixes CRLF and LF line endings and the change would rewrite a CRLF line")

    val asIs = runCatching { Recognizer.recognize(fileName, lf, components, tokens) }.getOrNull()
      ?: return Refuse("the file has no @Composable screen function the editor can read, so it is not rewritten")
    // B1: write only onto the file the relay last wrote or read. A file that
    // changed since (an IDE save, an IDE undo of an edit that was read) pairs
    // with the document only by position — which deletes whatever the
    // document does not hold — so it is re-read, never merged onto.
    if (base == null) return Reread("the file has not been read since it was created or changed")
    if (!WriteBack.sameTree(asIs.root, base.root)) return Reread("the file changed since it was last read")

    val surgical = WriteBack.attempt(lf, doc, components, tokens, fileName, base)
    if (surgical is WriteBack.Outcome.Merged) return restored(contract(surgical.text, tree, functionName, components), How.SURGICAL)
    val why = (surgical as WriteBack.Outcome.NotSurgical).reason

    // The full export only when it is lossless: the file is the export of its own tree.
    val pkg = PKG_RE.find(lf)?.groupValues?.get(1) ?: packageName
    val asIsExport = export(UiDocument(doc.screen, asIs.root, asIs.contract, 0, 0).toWidgetTree(), pkg, functionName, components)
    if (asIsExport.trimEnd('\n') != lf.trimEnd('\n')) return Refuse(why)
    val text = export(tree, pkg, functionName, components)
    val back = runCatching { Recognizer.recognize(fileName, text, components, tokens) }.getOrNull()
    if (back == null || !WriteBack.sameTree(back.root, doc.root)) {
      val diff = back?.let { WriteBack.firstDifference(it.root, doc.root) } ?: "it does not read as a screen"
      return Refuse("$why, and the full export would not read back as the document either: $diff")
    }
    return restored(text, How.EXPORT)
  }

  /** The full export of [tree] as a screen file (package line, Bindings interface kept in sync). */
  private fun export(tree: WidgetNode, pkg: String?, functionName: String, components: ComponentRegistry): String {
    val header = pkg?.let { "package $it\n\n" } ?: ""
    return contract(header + exportKotlin(tree, functionName = functionName, components = components), tree, functionName, components)
  }

  /**
   * P2b-a: keep the Bindings interface in sync with the tree — new binds gain
   * defaulted, TODO(portal)-marked members so the guest keeps compiling;
   * marker-carrying members that ops un-required are removed.
   */
  private fun contract(text: String, tree: WidgetNode, functionName: String, components: ComponentRegistry): String =
    runCatching { ContractWriteBack.ensure(text, tree, functionName, components) }.getOrDefault(text)
}
