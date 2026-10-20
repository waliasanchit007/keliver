package dev.keliver.portal.document

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Stable-for-the-document's-lifetime node handle. All ops target handles. */
@Serializable
@JvmInline
public value class Handle(public val v: Long)

@Serializable
public sealed interface PropValue {
  @Serializable
  public data class Lit(
    val tag: String,
    val s: String? = null,
    val i: Int? = null,
    val d: Double? = null,
    val b: Boolean? = null,
    val li: List<Int>? = null,
    val lf: List<Float>? = null,
    val ls: List<String>? = null,
  ) : PropValue

  @Serializable
  public data class Bind(val field: String) : PropValue

  /**
   * An event wired to a named handler. [arg] is what the emitted lambda passes:
   * `null` -> `{ b.name() }`; `"it"` -> the event payload `{ b.name(it) }`;
   * `"item.field"` -> item-scoped data `{ b.name(item.field) }` (P2).
   */
  @Serializable
  public data class Action(val name: String, val arg: String? = null) : PropValue

  /**
   * K2: a reference to a design-token constant (`C.Ink`) in the app's own
   * sources. [ref] is written back verbatim; [value] is the constant's value
   * resolved at ingest (what the canvas renders — tags s/i/d/b only); [import]
   * is the declaration a file using [ref] imports (null = none needed).
   * Valid only where [value]'s kind is the prop's plain literal kind
   * (`dev.keliver.portal.tokenFits`).
   */
  @Serializable
  public data class Token(val ref: String, val value: Lit, val import: String? = null) : PropValue

  /**
   * K3: a value chosen by a condition — `if (<condition>) whenTrue else
   * whenFalse` in source. The condition reads the screen binding [field]
   * with [test] (null = the Boolean itself; "empty"/"blank" = a String's
   * `isEmpty()`/`isBlank()`), negated by [negate] (`!b.x`,
   * `b.s.isNotEmpty()`) — the model a `Condition` node's props
   * (`field`/`test`/`negate`) use for conditional children
   * (`dev.keliver.portal.Cond`). Each branch is a [Lit], [Token], [Bind] or
   * [Action] of the prop's kind; null is Kotlin `null` and fits only an
   * event. Branches never nest.
   *
   * K6: [field]/[test]/[negate]/[operand] are the condition's FIRST term.
   * An equality (`test = "eq"`: `b.kind == "card"`, `!=` negated) compares
   * the field with [operand] — a [Lit] of tag s/i/b or a [Token] of one of
   * those kinds. A compound condition's further terms are [more], all joined
   * by [join] (`"or"` = `||`; null = `&&`). Every new field defaults (and is
   * left out of the JSON) to what a K3 condition is, so K3 JSON decodes to
   * the identical value.
   */
  @Serializable
  public data class Conditional(
    val field: String,
    val test: String? = null,
    val negate: Boolean = false,
    val whenTrue: PropValue? = null,
    val whenFalse: PropValue? = null,
    /** K6: an equality's operand (the first term's); null for any other test. */
    val operand: PropValue? = null,
    /** K6: the terms after the first; empty = a single-term condition. */
    val more: List<CondTerm> = emptyList(),
    /** K6: `"or"` (`||`) or null (`&&`) — how [more] joins the terms; null for a single term. */
    val join: String? = null,
  ) : PropValue

  /**
   * K4: a String template — `"Rating: $rating"`, `"${b.count} items"` in
   * source. Each of [parts] is a literal text segment (a [Lit] tagged "s",
   * its decoded value) or a reference: a [Bind] (a screen binding field, an
   * item field `item.x` inside a Repeat over `item`, or — in a component
   * body — a parameter) or a [Token] of a String, Int or Boolean kind —
   * never a Double, nor a field or parameter declared `Double`/`Float` or
   * nullable (the device, Kotlin/JS, prints `1.0` as `1` and a null as
   * `null`; the preview prints `1.0` and nothing). Canonical form
   * ([canonical]): no empty or adjacent literal segments, at least one
   * reference (a template without one is a plain [Lit]). The author's
   * spelling (`$x` / `${x}`, escapes) is not modelled: write-back keeps it
   * for every part an edit does not change. Fits a String prop or parameter.
   */
  @Serializable
  public data class Template(val parts: List<PropValue>) : PropValue

  /**
   * K4: a list whose elements may be references — `listOf(C.Start, C.End)`
   * in source. [tag] is the element kind as a [Lit] list tag: "li" (a
   * `List<Int>` prop) or "ls" (a `List<String>` prop). Each of [items] is a
   * [Lit] of that kind ("i"/"s"), a [Token] of that kind, or a [Bind].
   * Canonical form ([canonical]): at least one element is not a literal (a
   * list of literals is a plain [Lit]).
   */
  @Serializable
  public data class ListOf(val tag: String, val items: List<PropValue>) : PropValue

  /**
   * K7: an Int arithmetic value — `size / 2`, `b.columns * 48 + 8` in
   * source: ONE binary operation, [op] one of `+ - * / %`, each operand a
   * [Lit] of tag "i" (negative too; never `Int.MIN_VALUE`, which Kotlin source
   * reads as a Long), an Int [Token], a [Bind] of an Int field (a screen
   * binding, an item field `item.x` inside a Repeat over `item`, or — in a
   * component body — an Int parameter) or another [Arith]. The tree is the
   * expression as Kotlin parses it (precedence, left associativity;
   * parentheses only group). `/` and `%` take only a non-zero Int literal on
   * the right. Canonical form ([canonical]): at least one operand is not a
   * literal — an expression of literals is the plain [Lit] it computes
   * (`dev.keliver.portal.ArithOp.apply`, Kotlin's 32-bit Int arithmetic).
   * Fits an Int prop or a component's Int parameter (also as a
   * [Conditional]'s branch); never a modifier argument, a template part, a
   * list element or an equality operand.
   */
  @Serializable
  public data class Arith(val op: String, val left: PropValue, val right: PropValue) : PropValue

  /**
   * K7: an Int value used where a Dp is taken — `gap.dp` ([form] null) or
   * `Dp(gap.toDouble())` ([form] `"Dp"`), the author's spelling. [value] is
   * an [Arith], a [Bind] of an Int field or an Int [Token]; an Int literal is
   * folded into the plain Dp [Lit] ([canonical]). Fits a catalogued widget's
   * Dp prop (`Spacer.width`/`height`) — never a Margin side, a modifier
   * argument or a component parameter.
   */
  @Serializable
  public data class IntDp(val value: PropValue, val form: String? = null) : PropValue
}

/**
 * K6: one further term of a compound condition ([PropValue.Conditional.more]):
 * the same shape as the first term — a field, a test (null = the Boolean
 * itself, "empty", "blank", "eq"), a negation and an equality's [operand].
 */
@Serializable
public data class CondTerm(
  val field: String,
  val test: String? = null,
  val negate: Boolean = false,
  val operand: PropValue? = null,
)

/**
 * Convenience constructor mirroring the V1 tree's prop kinds. A Long has no
 * tag and fits no prop: it is refused rather than silently stringified (a
 * Long design token written as a Text would not compile).
 */
public fun lit(v: Any?): PropValue.Lit = when (v) {
  is Long -> throw IllegalArgumentException("a Long fits no portal prop: $v")
  is String -> PropValue.Lit("s", s = v)
  is Int -> PropValue.Lit("i", i = v)
  is Double -> PropValue.Lit("d", d = v)
  is Boolean -> PropValue.Lit("b", b = v)
  is List<*> -> when (v.firstOrNull()) {
    is Float -> PropValue.Lit("lf", lf = v.filterIsInstance<Float>())
    is String -> PropValue.Lit("ls", ls = v.filterIsInstance<String>())
    else -> PropValue.Lit("li", li = v.filterIsInstance<Int>())
  }
  else -> PropValue.Lit("s", s = v?.toString() ?: "")
}

public fun PropValue.Lit.toAny(): Any? = when (tag) {
  "s" -> s
  "i" -> i
  "d" -> d
  "b" -> b
  "li" -> li
  "lf" -> lf
  "ls" -> ls
  else -> null
}

@Serializable
public sealed interface DocNode {
  public val handle: Handle

  @Serializable
  public data class Widget(
    override val handle: Handle,
    val type: String,                                   // catalog simple name
    val props: Map<String, PropValue> = emptyMap(),
    val modifiers: Map<String, PropValue> = emptyMap(), // "Padding.allDp" -> Lit
    val children: List<DocNode> = emptyList(),
    val explicitId: String? = null,                     // id("...") — file-boundary identity (M4)
  ) : DocNode

  @Serializable
  public data class RawCode(
    override val handle: Handle,
    val text: String,                                   // verbatim source — never lost
    val kindHint: String? = null,                       // display-only heuristic
  ) : DocNode
}

@Serializable
public data class Contract(
  val fields: Map<String, String> = emptyMap(), // name -> Kotlin type ("<unknown>": declared without one)
  val actions: List<String> = emptyList(),
  val actionParams: Map<String, String> = emptyMap(), // action name -> single param Kotlin type
  /**
   * K4: why a field [fields] does not list has a type the portal cannot read
   * in the screen's file — or null (the default, and what JSON without it
   * decodes as) when the screen's Bindings interface is read whole there, so
   * an unlisted field is new and contract write-back declares it. Set by
   * ingest when the screen has no Bindings parameter, takes a type not
   * declared in its file, or takes an interface extending a type declared in
   * another file (an unlisted field may be inherited from it, with any type).
   * The document then refuses a template reading an unlisted field, as it
   * refuses one reading a field [fields] types `<unknown>`. The file decides
   * it: [DocOp.ContractEdit] keeps it.
   */
  val unlistedFieldsUnknown: String? = null,
) {
  /**
   * The 0.3.x constructor, kept for callers compiled against it (binary
   * compatible: its plain and defaulted JVM signatures). Hidden from source:
   * a call with three arguments is the primary constructor's.
   */
  @Deprecated("The 0.3.x signature, kept for compiled callers", level = DeprecationLevel.HIDDEN)
  public constructor(
    fields: Map<String, String> = emptyMap(),
    actions: List<String> = emptyList(),
    actionParams: Map<String, String> = emptyMap(),
  ) : this(fields, actions, actionParams, null)

  /**
   * The 0.3.x `copy`, kept for callers compiled against it (its plain and
   * `copy$default` JVM signatures). It keeps [unlistedFieldsUnknown], as the
   * `copy` a recompiled caller gets does. Hidden from source.
   */
  @Deprecated("The 0.3.x signature, kept for compiled callers", level = DeprecationLevel.HIDDEN)
  public fun copy(
    fields: Map<String, String> = this.fields,
    actions: List<String> = this.actions,
    actionParams: Map<String, String> = this.actionParams,
  ): Contract = Contract(fields, actions, actionParams, unlistedFieldsUnknown)
}
