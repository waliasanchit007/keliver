package dev.keliver.portal.ingest

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.document.Handle
import dev.keliver.portal.modifierSpecs
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Renders a single DocNode to canonical Kotlin using the SAME formatter as the
 * exporter (so surgical write-back stays byte-identical to a fresh export).
 * Trick: export a one-node throwaway screen, then lift the statement text out.
 *
 * Emission is TEXT at a caller-supplied indent: the exporter formats at its own
 * base depth, and splicing that verbatim into a deeper call site was the P0
 * write-back indent bug — every line after the first must be shifted from the
 * source depth to the destination depth before the PSI splice.
 *
 * Two things the exporter does not know about the FILE are put right here:
 * the screen's bindings parameter is spelled as the file spells it
 * ([statement]'s `bindingsParam`; the exporter always writes `b`), and a
 * RawCode node — on its own or inside the emitted subtree — is written as its
 * own source text (the exporter writes a `RawCode(text = …)` placeholder
 * call, which does not compile).
 */
object NodeEmitter {
  /**
   * The whole statement re-indented so line 1 splices at [indent] depth.
   * [parentType] is the widget whose content lambda receives it (Condition/
   * Repeat looked through): the exporter writes a scoped layout modifier only
   * under a parent that is one of its scopes, so the node is exported inside a
   * throwaway parent of that type.
   */
  fun statementText(
    node: DocNode,
    indent: String,
    components: ComponentRegistry = EmptyComponentRegistry,
    parentType: String? = null,
    bindingsParam: String? = null,
  ): String = statement(node, indent, components, parentType, bindingsParam).text

  /** A statement's text plus the imports it needs — taken from the exporter's structural set. */
  data class Emitted(val text: String, val imports: Set<String>)

  fun statement(
    node: DocNode,
    indent: String,
    components: ComponentRegistry = EmptyComponentRegistry,
    parentType: String? = null,
    /** The file's bindings parameter (null or `b` = as the exporter writes it). */
    bindingsParam: String? = null,
  ): Emitted {
    // A RawCode statement is its own text: nothing to export, nothing to import.
    if (node is DocNode.RawCode) return Emitted(node.text, emptySet())
    val raws = mutableListOf<String>()
    val marked = markRaw(node, raws)
    val (lifted, imports) = lift(marked, components, parentType, bindingsParam)
    val (text, srcIndent) = lifted
    var out = reindent(text, srcIndent, indent)
    // After the re-indent: a RawCode's own lines are its source, never shifted.
    raws.forEachIndexed { i, raw -> out = out.replace("${marker(i)}()", raw) }
    return Emitted(out, imports)
  }

  /**
   * The full export of [doc] as a screen function — each RawCode in it
   * written as its own source text, not the exporter's `RawCode(text = …)`
   * placeholder (which does not compile, and does not read back as code).
   */
  fun screen(doc: UiDocument, functionName: String, components: ComponentRegistry = EmptyComponentRegistry): String {
    val raws = mutableListOf<String>()
    val marked = doc.copy(root = markRaw(doc.root, raws))
    var out = exportKotlin(marked.toWidgetTree(), functionName = functionName, components = components)
    raws.forEachIndexed { i, raw -> out = out.replace("${marker(i)}()", raw) }
    return out
  }

  private fun marker(i: Int) = "__KeliverRawCode${i}__"

  /** [n] with each RawCode in it replaced by a marker call (`__KeliverRawCode0__()`); [raws] collects their texts. */
  private fun markRaw(n: DocNode, raws: MutableList<String>): DocNode = when (n) {
    is DocNode.RawCode -> DocNode.Widget(n.handle, marker(raws.size).also { raws += n.text })
    is DocNode.Widget -> if (n.children.isEmpty()) n else n.copy(children = n.children.map { markRaw(it, raws) })
  }

  private fun lift(node: DocNode, components: ComponentRegistry, parentType: String?, bindingsParam: String?): Pair<Pair<String, String>, Set<String>> {
    val givesScope = parentType != null && modifierSpecs.any { parentType in it.scopes }
    val root = if (givesScope) DocNode.Widget(Handle(0), parentType!!, children = listOf(node)) else node
    val throwaway = UiDocument("_", root, Contract(), version = 0, nextHandle = 0)
    val exported = exportKotlin(throwaway.toWidgetTree(), functionName = "Tmp", components = components)
    val file = PsiEnv.parse("Tmp.kt", exported)
    val fn = file.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "Tmp" }
    val top = (fn.bodyExpression as KtBlockExpression).statements.first()
    val stmt = if (!givesScope) top else {
      ((top as KtCallExpression).lambdaArguments.first().getLambdaExpression()!!.bodyExpression as KtBlockExpression)
        .statements.first()
    }
    val off = stmt.textRange.startOffset
    val lineStart = exported.lastIndexOf('\n', off - 1) + 1
    val srcIndent = exported.substring(lineStart, off).takeIf { it.isBlank() } ?: ""
    // The throwaway's imports, minus @Composable and the wrapper parent's own
    // call (unless the node itself uses that widget).
    val imports = file.importDirectives.mapNotNull { it.importPath?.pathStr }
      .filter { it != "androidx.compose.runtime.Composable" }
      .filter { !givesScope || it.substringAfterLast('.') != parentType || usesType(node, parentType!!) }
      .toSet()
    return (renameBindings(stmt, bindingsParam) to srcIndent) to imports
  }

  /**
   * [stmt]'s text with the exporter's bindings parameter `b` spelled [param]:
   * each `b` that is the receiver of `b.x` or `b::x` and not a lambda
   * parameter named `b` (a Repeat item) in scope.
   */
  private fun renameBindings(stmt: KtExpression, param: String?): String {
    val text = stmt.text
    if (param == null || param == "b") return text
    val base = stmt.textRange.startOffset
    val refs = PsiTreeUtil.collectElementsOfType(stmt, KtNameReferenceExpression::class.java).filter { ref ->
      ref.getReferencedName() == "b" && when (val p = ref.parent) {
        is KtDotQualifiedExpression -> p.receiverExpression == ref
        is KtCallableReferenceExpression -> p.receiverExpression == ref
        else -> false
      } && !shadowed(ref, stmt)
    }
    val out = StringBuilder(text)
    for (ref in refs.sortedByDescending { it.textRange.startOffset }) {
      val at = ref.textRange.startOffset - base
      out.replace(at, at + 1, param)
    }
    return out.toString()
  }

  /** Whether a lambda between [ref] and [top] declares a parameter `b`. */
  private fun shadowed(ref: KtExpression, top: KtExpression): Boolean {
    var p = ref.parent
    while (p != null && p != top.parent) {
      if (p is KtFunctionLiteral && p.valueParameters.any { it.name == "b" }) return true
      p = p.parent
    }
    return false
  }

  private fun usesType(n: DocNode, type: String): Boolean =
    n is DocNode.Widget && (n.type == type || n.children.any { usesType(it, type) })

  private fun reindent(text: String, src: String, dest: String): String =
    text.lines().mapIndexed { i, line ->
      when {
        i == 0 -> line // spliced at the destination column already
        line.isBlank() -> line
        line.startsWith(src) -> dest + line.removePrefix(src)
        else -> line
      }
    }.joinToString("\n")
}
