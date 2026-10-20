package dev.keliver.portal.document

import dev.keliver.portal.ArithOp

/*
 * K7: Int arithmetic values and Ints used as Dps, in the document — their
 * canonical form, their value where the document knows it, the fields their
 * operands read, and the compatibility view the relay serves a client that
 * predates them.
 */

/**
 * The canonical form of an arithmetic value ([PropValue.Arith]): an
 * expression whose every operand is a literal is the plain [PropValue.Lit] it
 * computes — what an op that leaves no reference stores (a token or a
 * binding is a reference). An expression that cannot be computed (a zero
 * divisor, an unknown operator) is kept as it is, for the document to refuse.
 * Operands are never folded: `size * (2 + 3)` reads back as that tree.
 */
public fun PropValue.Arith.canonical(): PropValue {
  if (!literalOnly()) return this
  return staticIntValue(this)?.let { lit(it) } ?: this
}

/**
 * The canonical form of an Int used as a Dp ([PropValue.IntDp]): one whose
 * value is a literal (or an expression of literals) is the plain Dp
 * [PropValue.Lit] (a Double) it is; otherwise its value in canonical form.
 */
public fun PropValue.IntDp.canonical(): PropValue {
  val inner = canonicalValue(value)
  if (inner is PropValue.Lit && inner.tag == "i" && inner.i != null) return lit(inner.i.toDouble())
  return if (inner == value) this else copy(value = inner)
}

private fun PropValue.literalOnly(): Boolean = when (this) {
  is PropValue.Lit -> true
  is PropValue.Arith -> left.literalOnly() && right.literalOnly()
  else -> false
}

/**
 * K7: [v]'s Int value when the document knows it — an Int literal, an Int
 * token's value, an arithmetic value whose every operand is one of those —
 * else null (an operand reads a binding, or the expression is not valid).
 */
public fun staticIntValue(v: PropValue): Int? = when (v) {
  is PropValue.Lit -> if (v.tag == "i") v.i else null
  is PropValue.Token -> if (v.value.tag == "i") v.value.i else null
  is PropValue.Arith -> {
    val op = ArithOp.fromSymbol(v.op)
    val a = staticIntValue(v.left)
    val b = staticIntValue(v.right)
    if (op == null || a == null || b == null) null else op.apply(a, b)
  }
  else -> null
}

/** K7: every operand of [this] that is not an [PropValue.Arith] (its literals, tokens and bindings), in source order. */
public fun PropValue.Arith.operandValues(): List<PropValue> =
  listOf(left, right).flatMap { if (it is PropValue.Arith) it.operandValues() else listOf(it) }

/** K7: the operands of an arithmetic value, or of an Int used as a Dp — empty for anything else. */
public fun arithOperandsOf(v: PropValue?): List<PropValue> = when (v) {
  is PropValue.Arith -> v.operandValues()
  is PropValue.IntDp -> (v.value as? PropValue.Arith)?.operandValues() ?: listOf(v.value)
  else -> emptyList()
}

/** K7: the fields (screen fields, item fields `item.x`) the operands of [v] read — also in a conditional's branches. */
public fun arithFields(v: PropValue?): List<String> = when (v) {
  is PropValue.Conditional -> arithFields(v.whenTrue) + arithFields(v.whenFalse)
  else -> arithOperandsOf(v).filterIsInstance<PropValue.Bind>().map { it.field }
}

/** K7: whether [v] is arithmetic — an [PropValue.Arith] or a [PropValue.IntDp]. */
public fun isArithmetic(v: PropValue?): Boolean = v is PropValue.Arith || v is PropValue.IntDp

/**
 * K7 compatibility: [this] as an editor that predates arithmetic can decode
 * it — every arithmetic value the document can compute (its operands
 * literals and tokens) replaced by that literal, an Int used as a Dp by its
 * Dp literal (never negative), also in a conditional's branches; a prop
 * holding one the document cannot compute (an operand reads a binding) is
 * left out, so such an editor shows the prop's default. The relay serves
 * this unless the client asks `arithmetic=1`. As with tokens and templates,
 * an edit such a client makes to another prop leaves the expression in the
 * document and the file; a prop it sets becomes what it sets.
 */
public fun UiDocument.withoutArithmetic(): UiDocument {
  /** The literal [x] reads as for such an editor; null = it cannot be computed. */
  fun plain(x: PropValue): PropValue? = when (x) {
    is PropValue.Arith -> staticIntValue(x)?.let { lit(it) }
    is PropValue.IntDp -> staticIntValue(x.value)?.let { lit(it.toDouble().coerceAtLeast(0.0)) }
    else -> x
  }
  fun value(x: PropValue): PropValue? = when (x) {
    is PropValue.Conditional -> {
      val t = x.whenTrue?.let { plain(it) ?: return null }
      val f = x.whenFalse?.let { plain(it) ?: return null }
      x.copy(whenTrue = t, whenFalse = f)
    }
    else -> plain(x)
  }
  fun values(m: Map<String, PropValue>): Map<String, PropValue> = buildMap {
    m.forEach { (k, v) -> value(v)?.let { put(k, it) } }
  }
  fun node(n: DocNode): DocNode = when (n) {
    is DocNode.RawCode -> n
    is DocNode.Widget -> n.copy(props = values(n.props), modifiers = values(n.modifiers), children = n.children.map(::node))
  }
  return copy(root = node(root))
}

/** K7: [this] with [f] applied to each operand that is not an [PropValue.Arith]; the tree's shape kept (nothing folded). */
public fun PropValue.Arith.mapOperandTree(f: (PropValue) -> PropValue): PropValue.Arith =
  PropValue.Arith(op, (left as? PropValue.Arith)?.mapOperandTree(f) ?: f(left), (right as? PropValue.Arith)?.mapOperandTree(f) ?: f(right))
