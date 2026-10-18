package dev.keliver.portal

/**
 * K3: the grammar's conditions — ONE model shared by conditional children
 * (a `Condition` node: `if (…) { … } else { … }`) and conditional prop values
 * (`colorArgb = if (selected) C.A else C.B`).
 *
 * A condition reads one [field] — a screen binding (`b.selected`) or, inside a
 * project component's body, one of its parameters (`selected`) — and applies
 * one [test], optionally negated. Every form has exactly ONE spelling, so the
 * source a condition came from is the source it prints:
 *
 * | test    | negate = false        | negate = true            |
 * |---------|-----------------------|--------------------------|
 * | [Bool]  | `x`                   | `!x`                     |
 * | [Empty] | `x.isEmpty()`         | `x.isNotEmpty()`         |
 * | [Blank] | `x.isBlank()`         | `x.isNotBlank()`         |
 * | [Null]  | `x == null`           | `x != null`              |
 *
 * [Null] is accepted only inside a component body, where the parameter's
 * declared type says it is nullable; a screen's contract is derived from its
 * tree and has no way to type a nullable field.
 */
public enum class CondTest(
  /** The wire name (`Condition` node prop `test`, `PropValue.Conditional.test`); null = Bool. */
  public val wire: String?,
) {
  Bool(null),
  Empty("empty"),
  Blank("blank"),
  Null("null"),
  ;

  public companion object {
    /** The test named [wire] (null/blank = [Bool]); null for an unknown name. */
    public fun fromWire(wire: String?): CondTest? =
      if (wire.isNullOrEmpty()) Bool else entries.firstOrNull { it.wire == wire }
  }
}

/** One grammar condition; see [CondTest]. */
public data class Cond(val field: String, val test: CondTest = CondTest.Bool, val negate: Boolean = false) {
  /**
   * This condition as Kotlin source, [ref] turning the field into its
   * reference (`b.selected`, `selected`, `item.flag`).
   */
  public fun kotlin(ref: (String) -> String): String {
    val r = ref(field)
    return when (test) {
      CondTest.Bool -> if (negate) "!$r" else r
      CondTest.Empty -> if (negate) "$r.isNotEmpty()" else "$r.isEmpty()"
      CondTest.Blank -> if (negate) "$r.isNotBlank()" else "$r.isBlank()"
      CondTest.Null -> if (negate) "$r != null" else "$r == null"
    }
  }

  /**
   * Whether the condition holds when the field's value is [value] ([present]
   * = the value is known). An UNKNOWN value — an unmocked binding, a
   * parameter with neither an argument nor a default — reads the way an
   * unmocked binding renders: a Boolean as true (as conditional children
   * always have), a String as a non-empty, non-blank placeholder, anything as
   * non-null. A String value (a preview mock) is read as its kind: `"false"`
   * is false, `""` is empty.
   */
  public fun holds(value: Any?, present: Boolean = true): Boolean {
    val v = tokenValue(value)
    val raw = when (test) {
      CondTest.Bool -> if (!present) true else when (v) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: true
        else -> true
      }
      CondTest.Empty -> present && v is String && v.isEmpty()
      CondTest.Blank -> present && v is String && v.isBlank()
      CondTest.Null -> present && v == null
    }
    return raw != negate
  }

  /** The Kotlin type the field must have for this test (a screen contract field's type). */
  public val fieldType: String
    get() = when (test) {
      CondTest.Bool -> "Boolean"
      CondTest.Empty, CondTest.Blank -> "String"
      CondTest.Null -> "Any?"
    }
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

/** The [Cond] a `Condition` node tests (props `field`, `test`, `negate`), or null when it has no field. */
public fun condOf(node: WidgetNode): Cond? {
  val field = (node.props["field"] as? String)?.takeIf { it.isNotBlank() } ?: return null
  val test = CondTest.fromWire(node.props["test"] as? String) ?: return null
  return Cond(field, test, node.props["negate"] == true)
}

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
