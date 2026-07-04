package dev.keliver.portal.document

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Stable-for-the-document's-lifetime node handle. All ops target handles. */
@Serializable
@JvmInline
value class Handle(val v: Long)

@Serializable
sealed interface PropValue {
  @Serializable
  data class Lit(
    val tag: String,
    val s: String? = null,
    val i: Int? = null,
    val d: Double? = null,
    val b: Boolean? = null,
    val li: List<Int>? = null,
    val lf: List<Float>? = null,
  ) : PropValue

  @Serializable
  data class Bind(val field: String) : PropValue

  @Serializable
  data class Action(val name: String) : PropValue
}

/** Convenience constructor mirroring the V1 tree's prop kinds. */
fun lit(v: Any?): PropValue.Lit = when (v) {
  is String -> PropValue.Lit("s", s = v)
  is Int -> PropValue.Lit("i", i = v)
  is Double -> PropValue.Lit("d", d = v)
  is Boolean -> PropValue.Lit("b", b = v)
  is List<*> -> if (v.firstOrNull() is Float) {
    PropValue.Lit("lf", lf = v.filterIsInstance<Float>())
  } else {
    PropValue.Lit("li", li = v.filterIsInstance<Int>())
  }
  else -> PropValue.Lit("s", s = v?.toString() ?: "")
}

fun PropValue.Lit.toAny(): Any? = when (tag) {
  "s" -> s
  "i" -> i
  "d" -> d
  "b" -> b
  "li" -> li
  "lf" -> lf
  else -> null
}

@Serializable
sealed interface DocNode {
  val handle: Handle

  @Serializable
  data class Widget(
    override val handle: Handle,
    val type: String,                                   // catalog simple name
    val props: Map<String, PropValue> = emptyMap(),
    val modifiers: Map<String, PropValue> = emptyMap(), // "Padding.allDp" -> Lit
    val children: List<DocNode> = emptyList(),
    val explicitId: String? = null,                     // id("...") — file-boundary identity (M4)
  ) : DocNode

  @Serializable
  data class RawCode(
    override val handle: Handle,
    val text: String,                                   // verbatim source — never lost
    val kindHint: String? = null,                       // display-only heuristic
  ) : DocNode
}

@Serializable
data class Contract(
  val fields: Map<String, String> = emptyMap(), // name -> Kotlin type
  val actions: List<String> = emptyList(),
)
