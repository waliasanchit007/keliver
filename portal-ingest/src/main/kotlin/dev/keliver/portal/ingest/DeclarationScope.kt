package dev.keliver.portal.ingest

import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.UiDocument
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * K3 fourth review, S5: a move that would write code which reads back as the
 * document but no longer compiles because of a local declaration — a code
 * node `val label = "hi"` moved after a statement that uses `label`, or into
 * another block (where what follows it can no longer see it).
 *
 *  - A code node that DECLARES something (`val`, `var`, `fun`, `class`,
 *    `object`, …) never moves to another block.
 *  - Nothing that mentions a declared name (a code node, or a widget holding
 *    one, or a token reference — matched as a whole word in its text) moves
 *    from where the declaration can see it (after it, in its block or
 *    deeper) to where it cannot.
 *
 * Text matching is deliberately simple: a shadowing local or a name in a
 * string also counts as a use (the move is refused; it can be made in code).
 * What it does not see — a use through a binding or a reference the editor
 * models without text — cannot mention a local declaration anyway.
 */
object DeclarationScope {
  /** Null = the change from [before] to [after] keeps every local declaration's uses in its scope; else why not. */
  fun problem(before: UiDocument, after: UiDocument): String? {
    val was = Places(before.root)
    val now = Places(after.root)
    for ((h, node) in now.nodes) {
      if (node !is DocNode.RawCode) continue
      val names = declaredNames(node.text).ifEmpty { continue }
      if (h !in was.nodes) continue // new code: not a move
      val code = "`${short(node.text)}`"
      if (was.parent[h] != now.parent[h]) {
        return "the code $code declares ${names.joinToString { "`$it`" }}; moving it to another block can leave code that " +
          "uses it unable to see it (move it in code)"
      }
      val seenBefore = was.visibleAfter(h)
      val seenNow = now.visibleAfter(h)
      for (name in names) {
        val word = Regex("(?<![\\w`])${Regex.escape(name)}(?![\\w`])")
        val lost = seenBefore.firstOrNull { u -> u !in seenNow && u != h && mentions(was.nodes.getValue(u), word) }
          ?: continue
        val user = was.nodes.getValue(lost)
        return "${label(user)} uses `$name`, which the code $code declares; after this move it would stand where " +
          "`$name` is not declared yet (move it in code)"
      }
    }
    return null
  }

  private fun short(text: String): String = text.trim().replace(Regex("\\s+"), " ").let { if (it.length > 50) it.take(47) + "…" else it }

  private fun label(n: DocNode): String = when (n) {
    is DocNode.RawCode -> "the code `${short(n.text)}`"
    is DocNode.Widget -> n.type
  }

  /** The names [text] declares as one statement (none = not a declaration). */
  internal fun declaredNames(text: String): List<String> {
    val stmt = runCatching { PsiEnv.factory.createBlock(text).statements.singleOrNull() }.getOrNull() ?: return emptyList()
    return when (stmt) {
      is KtDestructuringDeclaration -> stmt.entries.mapNotNull { it.name }.filter { it != "_" }
      is KtNamedDeclaration -> listOfNotNull(stmt.name)
      else -> emptyList()
    }
  }

  /** Whether [n] (or anything in it) mentions [word]: its code text, or a token reference. */
  private fun mentions(n: DocNode, word: Regex): Boolean = when (n) {
    is DocNode.RawCode -> word.containsMatchIn(n.text)
    is DocNode.Widget -> (n.props.values + n.modifiers.values).any { refMentions(it, word) } || n.children.any { mentions(it, word) }
  }

  private fun refMentions(v: PropValue?, word: Regex): Boolean = when (v) {
    is PropValue.Token -> word.containsMatchIn(v.ref)
    is PropValue.Conditional -> refMentions(v.whenTrue, word) || refMentions(v.whenFalse, word)
    else -> false
  }

  /** Each node by handle, its parent, and its block's children. */
  private class Places(root: DocNode) {
    val nodes = HashMap<Long, DocNode>()
    val parent = HashMap<Long, Long>()
    private val siblings = HashMap<Long, List<DocNode>>()

    init {
      fun walk(n: DocNode, p: DocNode.Widget?) {
        nodes[n.handle.v] = n
        if (p != null) {
          parent[n.handle.v] = p.handle.v
          siblings[n.handle.v] = p.children
        }
        if (n is DocNode.Widget) n.children.forEach { walk(it, n) }
      }
      walk(root, null)
    }

    /**
     * Every node a declaration [h] can be seen from: the later siblings in its
     * block and everything in them — not an `else` beside a then block (the
     * else block does not see the then block's declarations).
     */
    fun visibleAfter(h: Long): Set<Long> {
      val sibs = siblings[h] ?: return emptySet()
      val out = HashSet<Long>()
      fun add(n: DocNode) {
        out += n.handle.v
        if (n is DocNode.Widget) n.children.forEach(::add)
      }
      sibs.drop(sibs.indexOfFirst { it.handle.v == h } + 1)
        .filterNot { it is DocNode.Widget && it.type == dev.keliver.portal.ELSE_TYPE }
        .forEach(::add)
      return out
    }
  }
}
