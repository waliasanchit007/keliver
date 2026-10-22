package dev.keliver.portal.ingest

import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.UiDocument
import dev.keliver.portal.widgetSpec
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * K3 last review round: a RawCode replacement (`ReplaceRaw`) is accepted only
 * when its text can stand, in the file, as exactly that one code statement —
 * so what is written reads back as the document. Refused up front, with the
 * reason, when the text is
 *  - not exactly one Kotlin statement (several, none, or a parse error), or
 *    has a comment outside the statement (`Legacy(2) // c` — the comment is
 *    not part of the statement, so it would not read back);
 *  - grammar the editor models (a widget, an `if`, a `forEach`): it would
 *    read back as that node, not as code — insert it as a widget instead.
 * The second check reads the text where it would stand: in the file's own
 * screen function (its imports, design tokens and bindings parameter), inside
 * the node's enclosing `forEach`es and under its layout parent.
 */
object RawCodeEdit {
  /** Null = [text] can replace the RawCode [target] of [doc]; else why not. */
  fun problem(
    text: String,
    doc: UiDocument,
    target: Handle,
    /** The screen file as it is on disk (null = none yet: only the statement check runs). */
    fileText: String?,
    fileName: String,
    components: ComponentRegistry = EmptyComponentRegistry,
    tokens: TokenTable = TokenTable.EMPTY,
    /** E14: [fileText]'s component is the document, not a screen ([Recognizer.recognize]). */
    asComponent: Boolean = false,
  ): String? {
    val block = runCatching { PsiEnv.factory.createBlock(text) }.getOrNull()
      ?: return "the code does not parse as Kotlin"
    val stmts = block.statements
    if (stmts.isEmpty()) return "the code is empty (delete the node instead)"
    if (stmts.size > 1) return "the code is ${stmts.size} statements; a code node is exactly one (edit the .kt file to add more)"
    val stmt = stmts.single()
    val outside = generateSequence(block.lBrace?.nextSibling) { it.nextSibling }
      .takeWhile { it != block.rBrace }
      .filter { it != stmt && it !is PsiWhiteSpace }
      .toList()
    outside.firstOrNull { it is PsiComment }?.let {
      return "the comment `${it.text.trim()}` is outside the statement, so it is not part of the code node " +
        "(put it inside the statement, or edit the .kt file)"
    }
    if (outside.isNotEmpty() || PsiTreeUtil.hasErrorElements(block)) return "the code does not parse as one Kotlin statement"

    if (fileText == null) return null
    val lf = LineEndings.toLf(fileText)
    val rec = Recognizer.recognize(fileName, lf, components, tokens, asComponent) ?: return null
    val fn = rec.file?.declarations?.filterIsInstance<KtNamedFunction>()
      ?.firstOrNull { f -> f.annotationEntries.any { it.shortName?.asString() == "Composable" } } ?: return null
    val body = fn.bodyExpression as? KtBlockExpression ?: return null
    val path = pathTo(doc.root, target) ?: return null
    val param = rec.bindingsParam ?: "b"
    // The node's enclosing forEach'es (their items are in scope) and its layout parent (scoped modifiers).
    val logic = setOf("Condition", dev.keliver.portal.ELSE_TYPE, "Repeat")
    val layout = path.lastOrNull { it.type !in logic }
      ?.takeIf { widgetSpec(it.type) != null && (it !== doc.root || body.statements.size == 1) }
    val wrappers = path.filter { it.type == "Repeat" || it === layout }.map { w ->
      if (w.type == "Repeat") {
        val items = (w.props["items"] as? dev.keliver.portal.document.PropValue.Lit)?.s ?: return null
        val item = (w.props["item"] as? dev.keliver.portal.document.PropValue.Lit)?.s ?: return null
        (if (param == COMPONENT_PARAMS) "" else "$param.") + "$items.forEach { $item ->"
      } else {
        "${w.type} {"
      }
    }
    // A second top-level statement keeps the probe's root a plain list (a lone statement would BE the root).
    val probeBody = "{\n" + wrappers.joinToString("") { "$it\n" } + text.trim() + "\n" + "}\n".repeat(wrappers.size) + "Unit\n}"
    val range = body.textRange
    val probe = runCatching {
      Recognizer.recognize(fileName, lf.substring(0, range.startOffset) + probeBody + lf.substring(range.endOffset), components, tokens, asComponent)
    }.getOrNull() ?: return null
    var node: DocNode = probe.root.children.firstOrNull() ?: return null
    repeat(wrappers.size) { node = (node as? DocNode.Widget)?.children?.singleOrNull() ?: return null }
    val w = node as? DocNode.Widget ?: return null
    val what = when (w.type) {
      "Condition" -> "an `if` the editor models (a Condition)"
      "Repeat" -> "a `forEach` the editor models (a Repeat)"
      else -> "a ${w.type} widget"
    }
    return "this code reads as $what, not as code: insert it as a widget instead (or edit the .kt file)"
  }

  /** The widgets from [root] down to [target]'s parent; null when [target] is not under [root]. */
  private fun pathTo(root: DocNode, target: Handle): List<DocNode.Widget>? {
    if (root !is DocNode.Widget) return null
    for (c in root.children) {
      if (c.handle == target) return listOf(root)
      pathTo(c, target)?.let { return listOf(root) + it }
    }
    return null
  }
}
