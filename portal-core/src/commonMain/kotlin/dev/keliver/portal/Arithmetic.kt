package dev.keliver.portal

/**
 * K7: INTEGER ARITHMETIC values — `cornerRadiusDp = size / 2`,
 * `fontSize = size * 2 / 5`, `widthDp = b.columns * 48 + 8` — and an Int
 * value used as a Dp ([IntDp]: `Spacer(height = Dp(gap.toDouble()))`,
 * `gap.dp`).
 *
 * An [Arith] is one binary operation; its operands are Int literals
 * (negative too, never `-2147483648`, which Kotlin source reads as a Long),
 * Int design [Token]s, [Bind]s of Int fields (a screen binding, an item field
 * inside its Repeat, a component parameter) and other [Arith]s. The tree IS
 * the expression as Kotlin parses it: precedence (`*` `/` `%` over `+` `-`)
 * and left associativity decide its shape, parentheses only group, and
 * [arithKotlin] writes the parentheses that shape needs. `/` and `%` take
 * only a non-zero Int LITERAL as their right operand: Kotlin/JS and
 * Kotlin/wasm do not fail alike on a zero divisor, so the grammar never
 * divides by a value it cannot see.
 *
 * Kotlin's Int arithmetic is 32-bit two's complement on every target (the
 * device is Kotlin/JS, which emulates it: `|0`, `Math.imul`; the preview is
 * wasm; the relay the JVM): overflow wraps, `/` truncates toward zero, `%`
 * takes the dividend's sign, `Int.MIN_VALUE / -1` is `Int.MIN_VALUE`. The
 * interpreter evaluates with Kotlin's own Int operators ([ArithOp.apply]),
 * so it computes what compiled code computes; `portal-render`'s
 * `ArithParityTest` pins it on js and wasm.
 */
public enum class ArithOp(
  /** The Kotlin operator — also the wire name. */
  public val symbol: String,
  /** Kotlin's precedence: multiplicative (2) binds tighter than additive (1). */
  public val precedence: Int,
) {
  Plus("+", 1),
  Minus("-", 1),
  Times("*", 2),
  Div("/", 2),
  Rem("%", 2),
  ;

  /** This operator on two Ints, as Kotlin computes it; null for a zero divisor (which the grammar never holds). */
  public fun apply(a: Int, b: Int): Int? = when (this) {
    Plus -> a + b
    Minus -> a - b
    Times -> a * b
    Div -> if (b == 0) null else a / b
    Rem -> if (b == 0) null else a % b
  }

  /** Whether this operator's right operand is a divisor (a non-zero Int literal only). */
  public val divides: Boolean get() = this == Div || this == Rem

  public companion object {
    /** The operator spelled [symbol], or null. */
    public fun fromSymbol(symbol: String?): ArithOp? = entries.firstOrNull { it.symbol == symbol }
  }
}

/**
 * K7: one binary Int operation `left op right`. Each operand is an Int, a
 * [Token] of an Int, a [Bind] or another [Arith] (and, inside a preview
 * expansion, whatever a component argument substituted — a [Conditional], a
 * mock). See [ArithOp].
 */
public data class Arith(val op: ArithOp, val left: Any?, val right: Any?)

/**
 * K7: an Int value used where a Dp is taken — `<x>.dp` ([ctor] false) or
 * `Dp(<x>.toDouble())` ([ctor] true), the author's spelling. [value] is an
 * [Arith], a [Bind] of an Int field or an Int [Token]. A Dp is never
 * negative: on a device a negative value fails `Dp`'s own check, the
 * preview draws 0 (as for any Dp).
 */
public data class IntDp(val value: Any?, val ctor: Boolean = false)

/**
 * K7: [v]'s Int value — an Int, an Int [Token]'s value, an [Arith] computed
 * from its operands — each operand that is none of those read through
 * [operand] (null = unknown). UNKNOWN when any operand is: an unmocked
 * binding, a mock that is not a decimal, a value chosen at render time. An
 * unknown expression renders as an unmocked binding does (the prop's
 * default), never as a guess.
 */
public fun arithValue(v: Any?, operand: (Any?) -> Int? = { null }): Int? = when (val x = tokenValue(v)) {
  is Int -> x
  is Arith -> {
    val a = arithValue(x.left, operand)
    val b = arithValue(x.right, operand)
    if (a == null || b == null) null else x.op.apply(a, b)
  }
  else -> operand(x)
}

/** K7: an [IntDp]'s value in dp (null = unknown, see [arithValue]). */
public fun intDpValue(v: IntDp, operand: (Any?) -> Int? = { null }): Double? = arithValue(v.value, operand)?.toDouble()

/**
 * K7: [v] as Kotlin source — each operand an Int literal, a token's
 * reference, or a binding through [ref]; parentheses exactly where the tree
 * needs them (a looser operand on either side, an operand as loose on the
 * right: `a - (b - c)`, `a * (b / c)`, `a + (b + c)` — the shape is kept so
 * the text reads back as the same tree).
 */
public fun arithKotlin(v: Any?, ref: (String) -> String): String = when (v) {
  is Arith -> {
    fun side(x: Any?, right: Boolean): String {
      val text = arithKotlin(x, ref)
      val inner = (x as? Arith)?.op?.precedence ?: return text
      val wrap = inner < v.op.precedence || (right && inner == v.op.precedence)
      return if (wrap) "($text)" else text
    }
    "${side(v.left, right = false)} ${v.op.symbol} ${side(v.right, right = true)}"
  }
  is IntDp -> {
    val inner = arithKotlin(v.value, ref)
    val receiver = if (v.value is Arith) "($inner)" else inner
    if (v.ctor) "Dp($receiver.toDouble())" else "$receiver.dp"
  }
  is Token -> v.ref
  is Bind -> ref(v.field)
  else -> tokenValue(v).toString()
}

/** K7: every operand of [v] that is not an [Arith] — its literals, tokens and bindings, in source order (an [IntDp]'s value's). */
public fun arithOperands(v: Any?): List<Any?> = when (v) {
  is Arith -> arithOperands(v.left) + arithOperands(v.right)
  is IntDp -> arithOperands(v.value)
  else -> listOf(v)
}

/** K7: the bindings [v]'s operands read (screen fields, item fields, parameters), in source order; none for anything that is not arithmetic. */
public fun arithBinds(v: Any?): List<Bind> = when (v) {
  is Arith, is IntDp -> arithOperands(v).filterIsInstance<Bind>()
  is Conditional -> arithBinds(v.whenTrue) + arithBinds(v.whenFalse)
  else -> emptyList()
}

/** K7: [v] with [f] applied to every operand that is not an [Arith] (also an [IntDp]'s); anything else unchanged. */
public fun mapArithOperands(v: Any?, f: (Any?) -> Any?): Any? = when (v) {
  is Arith -> Arith(v.op, mapArithOperands(v.left, f), mapArithOperands(v.right, f))
  is IntDp -> IntDp(mapArithOperands(v.value, f), v.ctor)
  else -> f(v)
}
