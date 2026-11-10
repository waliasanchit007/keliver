/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
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
  )
  private val primitives = setOf("kotlin.String", "kotlin.Boolean", "kotlin.Int", "kotlin.Float", "kotlin.Long", "kotlin.Double")

  /** Compose's `model: Any?` (Coil): a URL string on the wire. */
  const val IMAGE_MODEL = "kotlin.Any"

  fun isMapped(t: TypeRef): Boolean = !t.isFunction && t.args.isEmpty() && (t.fq in mirrored || t.fq in primitives || t.fq == IMAGE_MODEL)
  fun isPrimitive(t: TypeRef): Boolean = t.fq in primitives

  /** The type as the guest and the schema write it. */
  fun wireType(t: TypeRef, nullable: Boolean): String = when (t.fq) {
    IMAGE_MODEL -> "kotlin.String?"
    else -> mirrorFq(t.fq) + if (nullable) "?" else ""
  }
}

enum class ParamKind { MODIFIER, SLOT, EVENT, VALUE, DROP, BLOCK }

/** Case 1, 2 or 3 of docs/COMPOSE_GENERATOR_DESIGN.md §5 for a VALUE parameter with a default. */
enum class DefaultCase { NONE, LITERAL, EXPRESSION }

data class Classified(val param: ParamInfo, val kind: ParamKind, val defaultCase: DefaultCase, val reason: String? = null)

private val literalPatterns = listOf(
  Regex("""null|true|false"""),
  Regex("""-?\d+(\.\d+)?[fFL]?"""),
  Regex("""\d+(\.\d+)?\.(dp|sp|em)"""),
  Regex("""Int\.MAX_VALUE|Int\.MIN_VALUE|Float\.NaN"""),
  Regex("""(Color|Dp|TextUnit|TextAlign|TextOverflow|TextDecoration|FontWeight|FontStyle|Alignment|Arrangement|ContentScale|FilterQuality)\.[A-Z][A-Za-z]*"""),
  Regex("""RectangleShape|CircleShape"""),
)

fun isLiteralDefault(text: String): Boolean = literalPatterns.any { it.matches(text.trim()) }

fun classify(decl: DeclInfo): List<Classified> = decl.params.map { p ->
  val t = p.type
  val kind = when {
    p.name == "modifier" && t.fq == MODIFIER -> ParamKind.MODIFIER
    t.isFunction && t.composable && t.functionParams.isEmpty() && t.functionReturn?.fq == "kotlin.Unit" && !t.nullable && !t.suspend ->
      ParamKind.SLOT
    t.isFunction && !t.composable && !t.suspend && t.receiver == null && t.functionReturn?.fq == "kotlin.Unit" &&
      t.functionParams.size <= 1 && t.functionParams.all { TypeMap.isPrimitive(it) } -> ParamKind.EVENT
    // Redwood's modifier serializers cannot take a nullable property (KotlinPoet writes `T?::class`).
    TypeMap.isMapped(t) && t.nullable && decl.kind != DeclKind.WIDGET && p.hasDefault -> ParamKind.DROP
    TypeMap.isMapped(t) -> ParamKind.VALUE
    p.hasDefault -> ParamKind.DROP
    else -> ParamKind.BLOCK
  }
  val case = when {
    kind != ParamKind.VALUE || !p.hasDefault -> DefaultCase.NONE
    isLiteralDefault(p.defaultText ?: error("${decl.spec}: ${p.name} has a default but no source text")) -> DefaultCase.LITERAL
    else -> DefaultCase.EXPRESSION
  }
  Classified(p, kind, case, reason = if (kind == ParamKind.DROP || kind == ParamKind.BLOCK) t.canonical() else null)
}

/** Types an error stub's signature names that have no mirror: placeholder classes are generated. */
fun unmappedTypes(t: TypeRef): List<String> = buildList {
  fun walk(x: TypeRef) {
    if (x.isFunction) {
      x.receiver?.let(::walk)
      x.args.forEach(::walk)
    } else if (!x.fq.startsWith("kotlin.") && !TypeMap.isMapped(x) && x.fq != MODIFIER) {
      add(x.fq)
      x.args.forEach(::walk)
    }
  }
  walk(t)
}
