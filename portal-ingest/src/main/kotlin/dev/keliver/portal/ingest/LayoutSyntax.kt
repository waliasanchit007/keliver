package dev.keliver.portal.ingest

import dev.keliver.portal.MARGIN_SIDES
import dev.keliver.portal.ModifierSpec
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.toAny
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.parseKotlinStringLiteral

/** One argument of a call, with its trimmed text's offsets in the text it was parsed from. */
internal data class SourceArg(val name: String?, val value: String, val start: Int, val end: Int)

/**
 * Splits the argument text between a call's parens into [SourceArg]s.
 * [base] is the offset of [inner] in the caller's text. A trailing comma is
 * allowed; an empty argument anywhere else is not (null).
 */
internal fun splitArgs(inner: String, base: Int): List<SourceArg>? {
  val pieces = mutableListOf<Pair<Int, Int>>()
  var depth = 0
  var start = 0
  var i = 0
  while (i < inner.length) {
    when (val c = inner[i]) {
      '"' -> i = skipString(inner, i) ?: return null
      '(', '[', '{' -> depth++
      ')', ']', '}' -> { depth--; if (depth < 0) return null }
      ',' -> if (depth == 0) { pieces += start to i; start = i + 1 }
      else -> if (c == '/' && i + 1 < inner.length && (inner[i + 1] == '/' || inner[i + 1] == '*')) return null // comments are not grammar
    }
    i++
  }
  if (depth != 0) return null
  pieces += start to inner.length
  val args = mutableListOf<SourceArg>()
  for ((idx, piece) in pieces.withIndex()) {
    val raw = inner.substring(piece.first, piece.second)
    if (raw.isBlank()) {
      if (idx == pieces.lastIndex && idx > 0) continue // trailing comma
      if (pieces.size == 1) return emptyList() // `()`
      return null
    }
    val lead = raw.length - raw.trimStart().length
    val text = raw.trim()
    val s = base + piece.first + lead
    val named = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)\\s*").find(text)
    if (named != null) {
      val off = named.range.last + 1
      args += SourceArg(named.groupValues[1], text.substring(off), s + off, s + text.length)
    } else {
      args += SourceArg(null, text, s, s + text.length)
    }
  }
  return args
}

/** Index of the closing quote of the string starting at [open]; null when unterminated or raw. */
private fun skipString(t: String, open: Int): Int? {
  if (t.startsWith("\"\"\"", open)) return null // raw strings are not grammar here
  var i = open + 1
  while (i < t.length) {
    when (t[i]) {
      '\\' -> i++
      '"' -> return i
    }
    i++
  }
  return null
}

/**
 * K1: `dev.keliver.ui.Margin` values in source. Accepted (all exact):
 * `Margin.Zero`, `Margin()`, `Margin(all = X)`, `Margin(horizontal = X,
 * vertical = Y)` (either alone), any named subset of `start`/`end`/`top`/
 * `bottom` (the class constructor), and POSITIONAL forms as Kotlin's overload
 * resolution reads them (the candidate leaving the fewest defaults unused wins):
 * `Margin(X)` = `Margin(all = X)`, `Margin(X, Y)` = `Margin(horizontal = X,
 * vertical = Y)`, and 3 or 4 values = the class constructor's start, end, top,
 * bottom (its only candidate). A compiled fixture (ParityLayout.kt) pins each
 * reading. X is `N.dp`, `N.M.dp` or `Dp(N.M)` (`Dp(N)` with an Int literal
 * does not compile: Dp takes a Double). Mixed positional + named is not grammar.
 */
internal object MarginSyntax {
  /** start, end, top, bottom (dp) — null when [text] is not a Margin in the grammar. */
  fun parse(text: String): DoubleArray? = parseArgs(text.trim())?.let { (form, args) -> sides(form, args) }

  /** A Dp literal's value: `16.dp`, `16.5.dp`, `Dp(16.0)`. Never negative. */
  fun parseDp(text: String): Double? {
    val t = text.trim()
    Regex("^(\\d+(?:\\.\\d+)?)\\.dp$").find(t)?.let { return it.groupValues[1].toDouble() }
    Regex("^Dp\\(\\s*(\\d+\\.\\d+)\\s*\\)$").find(t)?.let { return it.groupValues[1].toDouble() }
    return null
  }

  private enum class Form { ZERO, ALL, AXES, SIDES }

  /** The recognized form + its args keyed by LOGICAL name ("all", "horizontal", "start", …). */
  private fun parseArgs(t: String): Pair<Form, List<Pair<String, SourceArg>>>? {
    if (t == "Margin.Zero") return Form.ZERO to emptyList()
    if (!t.startsWith("Margin(") || !t.endsWith(")")) return null
    val args = splitArgs(t.substring(7, t.length - 1), 7) ?: return null
    if (args.isEmpty()) return Form.ZERO to emptyList()
    if (args.any { parseDp(it.value) == null }) return null
    if (args.all { it.name == null }) {
      return when (args.size) {
        1 -> Form.ALL to listOf("all" to args[0])
        2 -> Form.AXES to listOf("horizontal" to args[0], "vertical" to args[1])
        3, 4 -> Form.SIDES to args.mapIndexed { i, a -> MARGIN_SIDES[i] to a }
        else -> null
      }
    }
    if (args.any { it.name == null }) return null // mixed positional + named
    val names = args.map { it.name!! }
    if (names.toSet().size != names.size) return null
    val named = args.map { it.name!! to it }
    return when {
      names == listOf("all") -> Form.ALL to named
      names.all { it == "horizontal" || it == "vertical" } -> Form.AXES to named
      names.all { it in MARGIN_SIDES } -> Form.SIDES to named
      else -> null
    }
  }

  private fun sides(form: Form, args: List<Pair<String, SourceArg>>): DoubleArray {
    val v = args.associate { (name, arg) -> name to parseDp(arg.value)!! }
    return when (form) {
      Form.ZERO -> DoubleArray(4)
      Form.ALL -> DoubleArray(4) { v.getValue("all") }
      Form.AXES -> {
        val h = v["horizontal"] ?: 0.0
        val vv = v["vertical"] ?: 0.0
        doubleArrayOf(h, h, vv, vv)
      }
      Form.SIDES -> DoubleArray(4) { v[MARGIN_SIDES[it]] ?: 0.0 }
    }
  }

  /**
   * Surgical edit of a Margin expression: rewrite only the VALUE texts of the
   * arguments whose side changed, keeping the author's form and Dp style.
   * Null when the change does not fit the existing form (e.g. a side that is
   * not written, `Margin.Zero`, or `all = X` with now-unequal sides) — the
   * caller then replaces the whole expression with the canonical one.
   */
  fun surgical(text: String, old: DoubleArray, new: DoubleArray): String? {
    val (form, args) = parseArgs(text) ?: return null
    fun arg(name: String) = args.firstOrNull { it.first == name }?.second
    val edits = mutableListOf<Pair<SourceArg, Double>>()
    when (form) {
      Form.ZERO -> return null
      Form.ALL -> {
        if (new.any { it != new[0] }) return null
        edits += arg("all")!! to new[0]
      }
      Form.AXES -> {
        if (new[0] != new[1] || new[2] != new[3]) return null
        for ((axis, idx) in listOf("horizontal" to 0, "vertical" to 2)) {
          if (old[idx] == new[idx]) continue
          edits += (arg(axis) ?: return null) to new[idx]
        }
      }
      Form.SIDES -> {
        for ((idx, side) in MARGIN_SIDES.withIndex()) {
          if (old[idx] == new[idx]) continue
          edits += (arg(side) ?: return null) to new[idx]
        }
      }
    }
    var out = text
    for ((a, v) in edits.sortedByDescending { it.first.start }) {
      out = out.substring(0, a.start) + dpLike(a.value, v) + out.substring(a.end)
    }
    return out
  }

  /**
   * [v] written in the same Dp style as [previous] (`N.dp` or `Dp(N.0)`),
   * clamped at 0 (Dp is non-negative) and never in exponent notation.
   */
  fun dpLike(previous: String, v: Double): String {
    val c = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0
    return if (previous.trim().startsWith("Dp(")) {
      "Dp(${dev.keliver.portal.kotlinDoubleLiteral(c)})"
    } else {
      dev.keliver.portal.kotlinDpLiteral(c)
    }
  }
}

/** One `.name(args)` link of a `Modifier.…` chain; offsets are into the chain text. */
internal data class ChainSegment(val ext: String, val args: List<SourceArg>)

/** A segment resolved against the catalog: its spec and the wire props it contributes. */
internal data class ResolvedSegment(val segment: ChainSegment, val spec: ModifierSpec, val props: Map<String, PropValue>)

/**
 * K1: `Modifier.a(…).b(…)` chains, with nested parens and typed arguments
 * (`Dp`, `CrossAxisAlignment.X`, `Margin(…)`), resolved against the node's
 * PARENT. Kotlin resolves an implicit receiver's member extension (a layout
 * scope's `flex`, `size(Dp, Dp)`, …) before imported top-level extensions,
 * but an INAPPLICABLE member does not hide an applicable top-level one — so
 * `Modifier.size(12, 12)` inside a Row is still material's `size(Int, Int)`,
 * while `Modifier.size(12.dp, 12.dp)` is the Row scope's. A scoped modifier
 * whose parent is not one of its scopes is not grammar.
 */
internal object ModifierChain {
  /** Null when [text] is not a plain `Modifier(.ext(args))*` chain. */
  fun segments(text: String): List<ChainSegment>? {
    var i = skipWs(text, 0)
    if (!text.startsWith("Modifier", i)) return null
    i += "Modifier".length
    val out = mutableListOf<ChainSegment>()
    while (true) {
      i = skipWs(text, i)
      if (i >= text.length) return out
      if (text[i] != '.') return null
      i = skipWs(text, i + 1)
      val identStart = i
      while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
      if (i == identStart || !text[identStart].let { it.isLetter() || it == '_' }) return null
      val ext = text.substring(identStart, i)
      i = skipWs(text, i)
      if (i >= text.length || text[i] != '(') return null
      val close = matchingParen(text, i) ?: return null
      val args = splitArgs(text.substring(i + 1, close), i + 1) ?: return null
      out += ChainSegment(ext, args)
      i = close + 1
    }
  }

  /**
   * Resolve every segment; null when any is outside the grammar or a modifier
   * repeats. [tokens] (K2) resolves design-token arguments (`background(C.Ink)`).
   */
  fun resolve(segments: List<ChainSegment>, parentType: String?, tokens: TokenLookup? = null): List<ResolvedSegment>? {
    val out = segments.map { resolveSegment(it, parentType, tokens) ?: return null }
    if (out.map { it.spec.name }.toSet().size != out.size) return null // a repeat would be lossy
    return out
  }

  fun resolveSegment(seg: ChainSegment, parentType: String?, tokens: TokenLookup? = null): ResolvedSegment? {
    val candidates = modifierSpecs.filter { it.extension == seg.ext }
    val scoped = candidates.filter { it.scopes.isNotEmpty() && parentType != null && parentType in it.scopes }
    for (spec in scoped + candidates.filter { it.scopes.isEmpty() }) {
      parseArgs(spec, seg.args, tokens)?.let { return ResolvedSegment(seg, spec, it) }
    }
    return null
  }

  /** The wire props one spec argument occupies (four for a Margin). */
  fun argKeys(spec: ModifierSpec): List<List<String>> {
    if (spec.argTypes.isEmpty()) return spec.props.map { listOf("${spec.name}.${it.name}") }
    val out = mutableListOf<List<String>>()
    var cursor = 0
    for (type in spec.argTypes) {
      if (type == "Margin") {
        val base = spec.props[cursor].name.substringBeforeLast('.')
        out += MARGIN_SIDES.map { "${spec.name}.$base.$it" }
        cursor += 4
      } else {
        out += listOf("${spec.name}.${spec.props[cursor].name}")
        cursor++
      }
    }
    return out
  }

  private fun parseArgs(spec: ModifierSpec, args: List<SourceArg>, tokens: TokenLookup?): Map<String, PropValue>? {
    if (spec.props.isEmpty()) return if (args.isEmpty()) mapOf(spec.name to PropValue.Lit("b", b = true)) else null
    if (args.any { it.name != null }) return null // positional only (what the exporter writes)
    val keys = argKeys(spec)
    if (args.size != keys.size) return null
    val out = LinkedHashMap<String, PropValue>()
    for ((j, arg) in args.withIndex()) {
      val type = spec.argTypes.getOrNull(j)
      if (type == "Margin") {
        val sides = MarginSyntax.parse(arg.value) ?: return null
        keys[j].forEachIndexed { s, k -> out[k] = PropValue.Lit("d", d = sides[s]) }
      } else {
        out[keys[j].single()] = typedLiteral(type, arg.value)
          // K2: a design token of exactly the argument's plain literal type.
          ?: tokens?.lookup(arg.value)?.takeIf { dev.keliver.portal.tokenFits(type, it.value.toAny()) }
          ?: return null
      }
    }
    return out
  }

  /** An argument literal of Kotlin source type [type] (null type = legacy untyped catalog). */
  fun typedLiteral(type: String?, t: String): PropValue.Lit? = when (type) {
    "Int" -> t.takeIf { Regex("^-?\\d+$").matches(it) }?.toIntOrNull()?.let { PropValue.Lit("i", i = it) }
    "Double" -> t.takeIf { Regex("^-?\\d+\\.\\d+$").matches(it) }?.let { PropValue.Lit("d", d = it.toDouble()) }
    "Float" -> t.takeIf { Regex("^-?\\d+(?:\\.\\d+)?[fF]$").matches(it) }
      ?.let { PropValue.Lit("d", d = it.dropLast(1).toDouble()) }
    "Boolean" -> when (t) { "true" -> PropValue.Lit("b", b = true); "false" -> PropValue.Lit("b", b = false); else -> null }
    "String" -> if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) parseKotlinStringLiteral(t)?.let { PropValue.Lit("s", s = it) } else null
    "Dp" -> MarginSyntax.parseDp(t)?.let { PropValue.Lit("d", d = it) }
    "CrossAxisAlignment" -> enumOrdinal(t, "CrossAxisAlignment", listOf("Start", "Center", "End", "Stretch"))
    "MainAxisAlignment" -> enumOrdinal(t, "MainAxisAlignment", listOf("Start", "Center", "End", "SpaceBetween", "SpaceAround", "SpaceEvenly"))
    "Constraint" -> enumOrdinal(t, "Constraint", listOf("Wrap", "Fill"))
    "Overflow" -> enumOrdinal(t, "Overflow", listOf("Clip", "Scroll"))
    null -> Recognizer.parseLiteral(t)
    else -> null
  }

  private fun enumOrdinal(t: String, cls: String, names: List<String>): PropValue.Lit? {
    if (!t.startsWith("$cls.")) return null
    return names.indexOf(t.removePrefix("$cls.")).takeIf { it >= 0 }?.let { PropValue.Lit("i", i = it) }
  }

  /**
   * Surgical edit of a chain whose modifier SET is unchanged: only the
   * arguments whose values changed are rewritten (a Margin argument keeps its
   * form where it can). Null when the set changed or the text does not
   * resolve — the caller then replaces the whole chain with [canonical].
   */
  fun surgical(
    text: String,
    old: Map<String, PropValue>,
    new: Map<String, PropValue>,
    parentType: String?,
    canonical: String?,
    tokens: TokenLookup? = null,
  ): String? {
    val names = { m: Map<String, PropValue> -> m.keys.map { it.substringBefore('.') }.toSet() }
    if (names(old) != names(new)) return canonical
    val source = segments(text)?.let { resolve(it, parentType, tokens) } ?: return canonical
    val canon = canonical?.let { c -> segments(c)?.let { resolve(it, parentType, tokens) } }
    val edits = mutableListOf<Pair<SourceArg, String>>()
    for (seg in source) {
      val spec = seg.spec
      val keys = argKeys(spec)
      for ((j, argKeys) in keys.withIndex()) {
        if (argKeys.all { old[it] == new[it] }) continue
        val arg = seg.segment.args[j]
        val canonArg = canon?.firstOrNull { it.spec.name == spec.name }?.segment?.args?.getOrNull(j)?.value
        val newDp = if (spec.argTypes.getOrNull(j) == "Dp") (new[argKeys.single()] as? PropValue.Lit)?.d else null
        val replacement = when {
          spec.argTypes.getOrNull(j) == "Margin" ->
            MarginSyntax.surgical(arg.value, sidesOf(old, argKeys), sidesOf(new, argKeys)) ?: canonArg
          // A Dp keeps the author's spelling (`12.dp` stays `.dp`).
          newDp != null -> MarginSyntax.dpLike(arg.value, newDp)
          else -> canonArg
        } ?: return null
        edits += arg to replacement
      }
    }
    var out = text
    for ((arg, replacement) in edits.sortedByDescending { it.first.start }) {
      out = out.substring(0, arg.start) + replacement + out.substring(arg.end)
    }
    return out
  }

  /** Skips whitespace AND comments between chain links (`Modifier.a()\n  // why\n  .b()`). */
  private fun skipWs(t: String, from: Int): Int {
    var i = from
    while (i < t.length) {
      when {
        t[i].isWhitespace() -> i++
        t.startsWith("//", i) -> i = t.indexOf('\n', i).let { if (it < 0) t.length else it + 1 }
        t.startsWith("/*", i) -> i = t.indexOf("*/", i + 2).let { if (it < 0) return t.length + 1 else it + 2 }
        else -> return i
      }
    }
    return i
  }

  private fun matchingParen(t: String, open: Int): Int? {
    var depth = 0
    var i = open
    while (i < t.length) {
      when (t[i]) {
        '"' -> i = skipString(t, i) ?: return null
        '(' -> depth++
        ')' -> { depth--; if (depth == 0) return i }
      }
      i++
    }
    return null
  }
}

/** start/end/top/bottom from wire props [keys] (absent or non-numeric = 0). */
internal fun sidesOf(props: Map<String, PropValue>, keys: List<String>): DoubleArray =
  DoubleArray(4) { (props[keys[it]] as? PropValue.Lit)?.let { l -> l.d ?: l.i?.toDouble() } ?: 0.0 }

/**
 * K1: the imports a write-back FRAGMENT needs, derived from the grammar that
 * produced it (the modifier spec / argument type / Margin form) — never by
 * pattern-matching symbol names over text, so a scoped `.size(Dp(…), Dp(…))`
 * never pulls in material's top-level `size`.
 */
internal object FragmentImports {
  private const val UI = "dev.keliver.ui"
  private const val LAYOUT_API = "dev.keliver.layout.api"
  private val layoutEnums = setOf("Constraint", "CrossAxisAlignment", "MainAxisAlignment", "Overflow")

  /** A Dp literal: `N.dp` needs `dp`, `Dp(N.0)` needs `Dp`. */
  fun dp(text: String): Set<String> = when {
    text.trim().startsWith("Dp(") -> setOf("$UI.Dp")
    text.trim().endsWith(".dp") -> setOf("$UI.dp")
    else -> emptySet()
  }

  /** A Margin expression: `Margin` plus each argument's Dp spelling. */
  fun margin(text: String): Set<String> {
    val t = text.trim()
    if (t == "Margin.Zero") return setOf("$UI.Margin")
    val args = splitArgs(t.removePrefix("Margin(").removeSuffix(")"), 0).orEmpty()
    return setOf("$UI.Margin") + args.flatMap { dp(it.value) }
  }

  /** One argument of declared source type [type] (ModifierSpec.argTypes). */
  fun arg(type: String?, text: String): Set<String> = when (type) {
    "Dp" -> dp(text)
    "Margin" -> margin(text)
    in layoutEnums -> setOf("$LAYOUT_API.$type")
    else -> emptySet()
  }

  /** A widget prop value as the exporter writes it (Dp, Margin, layout enum, or a plain literal). */
  fun value(text: String): Set<String> {
    val t = text.trim()
    return when {
      t.startsWith("Margin") -> margin(t)
      MarginSyntax.parseDp(t) != null -> dp(t)
      t.substringBefore('.') in layoutEnums && '(' !in t -> setOf("$LAYOUT_API.${t.substringBefore('.')}")
      else -> emptySet()
    }
  }

  /**
   * A whole `Modifier.…` chain resolved under [parentType]: the extension
   * (unscoped only) + its args — a design-token argument (K2) needs its
   * declaring object, taken from the resolved token, never from the text.
   */
  fun chain(text: String, parentType: String?, tokens: TokenLookup? = null): Set<String> {
    val resolved = ModifierChain.segments(text)?.let { ModifierChain.resolve(it, parentType, tokens) } ?: return emptySet()
    val out = linkedSetOf("dev.keliver.Modifier")
    for (seg in resolved) {
      if (seg.spec.scopes.isEmpty()) dev.keliver.portal.modifierImport[seg.spec.name]?.let { out += it }
      seg.segment.args.forEachIndexed { j, a -> out += arg(seg.spec.argTypes.getOrNull(j), a.value) }
      seg.props.values.forEach { v -> token(v).let { out += it } }
    }
    return out
  }

  /** What a design-token value written as its reference imports (K2). */
  fun token(v: PropValue?): Set<String> = (v as? PropValue.Token)?.import?.let { setOf(it) }.orEmpty()
}
