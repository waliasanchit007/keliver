package dev.keliver.portal.ingest

import dev.keliver.portal.WidgetNode
import dev.keliver.portal.exportKotlin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

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
 *
 * U37: a member is declared only where that cannot redeclare an inherited
 * one. A member that a supertype declared in the same file has counts as
 * declared (it used to be declared again, without `override`, which does not
 * compile). When the interface extends a type declared in another file, a
 * member it does not declare in the file may be inherited, with any type, so
 * nothing is added to it at all (as nothing ever was to an interface not in
 * the file); [unvouched] is the writer's refusal of a member such a screen
 * would read that the file neither declares nor reads.
 */
object ContractWriteBack {
  const val MARKER = "TODO(portal): implement — auto-added for a portal binding"
  private val MARKER_DOC = "/** $MARKER. */"

  /**
   * Publish-safety gate: the contract member declarations still carrying a
   * [MARKER] doc — portal bindings the presenter never implemented (they render
   * their defaulted no-op). Returns the member decls (e.g.
   * "val items: List<Item> get() = emptyList()") so the relay can reject a
   * publish and name exactly what to implement. Empty = safe to ship.
   */
  fun unimplementedMembers(src: String): List<String> {
    val lines = src.lines()
    return lines.indices
      .filter { "TODO(portal)" in lines[it] }
      .mapNotNull { i -> lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.trim() }
  }

  private data class Edit(val start: Int, val end: Int, val replacement: String)

  fun ensure(
    fileText: String,
    tree: WidgetNode,
    functionName: String,
    components: dev.keliver.portal.ComponentRegistry = dev.keliver.portal.EmptyComponentRegistry,
  ): String {
    // C2 gap found live (P3-12 build loop): surgical inserts of COMPONENT
    // instances emit the call but not its import — ensure them here (adds
    // only; removing unused imports is a human decision).
    val withImports = ensureComponentImports(fileText, tree, components)
    val exported = exportKotlin(tree, functionName = functionName, components = components)
    return ensureContract(withImports, exported, functionName)
  }

  /** Insert `import <pkg>.<Name>` for every component instance in [tree] the file lacks. */
  private fun ensureComponentImports(
    fileText: String,
    tree: WidgetNode,
    components: dev.keliver.portal.ComponentRegistry,
  ): String {
    val used = LinkedHashSet<String>()
    fun walk(n: WidgetNode) {
      if (components.isComponent(n.type)) used += n.type
      n.children.forEach(::walk)
    }
    walk(tree)
    if (used.isEmpty()) return fileText
    val filePkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
      .find(fileText)?.groupValues?.get(1)
    val needed = used.mapNotNull { name ->
      val pkg = components.spec(name)?.packageName ?: return@mapNotNull null
      if (pkg == filePkg) null else "import $pkg.$name"
    }.filter { it !in fileText }
    if (needed.isEmpty()) return fileText
    val lines = fileText.lines().toMutableList()
    // After the last existing import, else after the package line, else at top.
    val lastImport = lines.indexOfLast { it.startsWith("import ") }
    val insertAt = when {
      lastImport >= 0 -> lastImport + 1
      else -> lines.indexOfFirst { it.startsWith("package ") }.let { if (it >= 0) it + 1 else 0 }
    }
    lines.addAll(insertAt, needed)
    return lines.joinToString("\n")
  }

  /**
   * U37: why the screen [tree] reads cannot be written onto [file] (the file
   * as it is on disk, before the change) — null when it can. When the
   * interface the screen takes is not read whole in its file (it is not in
   * the file, or extends a type declared in another file: the contract's
   * `unlistedFieldsUnknown`), a field or action the tree reads that the file
   * neither declares (in that interface or a supertype in the file) nor
   * already reads through the bindings parameter may be inherited or may be
   * new; the editor cannot tell which, so it writes neither a declaration
   * (it could redeclare an inherited member) nor a bare reference (it may not
   * compile). [readText] is a text of the file the relay last READ (not
   * wrote): what it read is vouched for too — the file read it then, so an
   * undo that brings a deleted read back is written.
   *
   * A screen with no bindings parameter is left to the writer's own refusal.
   */
  internal fun unvouched(
    file: KtFile,
    tree: WidgetNode,
    functionName: String,
    components: dev.keliver.portal.ComponentRegistry,
    readText: String?,
  ): String? {
    val fn = screenFunction(file) ?: return null
    if (fn.valueParameters.isEmpty()) return null
    val target = screenBindings(fn, file)
    val decls = typeDecls(file)
    val why = unlistedFieldsWhy(target, target.iface?.let { memberTypes(it, decls) }) ?: return null
    val required = requiredBindingsOf(exportKotlin(tree, functionName = functionName, components = components), functionName)
      ?.declarations?.mapNotNull { d -> d.name?.takeIf { (d is KtProperty || d is KtNamedFunction) && '.' !in it } }
      .orEmpty()
    if (required.isEmpty()) return null
    val declared = target.iface?.let { declaredNames(it, decls) }.orEmpty()
    val read = membersRead(file) + readText?.let { runCatching { membersRead(PsiEnv.parse("Read.kt", LineEndings.toLf(it))) }.getOrNull() }.orEmpty()
    val unknown = required.filter { it !in declared && it !in read }.distinct()
    if (unknown.isEmpty()) return null
    val names = unknown.joinToString { "`$it`" }
    val them = if (unknown.size == 1) "it exists" else "they exist"
    val redeclare = target.iface?.let { ", and declaring ${if (unknown.size == 1) "it" else "them"} in `${it.name}` could redeclare an inherited member" }.orEmpty()
    return "the screen would read $names, which this file neither declares nor reads yet, so the editor cannot tell whether $them: $why$redeclare"
  }

  /** The names [file]'s screen function reads through its bindings parameter (`b.x`, `b.go()`, `b::go`). */
  internal fun membersRead(file: KtFile): Set<String> {
    val fn = screenFunction(file) ?: return emptySet()
    val param = fn.valueParameters.firstOrNull()?.name ?: return emptySet()
    fun isParam(e: org.jetbrains.kotlin.psi.KtExpression?) = (e as? KtNameReferenceExpression)?.getReferencedName() == param
    val out = LinkedHashSet<String>()
    fn.accept(object : KtTreeVisitorVoid() {
      override fun visitQualifiedExpression(expression: KtQualifiedExpression) {
        super.visitQualifiedExpression(expression)
        if (!isParam(expression.receiverExpression)) return
        when (val s = expression.selectorExpression) {
          is KtNameReferenceExpression -> out += s.getReferencedName()
          is KtCallExpression -> (s.calleeExpression as? KtNameReferenceExpression)?.let { out += it.getReferencedName() }
          else -> {}
        }
      }

      override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)
        if (isParam(expression.receiverExpression)) out += expression.callableReference.getReferencedName()
      }
    })
    return out
  }

  /** The names [cls] declares — properties and functions — with those of its supertypes declared in the same file ([decls]). */
  private fun declaredNames(cls: KtClass, decls: Map<String, KtClass>, seen: MutableSet<KtClass> = HashSet()): Set<String> {
    if (!seen.add(cls)) return emptySet()
    val out = LinkedHashSet<String>()
    cls.primaryConstructorParameters.filter { it.hasValOrVar() }.mapNotNullTo(out) { it.name }
    cls.declarations.filter { it is KtProperty || it is KtNamedFunction }.mapNotNullTo(out) { it.name }
    cls.superTypeListEntries.forEach { e ->
      e.typeReference?.text?.substringBefore('<')?.trim()?.let(decls::get)?.let { out += declaredNames(it, decls, seen) }
    }
    return out
  }

  private fun requiredBindingsOf(exported: String, functionName: String): KtClass? =
    PsiEnv.parse("Exported.kt", exported).declarations.filterIsInstance<KtClass>()
      .firstOrNull { it.isInterface() && it.name == "${functionName}Bindings" }

  private fun ensureContract(fileText: String, exported: String, functionName: String): String {
    val exportFile = PsiEnv.parse("Exported.kt", exported)
    val exportIfaces = exportFile.declarations.filterIsInstance<KtClass>().filter { it.isInterface() }
    val requiredBindings = exportIfaces.firstOrNull { it.name == "${functionName}Bindings" }
    val requiredItems = exportIfaces.filter { it.name != "${functionName}Bindings" }

    val file = PsiEnv.parse("Screen.kt", fileText)
    val ifaces = file.declarations.filterIsInstance<KtClass>().filter { it.isInterface() }
    // K4: the interface the screen TAKES (as the recognizer reads it), not the first in the file.
    val bindings = screenFunction(file)?.let { screenInterface(it, file) }
      ?: return fileText // no contract seam in this file — nothing to maintain
    // U37: what the interface already has — its own members and those of its
    // supertypes in this file (declaring one of those again, without
    // `override`, does not compile) — and whether that is all of it.
    val decls = typeDecls(file)
    val declared = declaredNames(bindings, decls)
    val readWhole = memberTypes(bindings, decls).complete

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
    bindings.declarations.forEach { d ->
      val name = d.name ?: return@forEach
      if (name !in requiredMembers && isMarked(d.text)) {
        edits += lineBlockRemoval(fileText, d.textRange.startOffset, d.textRange.endOffset)
      }
    }
    // U37: an interface extending a type declared in another file gains nothing — a
    // member it does not declare here may be inherited (the writer refuses one the file
    // does not read: [unvouched]).
    val missing = if (!readWhole) emptyMap() else requiredMembers.filterKeys { it !in declared }
    if (missing.isNotEmpty()) {
      val rBrace = bindings.body?.rBrace ?: return fileText
      val insertAt = rBrace.textRange.startOffset
      val block = missing.values.joinToString("") { "  $MARKER_DOC\n  $it\n" }
      val prefix = if (insertAt > 0 && fileText[insertAt - 1] != '\n') "\n" else ""
      edits += Edit(insertAt, insertAt, prefix + block)
    }

    // ── 2. Item interfaces: remove/resync marked ones; append missing. ──
    val fileItemIfaces = ifaces.filter { it !== bindings && it.name?.endsWith("Bindings") == false }
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
