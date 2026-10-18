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
   */
  @Serializable
  public data class Conditional(
    val field: String,
    val test: String? = null,
    val negate: Boolean = false,
    val whenTrue: PropValue? = null,
    val whenFalse: PropValue? = null,
  ) : PropValue
}

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
  val fields: Map<String, String> = emptyMap(), // name -> Kotlin type
  val actions: List<String> = emptyList(),
  val actionParams: Map<String, String> = emptyMap(), // action name -> single param Kotlin type
)
