package dev.keliver.portal.ingest

import dev.keliver.portal.WidgetNode
import dev.keliver.portal.exportKotlin
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * P2b-a: contract write-back. An editor op that introduces a NEW bind or action
 * (e.g. inserting a Repeat over `b.items`) writes screen code referencing a
 * member the Bindings interface doesn't declare — and the guest compile goes
 * red until a human edits the interface AND the presenter. Instead, the write
 * pipeline calls [ensure] after every tree write:
 *
 *  - members the tree REQUIRES but the interface lacks are ADDED with a safe
 *    DEFAULT body (`get() = emptyList()`, `{}`) so presenters keep compiling
 *    and devices render the draft immediately; each carries a `TODO(portal)`
 *    marker so humans (and the publish verifier) can find unimplemented ones.
 *  - marker-carrying members the tree no longer requires are REMOVED (undo of
 *    a draft insert leaves no residue). Hand-written members — no marker —
 *    are NEVER touched: removing real contract is a human decision.
 *  - item interfaces required by Repeats are appended (marked); marked ones no
 *    longer required are removed; marked ones present RESYNC to the
 *    requirement (portal-owned until a human adopts them by removing the marker).
 *
 * The REQUIRED set comes from [exportKotlin] of the same tree — the single
 * source of truth for contract derivation — parsed back out of the export.
 * Implementation is offset-based string surgery from ONE parse (PSI tree
 * mutation proved fragile around brace anchors).
 */
object ContractWriteBack {
  const val MARKER = "TODO(portal): implement — auto-added for a portal binding"
  private val MARKER_DOC = "/** $MARKER. */"

  private data class Edit(val start: Int, val end: Int, val replacement: String)

  fun ensure(fileText: String, tree: WidgetNode, functionName: String): String {
    val exported = exportKotlin(tree, functionName = functionName)
    val exportFile = PsiEnv.parse("Exported.kt", exported)
    val exportIfaces = exportFile.declarations.filterIsInstance<KtClass>().filter { it.isInterface() }
    val requiredBindings = exportIfaces.firstOrNull { it.name == "${functionName}Bindings" }
    val requiredItems = exportIfaces.filter { it.name != "${functionName}Bindings" }

    val file = PsiEnv.parse("Screen.kt", fileText)
    val ifaces = file.declarations.filterIsInstance<KtClass>().filter { it.isInterface() }
    val bindings = ifaces.firstOrNull { it.name?.endsWith("Bindings") == true }
      ?: return fileText // no contract seam in this file — nothing to maintain

    val requiredMembers = LinkedHashMap<String, String>() // name -> defaulted decl text
    requiredBindings?.declarations?.forEach { d ->
      when (d) {
        // Dotted names would be item-scoped leakage — never interface members.
        is KtProperty -> d.name?.takeIf { '.' !in it }?.let { requiredMembers[it] = defaultedVal(d.text) }
        is KtNamedFunction -> d.name?.let { requiredMembers[it] = "${d.text.trim()} {}" }
        else -> {}
      }
    }

    val edits = mutableListOf<Edit>()

    // ── 1. Bindings interface: removals of marked+unrequired, additions of missing. ──
    val existingNames = bindings.declarations.mapNotNull { it.name }.toSet()
    bindings.declarations.forEach { d ->
      val name = d.name ?: return@forEach
      if (name !in requiredMembers && isMarked(d.text)) {
        edits += lineBlockRemoval(fileText, d.textRange.startOffset, d.textRange.endOffset)
      }
    }
    val missing = requiredMembers.filterKeys { it !in existingNames }
    if (missing.isNotEmpty()) {
      val rBrace = bindings.body?.rBrace ?: return fileText
      val insertAt = rBrace.textRange.startOffset
      val block = missing.values.joinToString("") { "  $MARKER_DOC\n  $it\n" }
      val prefix = if (insertAt > 0 && fileText[insertAt - 1] != '\n') "\n" else ""
      edits += Edit(insertAt, insertAt, prefix + block)
    }

    // ── 2. Item interfaces: remove/resync marked ones; append missing. ──
    val fileItemIfaces = ifaces.filter { it.name?.endsWith("Bindings") == false }
    fileItemIfaces.forEach { iface ->
      if (!markedIface(fileText, iface)) return@forEach
      val req = requiredItems.firstOrNull { it.name == iface.name }
      if (req == null) {
        edits += ifaceRemoval(fileText, iface)
      } else if (membersOf(iface) != membersOf(req)) {
        edits += Edit(iface.textRange.startOffset, iface.textRange.endOffset, req.text.trim())
      }
    }
    // Only append item interfaces referenced by members WE manage (just added,
    // or existing marker-carrying). Hand-written list members reference their
    // own hand-named item types (e.g. ProfileMenuEntry) — the exporter's
    // itemVar-derived name (Item/Qa) must not be duplicated alongside them.
    val managedDecls = missing.values +
      bindings.declarations.filter { it.name in requiredMembers && isMarked(it.text) }.map { it.text }
    val managedTypes = managedDecls
      .flatMap { decl -> Regex("[A-Z][A-Za-z0-9_]*").findAll(decl.substringAfter(':', "")).map { it.value } }
      .toSet()
    val presentNames = fileItemIfaces.mapNotNull { it.name }.toSet()
    val appendix = requiredItems.filter { it.name !in presentNames && it.name in managedTypes }
      .joinToString("") { "\n$MARKER_DOC\n${it.text.trim()}\n" }

    if (edits.isEmpty() && appendix.isEmpty()) return fileText

    var text = fileText
    edits.sortedByDescending { it.start }.forEach { e ->
      text = text.substring(0, e.start) + e.replacement + text.substring(e.end)
    }
    if (appendix.isNotEmpty()) text = text.trimEnd('\n') + "\n" + appendix
    // Byte-trust: whatever we did, the file keeps its ORIGINAL trailing-newline
    // run — otherwise insert+undo leaves EOF-whitespace diff noise.
    val trailing = fileText.takeLastWhile { it == '\n' }.ifEmpty { "\n" }
    return text.trimEnd('\n') + trailing
  }

  private fun isMarked(declText: String): Boolean = "TODO(portal)" in declText

  /** The marker doc may sit just above the iface without being PSI-attached. */
  private fun markedIface(fileText: String, iface: KtClass): Boolean {
    if (isMarked(iface.text)) return true
    val before = fileText.substring(maxOf(0, iface.textRange.startOffset - 200), iface.textRange.startOffset)
    return "TODO(portal)" in before.substringAfterLast("}")
  }

  /** Remove a declaration plus its whole line block (leading indent + trailing newline). */
  private fun lineBlockRemoval(text: String, start: Int, end: Int): Edit {
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    var e = end
    if (e < text.length && text[e] == '\n') e++
    return Edit(lineStart, e, "")
  }

  /** Remove an interface plus its preceding marker doc line and blank separator. */
  private fun ifaceRemoval(text: String, iface: KtClass): Edit {
    var start = text.lastIndexOf('\n', iface.textRange.startOffset - 1) + 1
    // swallow the marker doc line above, if present
    val prevLineEnd = start - 1
    if (prevLineEnd > 0) {
      val prevLineStart = text.lastIndexOf('\n', prevLineEnd - 1) + 1
      if ("TODO(portal)" in text.substring(prevLineStart, prevLineEnd)) start = prevLineStart
    }
    // swallow one preceding blank line
    if (start >= 1 && text[start - 1] == '\n' && (start < 2 || text[start - 2] == '\n')) start -= 1
    var end = iface.textRange.endOffset
    if (end < text.length && text[end] == '\n') end++
    return Edit(start, end, "")
  }

  private fun membersOf(iface: KtClass): List<String> =
    iface.declarations.map { it.text.trim() }.sorted()

  /** `val items: List<Item>` → `val items: List<Item> get() = emptyList()` (safe default). */
  private fun defaultedVal(decl: String): String {
    val type = decl.substringAfter(':', "").trim()
    val default = when {
      type == "String" -> "\"\""
      type == "Int" -> "0"
      type == "Double" -> "0.0"
      type == "Float" -> "0f"
      type == "Boolean" -> "false"
      type.startsWith("List<") -> "emptyList()"
      else -> null
    }
    return if (default == null) decl.trim() else "${decl.trim()} get() = $default"
  }
}
