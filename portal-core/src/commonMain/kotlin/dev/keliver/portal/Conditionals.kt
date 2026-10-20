package dev.keliver.portal

/**
 * K3: the grammar's conditions — ONE model shared by conditional children
 * (a `Condition` node: `if (…) { … } else { … }`) and conditional prop values
 * (`colorArgb = if (selected) C.A else C.B`).
 *
 * A condition is one or more TERMS — K6: two or more are joined by `&&`
 * only, or by `||` only ([CondJoin]; never mixed, never parenthesized, never
 * a negated group). A term reads one field — a screen binding
 * (`b.selected`) or, inside a project component's body, one of its
 * parameters (`selected`) — and applies one [test], optionally negated.
 * Every form has exactly ONE spelling, so the source a condition came from is
 * the source it prints:
 *
 * | test    | negate = false        | negate = true            |
 * |---------|-----------------------|--------------------------|
 * | [Bool]  | `x`                   | `!x`                     |
 * | [Empty] | `x.isEmpty()`         | `x.isNotEmpty()`         |
 * | [Blank] | `x.isBlank()`         | `x.isNotBlank()`         |
 * | [Null]  | `x == null`           | `x != null`              |
 * | [Eq]    | `x == <operand>`      | `x != <operand>`         |
 *
 * Terms are joined by ` && ` or ` || `. [Null] is accepted only inside a
 * component body, where the parameter's declared type says it is nullable; a
 * screen's contract is derived from its tree and has no way to type a
 * nullable field. K6: [Eq] compares the field — always on the left — with
 * [Cond.operand]: a String, Int or Boolean literal, or a design [Token] of
 * one of those kinds. The operand's kind is the field's kind.
 */
public enum class CondTest(
  /** The wire name (`Condition` node prop `test`, `PropValue.Conditional.test`); null = Bool. */
  public val wire: String?,
) {
  Bool(null),
  Empty("empty"),
  Blank("blank"),
  Null("null"),

  /** K6: `x == <operand>` (`x != <operand>` negated). */
  Eq("eq"),
  ;

  public companion object {
    /** The test named [wire] (null/blank = [Bool]); null for an unknown name. */
    public fun fromWire(wire: String?): CondTest? =
      if (wire.isNullOrEmpty()) Bool else entries.firstOrNull { it.wire == wire }
  }
}

/** K6: how the terms of a compound condition are joined — all by `&&`, or all by `||`. */
public enum class CondJoin(
  /** The wire name (`join`); null = [And], the default. */
  public val wire: String?,
  /** The Kotlin operator. */
  public val kotlin: String,
) {
  And(null, "&&"),
  Or("or", "||"),
  ;

  public companion object {
    /** The join named [wire] (null/blank = [And]); null for an unknown name. */
    public fun fromWire(wire: String?): CondJoin? =
      if (wire.isNullOrEmpty()) And else entries.firstOrNull { it.wire == wire }
  }
}

/**
 * One grammar condition; see [CondTest]. This object is its FIRST term
 * ([field], [test], [negate], [operand]). K6: [more] holds the terms after it
 * — each a single term (its own [more] empty) — all joined by [join]; [terms]
 * lists them all. A single-term condition is exactly the K3 one.
 */
public data class Cond(
  val field: String,
  val test: CondTest = CondTest.Bool,
  val negate: Boolean = false,
  /** K6: what an [CondTest.Eq] term compares with — a String, Int or Boolean, or a [Token] of one; null for any other test. */
  val operand: Any? = null,
  /** K6: the terms after this one (each a single term); empty = a single-term condition. */
  val more: List<Cond> = emptyList(),
  /** K6: how the terms are joined ([CondJoin.And] for a single term). */
  val join: CondJoin = CondJoin.And,
) {
  /** K6: every term in source order — this one (without [more]) first. */
  public val terms: List<Cond>
    get() = if (more.isEmpty() && join == CondJoin.And) listOf(this) else listOf(copy(more = emptyList(), join = CondJoin.And)) + more

  /** K6: whether this condition uses a K6 form — two or more terms, or an equality. */
  public val isExtended: Boolean get() = more.isNotEmpty() || test == CondTest.Eq

  /**
   * This condition as Kotlin source, [ref] turning each field into its
   * reference (`b.selected`, `selected`, `item.flag`): its terms joined by
   * ` && ` or ` || `.
   */
  public fun kotlin(ref: (String) -> String): String = terms.joinToString(" ${join.kotlin} ") { it.termKotlin(ref) }

  private fun termKotlin(ref: (String) -> String): String {
    val r = ref(field)
    return when (test) {
      CondTest.Bool -> if (negate) "!$r" else r
      CondTest.Empty -> if (negate) "$r.isNotEmpty()" else "$r.isEmpty()"
      CondTest.Blank -> if (negate) "$r.isNotBlank()" else "$r.isBlank()"
      CondTest.Null -> if (negate) "$r != null" else "$r == null"
      CondTest.Eq -> if (negate) "$r != ${operandKotlin(operand)}" else "$r == ${operandKotlin(operand)}"
    }
  }

  /**
   * Whether this TERM holds when its field's value is [value] ([present] =
   * the value is known). An UNKNOWN value — an unmocked binding, a
   * parameter with neither an argument nor a default — reads the way an
   * unmocked binding renders: a Boolean as true (as conditional children
   * always have), a String as a non-empty, non-blank placeholder, anything as
   * non-null. A String value (a preview mock) is read as its kind: `"false"`
   * is false, `""` is empty.
   *
   * K6: an equality compares by the OPERAND's kind, which is the field's: a
   * String equals only the same String; an Int the same Int — a String mock
   * read as a decimal ([String.toIntOrNull], as an Int binding renders); a
   * Boolean is read as for [CondTest.Bool] (a mock `"true"`/`"false"`;
   * anything else, and an unknown value, reads true). An unknown String or
   * Int — and a mock of an Int that is not a decimal — equals no operand:
   * `==` is false, `!=` true.
   *
   * A compound condition reads several fields: decide it with [holdsIn].
   * Here every term would read the same [value].
   */
  public fun holds(value: Any?, present: Boolean = true): Boolean {
    if (more.isNotEmpty()) return combine(terms.map { it.holds(value, present) })
    val v = tokenValue(value)
    val raw = when (test) {
      CondTest.Bool -> boolOf(v, present)
      CondTest.Empty -> present && v is String && v.isEmpty()
      CondTest.Blank -> present && v is String && v.isBlank()
      CondTest.Null -> present && v == null
      CondTest.Eq -> when (val op = tokenValue(operand)) {
        is Boolean -> boolOf(v, present) == op
        is Int -> present && (if (v is String) v.toIntOrNull() else v as? Int) == op
        is String -> present && v is String && v == op
        else -> false
      }
    }
    return raw != negate
  }

  /**
   * K6: whether the condition holds, each term reading its field in
   * [values] — a field [values] has no key for is UNKNOWN (see [holds]).
   * Every term is evaluated: a term is a pure read of a value, so Kotlin's
   * `&&`/`||` short-circuiting on a device cannot change the result.
   */
  public fun holdsIn(values: Map<String, Any?>): Boolean =
    combine(terms.map { t -> t.holds(values[t.field], t.field in values) })

  private fun combine(each: List<Boolean>): Boolean = if (join == CondJoin.Or) each.any { it } else each.all { it }

  /** The Kotlin type this TERM's field must have for its test (a screen contract field's type). */
  public val fieldType: String
    get() = when (test) {
      CondTest.Bool -> "Boolean"
      CondTest.Empty, CondTest.Blank -> "String"
      CondTest.Null -> "Any?"
      CondTest.Eq -> operandType(operand) ?: "Any?"
    }

  /**
   * K6: the condition that holds exactly when this one does not — each term
   * negated and the join flipped (De Morgan): `!(a && !b)` is `!a || b`.
   * Every test has a negated spelling, so the result is again a flat
   * condition.
   */
  public fun negated(): Cond =
    condOfTerms(terms.map { it.copy(negate = !it.negate) }, if (join == CondJoin.And && more.isNotEmpty()) CondJoin.Or else CondJoin.And)

  /**
   * K6: this condition with each term [decide] knows replaced by its truth
   * value — [decide] answers a Boolean for a term it decides, or a [Cond] to
   * keep in its place (the term itself, or re-pointed at another field). The
   * result is a Boolean when that decides the whole condition (a false term
   * under `&&`, a true one under `||`, or every term decided), else the
   * [Cond] of the terms left, joined as before. A kept [Cond] of several
   * terms is spliced in, so [decide] keeps it only where its join is this
   * one's (or one of the two is a single term).
   */
  public fun reduce(decide: (Cond) -> Any): Any {
    fun checked(d: Any): Any = when (d) {
      is Boolean, is Cond -> d
      else -> throw IllegalArgumentException("a term decides to a Boolean or a Cond, not $d")
    }
    if (more.isEmpty()) return checked(decide(this))
    val kept = ArrayList<Cond>()
    for (t in terms) {
      when (val d = checked(decide(t))) {
        is Boolean -> {
          if (join == CondJoin.And && !d) return false
          if (join == CondJoin.Or && d) return true
        }
        else -> {
          d as Cond
          require(d.more.isEmpty() || d.join == join) { "a ${d.join} condition cannot be spliced into a $join one" }
          kept += d.terms
        }
      }
    }
    return if (kept.isEmpty()) join == CondJoin.And else condOfTerms(kept, join)
  }
}

/** K6: the condition of [terms] (single terms, at least one) joined by [join] (a single term is joined by nothing). */
public fun condOfTerms(terms: List<Cond>, join: CondJoin = CondJoin.And): Cond {
  require(terms.isNotEmpty()) { "a condition has at least one term" }
  val first = terms.first().copy(more = emptyList(), join = CondJoin.And)
  if (terms.size == 1) return first
  return first.copy(more = terms.drop(1).map { it.copy(more = emptyList(), join = CondJoin.And) }, join = join)
}

/** K6: an equality operand's Kotlin type — "String", "Int" or "Boolean" (a token's value's), null for anything else. */
public fun operandType(operand: Any?): String? = when (tokenValue(operand)) {
  is String -> "String"
  is Int -> "Int"
  is Boolean -> "Boolean"
  else -> null
}

/**
 * K6: an equality operand as Kotlin source — a design token by its
 * reference, a String as a literal, an Int or a Boolean as itself.
 */
public fun operandKotlin(operand: Any?): String = when (operand) {
  is Token -> operand.ref
  is String -> kotlinStringLiteral(operand)
  else -> operand.toString()
}

/** A Boolean read the K3 way: an unknown value, or a String that is not `true`/`false`, reads true. */
private fun boolOf(v: Any?, present: Boolean): Boolean = if (!present) true else when (v) {
  is Boolean -> v
  is String -> v.toBooleanStrictOrNull() ?: true
  else -> true
}

/**
 * K3: a prop value chosen by a condition — `if (cond) whenTrue else
 * whenFalse` in source. Each branch is an ordinary tree value (a literal, a
 * [Token], a [Bind], an [Action]) or null (Kotlin `null`, only where the
 * parameter is nullable, e.g. an event). Branches never nest.
 */
public data class Conditional(val cond: Cond, val whenTrue: Any?, val whenFalse: Any?)

/** The type of the `else` branch node of a `Condition`: its children render when the condition does not hold. */
public const val ELSE_TYPE: String = "Else"

/** The logic node types (`if`/`forEach` in source): they add no layout receiver. */
public val LOGIC_NODE_TYPES: Set<String> = setOf("Condition", "Repeat", ELSE_TYPE)

/**
 * K6: the prop key of a `Condition` node's term [index] for [name] (`field`,
 * `test`, `negate`, `operand`): the first term's are the plain K3 keys, term
 * `i` ≥ 1's are suffixed (`field.1`, `test.1`, …). The join is the `join`
 * prop ([CondJoin.wire]; absent = `&&`).
 */
public fun conditionKey(name: String, index: Int): String = if (index == 0) name else "$name.$index"

/** K6: the `Condition` node prop holding a compound condition's join. */
public const val CONDITION_JOIN_PROP: String = "join"

/**
 * The [Cond] a `Condition` node tests (props `field`, `test`, `negate`,
 * K6: `operand`, the suffixed props of each further term and `join` — see
 * [conditionKey]), or null when it has no field, or a part this build
 * cannot read.
 */
public fun condOf(node: WidgetNode): Cond? {
  fun term(i: Int): Cond? {
    val field = (node.props[conditionKey("field", i)] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val test = CondTest.fromWire(node.props[conditionKey("test", i)] as? String) ?: return null
    val operand = if (test == CondTest.Eq) node.props[conditionKey("operand", i)] ?: return null else null
    return Cond(field, test, node.props[conditionKey("negate", i)] == true, operand)
  }
  val first = term(0) ?: return null
  val terms = mutableListOf(first)
  var i = 1
  while (conditionKey("field", i) in node.props) terms += term(i++) ?: return null
  val join = CondJoin.fromWire(node.props[CONDITION_JOIN_PROP] as? String) ?: return null
  return condOfTerms(terms, join)
}

/**
 * K6: a `Condition` node's props for [cond] ([conditionKey]), each default
 * left out (a Boolean test, no negation, a `&&` join) — what [condOf] reads.
 * [value] turns a value (a field name, a test name, `true`, an operand) into
 * the node's prop representation.
 */
public fun <V> conditionProps(cond: Cond, value: (Any) -> V): Map<String, V> = buildMap {
  cond.terms.forEachIndexed { i, t ->
    put(conditionKey("field", i), value(t.field))
    t.test.wire?.let { put(conditionKey("test", i), value(it)) }
    if (t.negate) put(conditionKey("negate", i), value(true))
    if (t.test == CondTest.Eq) t.operand?.let { put(conditionKey("operand", i), value(it)) }
  }
  if (cond.more.isNotEmpty()) cond.join.wire?.let { put(CONDITION_JOIN_PROP, value(it)) }
}

/** K6: whether [key] is one of a `Condition` node's condition props (any term's, or the join). */
public fun isConditionKey(key: String): Boolean {
  if (key == CONDITION_JOIN_PROP) return true
  val name = key.substringBefore('.')
  if (name !in CONDITION_TERM_NAMES) return false
  if ('.' !in key) return true
  return key.substringAfter('.').toIntOrNull()?.let { it >= 1 } == true
}

private val CONDITION_TERM_NAMES = setOf("field", "test", "negate", "operand")

/** A `Condition` node's then-branch children (everything but its [ELSE_TYPE] node). */
public fun WidgetNode.thenChildren(): List<WidgetNode> = children.filter { it.type != ELSE_TYPE }

/** A `Condition` node's else-branch node, if it has one. */
public fun WidgetNode.elseNode(): WidgetNode? = children.firstOrNull { it.type == ELSE_TYPE }

/**
 * [v] with every [Conditional] replaced by the branch [holds] picks
 * (recursively, so the result is never a Conditional); other values unchanged.
 */
public fun chooseBranch(v: Any?, holds: (Cond) -> Boolean): Any? =
  if (v is Conditional) chooseBranch(if (holds(v.cond)) v.whenTrue else v.whenFalse, holds) else v

/** The screen-side reference of a condition field: `b.x`, or an item-scoped `item.x` as written. */
internal fun screenRef(field: String): String = if ('.' in field) field else "b.$field"

/** [v]'s branches written with [fmt], as `if (cond) T else F` (a null branch is `null`). */
internal fun fmtConditional(v: Conditional, fmt: (Any?) -> String): String =
  "if (${v.cond.kotlin(::screenRef)}) ${v.whenTrue?.let(fmt) ?: "null"} else ${v.whenFalse?.let(fmt) ?: "null"}"

/**
 * K6: the imports every condition in [tree] needs for its operands — a
 * design token compared with (`b.kind == K.Card`) is written as its
 * reference, so its declaring object is imported. Every place a condition
 * sits counts: a `Condition` node, and a conditional value on any prop,
 * modifier argument or Margin side.
 */
public fun conditionImports(tree: WidgetNode): Set<String> {
  val out = LinkedHashSet<String>()
  fun cond(c: Cond) = c.terms.forEach { t -> (t.operand as? Token)?.importFq?.let(out::add) }
  fun walk(n: WidgetNode) {
    if (n.type == "Condition") condOf(n)?.let(::cond)
    n.props.values.forEach { v -> if (v is Conditional) cond(v.cond) }
    n.children.forEach(::walk)
  }
  walk(tree)
  return out
}
