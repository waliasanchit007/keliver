/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.compose.generator

/**
 * The Compose value types with a hand-written mirror and codec (docs/COMPOSE_GENERATOR_DESIGN.md §6).
 * Every other type is unmappable: a parameter of that type with a default is left out of the stub
 * (and kept in an ERROR overload); a required one makes the whole declaration an error stub.
 */
object TypeMap {
  private val mirrored = setOf(
    "androidx.compose.ui.unit.Dp",
    "androidx.compose.ui.unit.TextUnit",
    "androidx.compose.ui.graphics.Color",
    "androidx.compose.ui.graphics.Shape",
    "androidx.compose.ui.graphics.FilterQuality",
    "androidx.compose.ui.text.TextStyle",
    "androidx.compose.ui.text.font.FontWeight",
    "androidx.compose.ui.text.font.FontStyle",
    "androidx.compose.ui.text.font.FontFamily",
    "androidx.compose.ui.text.style.TextAlign",
    "androidx.compose.ui.text.style.TextOverflow",
    "androidx.compose.ui.text.style.TextDecoration",
    "androidx.compose.ui.Alignment",
    "androidx.compose.ui.Alignment.Horizontal",
    "androidx.compose.ui.Alignment.Vertical",
    "androidx.compose.foundation.layout.Arrangement.Horizontal",
    "androidx.compose.foundation.layout.Arrangement.Vertical",
    "androidx.compose.foundation.layout.PaddingValues",
    "androidx.compose.foundation.ScrollState",
    "androidx.compose.ui.layout.ContentScale",
    "androidx.compose.ui.graphics.vector.ImageVector",
    "androidx.compose.ui.semantics.Role",
    // Gate B, part a.
    "androidx.compose.foundation.BorderStroke",
    "androidx.compose.foundation.lazy.LazyListState",
    "androidx.compose.ui.graphics.painter.Painter",
    "androidx.compose.ui.graphics.ColorFilter",
    // Gate B-e: enter and exit transitions (AnimatedVisibility), sent as the parts they were built from.
    "androidx.compose.animation.EnterTransition",
    "androidx.compose.animation.ExitTransition",
  )

  /**
   * Generic Compose types sent as one non-generic wire class (Gate B-e): the guest writes Compose's
   * type with its arguments (`FiniteAnimationSpec<IntSize>`), the stub sends `.wire`, and the host
   * rebuilds the real value for the parameter's type arguments (`toCompose<IntSize>()`).
   */
  val generic: Map<String, String> = mapOf(
    "androidx.compose.animation.core.FiniteAnimationSpec" to "dev.keliver.compose.animation.core.AnimationSpecWire",
    "androidx.compose.animation.core.AnimationSpec" to "dev.keliver.compose.animation.core.AnimationSpecWire",
  )
  private val primitives = setOf("kotlin.String", "kotlin.Boolean", "kotlin.Int", "kotlin.Float", "kotlin.Long", "kotlin.Double")

  /** Types only a `*Defaults` factory makes (`ButtonColors`): generated `DefaultsValue` classes. */
  val opaque: MutableSet<String> = sortedSetOf()

  /** Host-owned state (design §10): the guest's object is a handle, always sent (never "the default"). */
  val hostState: Set<String> = setOf("androidx.compose.foundation.ScrollState", "androidx.compose.foundation.lazy.LazyListState")

  /**
   * A mutable guest handle travels as the immutable value its `wire` property reads (so reading it
   * subscribes the stub to new commands, and a new command re-sends the property).
   */
  val wireValue: Map<String, String> = mapOf(
    "androidx.compose.foundation.lazy.LazyListState" to "dev.keliver.compose.foundation.lazy.LazyListStateWire",
  )

  /** Types whose wire form can say "explicitly null" (design §5's wrap). */
  fun hasExplicitNull(t: TypeRef): Boolean = t.fq == "androidx.compose.foundation.BorderStroke" || t.fq in opaque

  /** Compose's `model: Any?` (Coil): a URL string on the wire. */
  const val IMAGE_MODEL = "kotlin.Any"

  const val LAZY_LIST_SCOPE = "androidx.compose.foundation.lazy.LazyListScope"

  fun isMapped(t: TypeRef): Boolean = !t.isFunction && (
    (t.args.isEmpty() && (t.fq in mirrored || t.fq in primitives || t.fq == IMAGE_MODEL || t.fq in opaque)) ||
      (t.fq in generic && t.args.all { isMapped(it) || it.fq in genericArgs })
    )
  fun isPrimitive(t: TypeRef): Boolean = t.fq in primitives

  /** Type arguments a generic wire type may carry that are not themselves sent (`IntSize`). */
  private val genericArgs = setOf("androidx.compose.ui.unit.IntSize", "androidx.compose.ui.unit.IntOffset", "androidx.compose.ui.unit.Dp", "kotlin.Float")

  /** The value a stub sends for a parameter of this type: `.wire` for a handle or a generic type. */
  fun sendExpr(t: TypeRef, name: String): String = if (t.fq in wireValue || t.fq in generic) "$name.wire" else name

  /** The type as the guest and the schema write it. */
  fun wireType(t: TypeRef, nullable: Boolean): String = when (t.fq) {
    IMAGE_MODEL -> "kotlin.String?"
    else -> (wireValue[t.fq] ?: generic[t.fq] ?: mirrorFq(t.fq)) + if (nullable) "?" else ""
  }

  /** Mirrors written by hand in keliver-compose-values that never travel alone: no placeholder for them. */
  val handWritten: Set<String> = setOf("androidx.compose.ui.unit.IntSize", "androidx.compose.ui.unit.IntOffset")

  /** Parameter types an animated reference can stand for (`animate*AsState`, Gate B-e). */
  val animatable: Set<String> = setOf("kotlin.Float", "androidx.compose.ui.unit.Dp", "androidx.compose.ui.graphics.Color")
}

/**
 * MODIFIER: the modifier chain. SLOT: a composable lambda (children). EVENT: a guest lambda the host
 * calls. VALUE: a mirrored value. LAZY: a lazy list's `LazyListScope.() -> Unit` content, windowed
 * (design §16). DROP: left out of the stub (kept in an ERROR overload). BLOCK: makes the whole
 * declaration an ERROR stub.
 */
enum class ParamKind { MODIFIER, SLOT, EVENT, VALUE, LAZY, DROP, BLOCK }

/** Case 1, 2 or 3 of docs/COMPOSE_GENERATOR_DESIGN.md §5 for a VALUE parameter with a default. */
enum class DefaultCase { NONE, LITERAL, EXPRESSION }

data class Classified(
  val param: ParamInfo,
  val kind: ParamKind,
  val defaultCase: DefaultCase,
  val reason: String? = null,
  /** A nullable parameter with a non-null default: an explicit `null` travels as a marker (design §5). */
  val explicitNull: Boolean = false,
)

private val literalPatterns = listOf(
  Regex("""null|true|false"""),
  Regex("""-?\d+(\.\d+)?[fFL]?"""),
  Regex("""\d+(\.\d+)?\.(dp|sp|em)"""),
  Regex("""Int\.MAX_VALUE|Int\.MIN_VALUE|Float\.NaN"""),
  Regex("""(Color|Dp|TextUnit|TextAlign|TextOverflow|TextDecoration|FontWeight|FontStyle|Alignment|Arrangement|ContentScale|FilterQuality)\.[A-Z][A-Za-z]*"""),
  Regex("""RectangleShape|CircleShape"""),
)

fun isLiteralDefault(text: String): Boolean = literalPatterns.any { it.matches(text.trim()) }

/**
 * Classifies [decl]'s parameters. [unsupportedDefault] says why a default expression cannot be
 * copied to the guest (it names a `*Defaults` member with no mirror), or null when it can.
 */
fun classify(decl: DeclInfo, unsupportedDefault: (String) -> String? = { null }): List<Classified> = decl.params.map { p ->
  val t = p.type
  val defaultProblem = p.defaultText?.takeIf { p.hasDefault && !isLiteralDefault(it) }?.let(unsupportedDefault)
  val kind = when {
    p.name == "modifier" && t.fq == MODIFIER -> ParamKind.MODIFIER
    t.isFunction && t.composable && t.functionParams.isEmpty() && t.functionReturn?.fq == "kotlin.Unit" && !t.nullable && !t.suspend ->
      ParamKind.SLOT
    t.isFunction && !t.composable && !t.suspend && t.receiver?.fq == TypeMap.LAZY_LIST_SCOPE && t.functionParams.isEmpty() &&
      t.functionReturn?.fq == "kotlin.Unit" && decl.kind == DeclKind.WIDGET -> ParamKind.LAZY
    t.isFunction &&
      !t.composable &&
      !t.suspend &&
      t.receiver == null &&
      t.functionReturn?.fq == "kotlin.Unit" &&
      t.functionParams.size <= 1 &&
      t.functionParams.all { TypeMap.isPrimitive(it) } -> ParamKind.EVENT
    // Redwood's modifier serializers cannot take a nullable property (KotlinPoet writes `T?::class`).
    TypeMap.isMapped(t) && t.nullable && decl.kind != DeclKind.WIDGET && p.hasDefault -> ParamKind.DROP
    defaultProblem != null -> ParamKind.DROP
    // A nullable parameter whose default is not null needs an explicit-null marker on the wire.
    TypeMap.isMapped(t) && t.nullable && p.hasDefault && p.defaultText != "null" && !TypeMap.hasExplicitNull(t) -> ParamKind.DROP
    TypeMap.isMapped(t) -> ParamKind.VALUE
    p.hasDefault -> ParamKind.DROP
    else -> ParamKind.BLOCK
  }
  val case = when {
    kind != ParamKind.VALUE || !p.hasDefault -> DefaultCase.NONE
    t.fq in TypeMap.hostState -> DefaultCase.LITERAL // a handle: always sent
    isLiteralDefault(p.defaultText ?: error("${decl.spec}: ${p.name} has a default but no source text")) -> DefaultCase.LITERAL
    else -> DefaultCase.EXPRESSION
  }
  val reason = when {
    kind == ParamKind.DROP && defaultProblem != null -> "default: $defaultProblem"
    kind == ParamKind.DROP && TypeMap.isMapped(t) && t.nullable && decl.kind == DeclKind.WIDGET -> "nullable with a non-null default"
    kind == ParamKind.DROP || kind == ParamKind.BLOCK -> t.canonical()
    else -> null
  }
  Classified(p, kind, case, reason, explicitNull = kind == ParamKind.VALUE && t.nullable && case == DefaultCase.EXPRESSION)
}

/** Types an error stub's signature names that have no mirror: placeholder classes are generated. */
fun unmappedTypes(t: TypeRef): List<String> = buildList {
  fun walk(x: TypeRef) {
    if (x.isFunction) {
      x.receiver?.let(::walk)
      x.args.forEach(::walk)
    } else if (!x.fq.startsWith("kotlin.") && !TypeMap.isMapped(x) && x.fq != MODIFIER && x.fq !in TypeMap.handWritten) {
      add(x.fq)
      x.args.forEach(::walk)
    }
  }
  walk(t)
}
