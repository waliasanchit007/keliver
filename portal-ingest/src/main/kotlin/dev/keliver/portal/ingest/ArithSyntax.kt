package dev.keliver.portal.ingest

import dev.keliver.portal.ArithOp
import dev.keliver.portal.document.DP_CTOR_FORM
import dev.keliver.portal.document.PropValue
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression

/** K7: an expression's source read as an arithmetic value, or why it is not grammar. */
internal sealed interface ArithRead {
  data class Ok(val value: PropValue) : ArithRead
  data class No(val why: String) : ArithRead

  /** Not arithmetic at all (a single reference, a literal, a call…): another reading may apply. */
  data object NotArith : ArithRead
}

/**
 * K7: Int arithmetic in source — `size / 2`, `b.columns * 48 + 8`, `(a + b) % 3`
 * — and an Int used as a Dp, `x.dp` or `Dp(x.toDouble())`.
 *
 * Read from ONE parse ([PsiEnv.factory]): the tree is Kotlin's (precedence,
 * left associativity), parentheses only group. An operator is one of `+ - *
 * / %`; an operand an Int literal in decimal (a negative one is `-N`, never
 * `-2147483648`, a Long in source), or whatever [operand] reads — a binding
 * of an Int field, an Int design token, a component's derived Int local.
 * `/` and `%` divide only by a non-zero Int literal. At least one operand is
 * not a literal: an expression of literals stays what it was before K7 (not
 * grammar — write its value). No comment inside, no unary minus on anything
 * but a literal, no call (`max(a, b)`, `x.coerceIn(…)`), no Long, Double or
 * Float arithmetic.
 */
internal object ArithSyntax {
  /** What an operand leaf reads as: [value] (a Bind, a Token, or — a derived local — the expression it stands for), or [why] not. */
  data class Operand(val value: PropValue?, val why: String?)

  fun interface Operands {
    fun read(e: KtExpression): Operand
  }

  /** [e] as an Int expression ([ArithRead.NotArith] for a single leaf: a plain value is read elsewhere). */
  fun readInt(e: KtExpression, operands: Operands): ArithRead {
    val x = unwrap(e)
    if (x !is KtBinaryExpression && !(x is KtPrefixExpression && x.operationToken == KtTokens.MINUS && x.baseExpression !is KtConstantExpression)) {
      return ArithRead.NotArith
    }
    commentWhy(e)?.let { return ArithRead.No(it) }
    if (x is KtBinaryExpression && ArithOp.fromSymbol(x.operationReference.text) == null) return ArithRead.NotArith
    return when (val r = tree(x, operands)) {
      is ArithRead.Ok -> if (literalOnly(r.value)) ArithRead.No(LITERALS.replace("%s", e.text.oneLine())) else r
      else -> r
    }
  }

  /** [e] as an Int used as a Dp — `x.dp` or `Dp(x.toDouble())`, `x` an Int expression or one Int reference. */
  fun readDp(e: KtExpression, operands: Operands): ArithRead {
    val (receiver, form) = dpReceiver(e) ?: return ArithRead.NotArith
    commentWhy(e)?.let { return ArithRead.No(it) }
    val inner = unwrap(receiver)
    val value = if (inner is KtBinaryExpression || inner is KtPrefixExpression) {
      when (val r = tree(inner, operands)) {
        is ArithRead.Ok -> r.value
        else -> return r
      }
    } else {
      if (inner is KtConstantExpression) return ArithRead.NotArith // `16.dp`: the Dp literal
      val o = operands.read(inner)
      o.value ?: return ArithRead.No(o.why ?: "`${inner.text.oneLine()}` is not an Int")
    }
    if (literalOnly(value)) return ArithRead.No(LITERALS.replace("%s", e.text.oneLine()))
    return ArithRead.Ok(PropValue.IntDp(value, form))
  }

  /** The receiver of `x.dp` / `Dp(x.toDouble())` and the spelling ([DP_CTOR_FORM] or null). */
  fun dpReceiver(e: KtExpression): Pair<KtExpression, String?>? {
    val x = unwrap(e)
    if (x is KtDotQualifiedExpression) {
      val sel = x.selectorExpression as? KtNameReferenceExpression ?: return null
      if (sel.getReferencedName() != "dp") return null
      return x.receiverExpression to null
    }
    if (x is KtCallExpression && x.calleeExpression?.text == "Dp" && x.typeArgumentList == null && x.lambdaArguments.isEmpty()) {
      val arg = x.valueArguments.singleOrNull() ?: return null
      if (arg.getArgumentName() != null || arg.getSpreadElement() != null) return null
      val dot = unwrap(arg.getArgumentExpression() ?: return null) as? KtDotQualifiedExpression ?: return null
      val call = dot.selectorExpression as? KtCallExpression ?: return null
      if (call.calleeExpression?.text != "toDouble" || call.valueArguments.isNotEmpty() || call.lambdaArguments.isNotEmpty()) return null
      return dot.receiverExpression to DP_CTOR_FORM
    }
    return null
  }

  private fun unwrap(e: KtExpression): KtExpression {
    var x = e
    while (x is KtParenthesizedExpression) x = x.expression ?: return x
    return x
  }

  private fun commentWhy(e: KtExpression): String? =
    if (hasComment(e)) "`${e.text.oneLine()}` has a comment inside it (an expression is its operands and operators only)" else null

  private fun literalOnly(v: PropValue): Boolean = when (v) {
    is PropValue.Lit -> true
    is PropValue.Arith -> literalOnly(v.left) && literalOnly(v.right)
    else -> false
  }

  /** An Int literal's value from its source (decimal digits; null for anything else, or past Int). */
  fun intLiteral(e: KtExpression): Int? {
    val x = unwrap(e)
    if (x is KtPrefixExpression && x.operationToken == KtTokens.MINUS) {
      val base = unwrap(x.baseExpression ?: return null) as? KtConstantExpression ?: return null
      if (!Regex("^\\d+$").matches(base.text)) return null
      val v = base.text.toLongOrNull()?.let { -it } ?: return null
      // -2147483648: Kotlin reads 2147483648 as a Long, so the negation is a Long too.
      return if (v >= -Int.MAX_VALUE.toLong()) v.toInt() else null
    }
    val c = x as? KtConstantExpression ?: return null
    if (!Regex("^\\d+$").matches(c.text)) return null
    return c.text.toIntOrNull()
  }

  private fun tree(e: KtExpression, operands: Operands): ArithRead {
    val x = unwrap(e)
    if (x is KtBinaryExpression) {
      val op = ArithOp.fromSymbol(x.operationReference.text)
        ?: return ArithRead.No("`${x.operationReference.text}` is not an Int operator (`+ - * / %`)")
      val l = when (val r = tree(x.left ?: return ArithRead.No("`${x.text.oneLine()}` does not parse"), operands)) {
        is ArithRead.Ok -> r.value
        else -> return r
      }
      val rightExpr = x.right ?: return ArithRead.No("`${x.text.oneLine()}` does not parse")
      if (op.divides) {
        val d = intLiteral(rightExpr)
        if (d == null || d == 0) {
          return ArithRead.No("`${x.text.oneLine()}`: `${op.symbol}` divides only by a non-zero Int literal " +
            "(Kotlin/JS and Kotlin/wasm do not fail alike on a zero divisor, so the portal never divides by a value it cannot see)")
        }
        return ArithRead.Ok(PropValue.Arith(op.symbol, l, PropValue.Lit("i", i = d)))
      }
      val r = when (val rr = tree(rightExpr, operands)) {
        is ArithRead.Ok -> rr.value
        else -> return rr
      }
      return ArithRead.Ok(PropValue.Arith(op.symbol, l, r))
    }
    intLiteral(x)?.let { return ArithRead.Ok(PropValue.Lit("i", i = it)) }
    if (x is KtPrefixExpression && x.operationToken == KtTokens.MINUS && unwrap(x.baseExpression ?: x) is KtConstantExpression) {
      return ArithRead.No("`${x.text}` is not an Int literal in decimal (`-2147483648` and anything past Int is a Long in Kotlin source)")
    }
    if (x is KtPrefixExpression) {
      return ArithRead.No("`${x.text.oneLine()}`: a unary `${x.operationReference.text}` applies only to an Int literal (write `0 - x` or `-1 * x`)")
    }
    if (x is KtConstantExpression) {
      return ArithRead.No("`${x.text}` is not an Int literal in decimal (a Long, a Double, a Float, hex or `_` separators are not Int operands here)")
    }
    val o = operands.read(x)
    return o.value?.let { ArithRead.Ok(it) } ?: ArithRead.No(o.why ?: "`${x.text.oneLine()}` is not an Int operand")
  }

  private const val LITERALS = "`%s` is an expression of literals only: write its value (an expression reads a binding, a parameter or a design token)"

  // ── write-back ────────────────────────────────────────────────────────

  /** One operand leaf of a parsed expression: its value and its offsets in the source. */
  private data class Leaf(val start: Int, val end: Int, val value: PropValue)

  /**
   * [source] (an argument expression's text, which reads as [old]) with only
   * the operands that changed between [old] and [new] rewritten — when the
   * two have the same shape (the same operators in the same tree, an Int as
   * a Dp in the same spelling). [spell] is a reference's source (`b.count`,
   * `C.Gap`, an item field). Null when the shapes differ or [source] does not
   * read as [old]: the caller writes the canonical value.
   */
  fun surgical(source: String, old: PropValue, new: PropValue, spell: (PropValue) -> String?): String? {
    if (old == new) return source
    if (shape(old) == null || shape(old) != shape(new)) return null
    val e = runCatching { PsiEnv.factory.createExpression(source) }.getOrNull() ?: return null
    if (e.text != source) return null
    val base = e.textRange.startOffset
    val leaves = ArrayList<Leaf>()
    val oldOperands = operandsOf(old)
    fun collect(x: KtExpression): Boolean {
      val u = unwrap(x)
      if (u is KtBinaryExpression && ArithOp.fromSymbol(u.operationReference.text) != null) {
        return collect(u.left ?: return false) && collect(u.right ?: return false)
      }
      val i = leaves.size
      if (i >= oldOperands.size) return false
      leaves += Leaf(u.textRange.startOffset - base, u.textRange.endOffset - base, oldOperands[i])
      return true
    }
    val root = when (old) {
      is PropValue.IntDp -> dpReceiver(e)?.takeIf { it.second == old.form }?.first ?: return null
      else -> e
    }
    if (!collect(root) || leaves.size != oldOperands.size) return null
    if (sourceShape(root) != treeShape((old as? PropValue.IntDp)?.value ?: old)) return null
    val newOperands = operandsOf(new)
    var out = source
    for (i in leaves.indices.reversed()) {
      if (oldOperands[i] == newOperands[i]) continue
      var text = spellOperand(newOperands[i], spell) ?: return null
      val before = out.getOrNull(leaves[i].start - 1)
      if (text.startsWith("-") && (before == '-' || before == '+')) text = " $text" // `a--3` would be a decrement
      out = out.substring(0, leaves[i].start) + text + out.substring(leaves[i].end)
    }
    return out
  }

  /** An operand's canonical source: an Int literal, else the reference [spell] gives. */
  fun spellOperand(v: PropValue, spell: (PropValue) -> String?): String? = when {
    v is PropValue.Lit && v.tag == "i" -> v.i?.toString()
    v is PropValue.Lit -> null
    else -> spell(v)
  }

  private fun operandsOf(v: PropValue): List<PropValue> = when (v) {
    is PropValue.Arith -> operandsOf(v.left) + operandsOf(v.right)
    is PropValue.IntDp -> operandsOf(v.value)
    else -> listOf(v)
  }

  /** The value's shape: its operator tree with every operand a hole, and an IntDp's spelling. */
  private fun shape(v: PropValue): String? = when (v) {
    is PropValue.Arith -> treeShape(v)
    is PropValue.IntDp -> "dp[${v.form}](${treeShape(v.value)})"
    else -> null
  }

  private fun treeShape(v: PropValue): String = when (v) {
    is PropValue.Arith -> "(${treeShape(v.left)}${v.op}${treeShape(v.right)})"
    else -> "_"
  }

  private fun sourceShape(e: KtExpression): String {
    val u = unwrap(e)
    if (u is KtBinaryExpression && ArithOp.fromSymbol(u.operationReference.text) != null) {
      return "(${sourceShape(u.left!!)}${u.operationReference.text}${sourceShape(u.right!!)})"
    }
    return "_"
  }
}
