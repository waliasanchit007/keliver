package dev.keliver.portal.ingest

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.document.toWidgetTree
import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.exportKotlin
import org.jetbrains.kotlin.psi.KtBlockExpression
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
  /** The whole statement re-indented so line 1 splices at [indent] depth. */
  fun statementText(
    node: DocNode,
    indent: String,
    components: ComponentRegistry = EmptyComponentRegistry,
  ): String {
    val (text, srcIndent) = lift(node, components)
    return reindent(text, srcIndent, indent)
  }

  private fun lift(node: DocNode, components: ComponentRegistry): Pair<String, String> {
    val throwaway = UiDocument("_", node, Contract(), version = 0, nextHandle = 0)
    val exported = exportKotlin(throwaway.toWidgetTree(), functionName = "Tmp", components = components)
    val file = PsiEnv.parse("Tmp.kt", exported)
    val fn = file.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "Tmp" }
    val stmt = (fn.bodyExpression as KtBlockExpression).statements.first()
    val off = stmt.textRange.startOffset
    val lineStart = exported.lastIndexOf('\n', off - 1) + 1
    val srcIndent = exported.substring(lineStart, off).takeIf { it.isBlank() } ?: ""
    return stmt.text to srcIndent
  }

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
