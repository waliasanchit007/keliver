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
  ): String = statement(node, indent, components, parentType).text

  /** A statement's text plus the imports it needs — taken from the exporter's structural set. */
  data class Emitted(val text: String, val imports: Set<String>)

  fun statement(
    node: DocNode,
    indent: String,
    components: ComponentRegistry = EmptyComponentRegistry,
    parentType: String? = null,
  ): Emitted {
    val (lifted, imports) = lift(node, components, parentType)
    val (text, srcIndent) = lifted
    return Emitted(reindent(text, srcIndent, indent), imports)
  }

  private fun lift(node: DocNode, components: ComponentRegistry, parentType: String?): Pair<Pair<String, String>, Set<String>> {
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
    return (stmt.text to srcIndent) to imports
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
