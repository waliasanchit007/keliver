package dev.keliver.portal.ingest

import dev.keliver.portal.MARGIN_SIDES
import dev.keliver.portal.ModifierSpec
import dev.keliver.portal.document.PropValue
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

/** Index just past... the closing quote of the string starting at [open]; null when unterminated. */
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
 * `Margin.Zero`, `Margin()`, `Margin(X)` (one positional = all),
 * `Margin(all = X)`, `Margin(horizontal = X, vertical = Y)` (either alone),
 * any named subset of `start`/`end`/`top`/`bottom` (the class constructor),
 * with X as `N.dp`, `N.M.dp`, `Dp(N)`, `Dp(N.M)`. Two or more POSITIONAL
 * arguments are not grammar: `Margin(a, b)` reads as the class constructor
 * (start, end) or the (horizontal, vertical) function depending on overload
 * resolution, so the portal refuses to guess.
 */
internal object MarginSyntax {
  /** start, end, top, bottom (dp) — null when [text] is not a Margin in the grammar. */
  fun parse(text: String): DoubleArray? = parseArgs(text.trim())?.let { (form, args) -> sides(form, args) }

  /** A Dp literal's value: `16.dp`, `16.0.dp`, `Dp(16)`, `Dp(16.0)`. */
  fun parseDp(text: String): Double? {
    val t = text.trim()
    Regex("^(\\d+(?:\\.\\d+)?)\\.dp$").find(t)?.let { return it.groupValues[1].toDouble() }
    Regex("^Dp\\(\\s*(\\d+(?:\\.\\d+)?)\\s*\\)$").find(t)?.let { return it.groupValues[1].toDouble() }
    return null
  }

  private enum class Form { ZERO, ALL, AXES, SIDES }

  /** The recognized form + its args (offsets relative to [t]). */
  private fun parseArgs(t: String): Pair<Form, List<SourceArg>>? {
    if (t == "Margin.Zero") return Form.ZERO to emptyList()
    if (!t.startsWith("Margin(") || !t.endsWith(")")) return null
    val args = splitArgs(t.substring(7, t.length - 1), 7) ?: return null
    if (args.isEmpty()) return Form.ZERO to args
    if (args.any { parseDp(it.value) == null }) return null
    val positional = args.filter { it.name == null }
    if (positional.isNotEmpty()) {
      return if (args.size == 1) Form.ALL to args else null
    }
    val names = args.map { it.name!! }
    if (names.toSet().size != names.size) return null
    return when {
      names == listOf("all") -> Form.ALL to args
      names.all { it == "horizontal" || it == "vertical" } -> Form.AXES to args
      names.all { it in MARGIN_SIDES } -> Form.SIDES to args
      else -> null
    }
  }

  private fun sides(form: Form, args: List<SourceArg>): DoubleArray {
    val v = args.associate { (it.name ?: "all") to parseDp(it.value)!! }
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
   * not written, or `all = X` with now-unequal sides) — the caller then
   * replaces the whole expression with the canonical one.
   */
  fun surgical(text: String, old: DoubleArray, new: DoubleArray): String? {
    val (form, args) = parseArgs(text) ?: return null
    val edits = mutableListOf<Pair<SourceArg, Double>>()
    when (form) {
      Form.ZERO -> return null
      Form.ALL -> {
        if (new.any { it != new[0] }) return null
        edits += args.single() to new[0]
      }
      Form.AXES -> {
        if (new[0] != new[1] || new[2] != new[3]) return null
        for ((axis, idx) in listOf("horizontal" to 0, "vertical" to 2)) {
          if (old[idx] == new[idx]) continue
          edits += (args.firstOrNull { it.name == axis } ?: return null) to new[idx]
        }
      }
      Form.SIDES -> {
        for ((idx, side) in MARGIN_SIDES.withIndex()) {
          if (old[idx] == new[idx]) continue
          edits += (args.firstOrNull { it.name == side } ?: return null) to new[idx]
        }
      }
    }
    var out = text
    for ((arg, v) in edits.sortedByDescending { it.first.start }) {
      out = out.substring(0, arg.start) + dpLike(arg.value, v) + out.substring(arg.end)
    }
    return out
  }

  /** [v] written in the same Dp style as [previous] (`N.dp` or `Dp(N.0)`). */
  private fun dpLike(previous: String, v: Double): String {
    val whole = v % 1.0 == 0.0 && v < 1e15
    return if (previous.trim().startsWith("Dp(")) {
      "Dp(" + (if (whole) "${v.toLong()}.0" else "$v") + ")"
    } else {
      (if (whole) "${v.toLong()}" else "$v") + ".dp"
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

  /** Resolve every segment; null when any is outside the grammar or a modifier repeats. */
  fun resolve(segments: List<ChainSegment>, parentType: String?): List<ResolvedSegment>? {
    val out = segments.map { resolveSegment(it, parentType) ?: return null }
    if (out.map { it.spec.name }.toSet().size != out.size) return null // a repeat would be lossy
    return out
  }

  fun resolveSegment(seg: ChainSegment, parentType: String?): ResolvedSegment? {
    val candidates = modifierSpecs.filter { it.extension == seg.ext }
    val scoped = candidates.filter { it.scopes.isNotEmpty() && parentType != null && parentType in it.scopes }
    for (spec in scoped + candidates.filter { it.scopes.isEmpty() }) {
      parseArgs(spec, seg.args)?.let { return ResolvedSegment(seg, spec, it) }
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

  private fun parseArgs(spec: ModifierSpec, args: List<SourceArg>): Map<String, PropValue>? {
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
        out[keys[j].single()] = typedLiteral(type, arg.value) ?: return null
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
  ): String? {
    val names = { m: Map<String, PropValue> -> m.keys.map { it.substringBefore('.') }.toSet() }
    if (names(old) != names(new)) return canonical
    val source = segments(text)?.let { resolve(it, parentType) } ?: return canonical
    val canon = canonical?.let { c -> segments(c)?.let { resolve(it, parentType) } }
    val edits = mutableListOf<Pair<SourceArg, String>>()
    for (seg in source) {
      val spec = seg.spec
      val keys = argKeys(spec)
      for ((j, argKeys) in keys.withIndex()) {
        if (argKeys.all { old[it] == new[it] }) continue
        val arg = seg.segment.args[j]
        val canonArg = canon?.firstOrNull { it.spec.name == spec.name }?.segment?.args?.getOrNull(j)?.value
        val replacement = if (spec.argTypes.getOrNull(j) == "Margin") {
          MarginSyntax.surgical(arg.value, sidesOf(old, argKeys), sidesOf(new, argKeys)) ?: canonArg
        } else {
          canonArg
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

  private fun skipWs(t: String, from: Int): Int {
    var i = from
    while (i < t.length && t[i].isWhitespace()) i++
    return i
  }

  private fun matchingParen(t: String, open: Int): Int? {
    var depth = 0
    var i = open
    while (i < t.length) {
      when (t[i]) {
        '"' -> i = skipStringAt(t, i) ?: return null
        '(' -> depth++
        ')' -> { depth--; if (depth == 0) return i }
      }
      i++
    }
    return null
  }

  private fun skipStringAt(t: String, open: Int): Int? {
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
}

/** start/end/top/bottom from wire props [keys] (absent or non-numeric = 0). */
internal fun sidesOf(props: Map<String, PropValue>, keys: List<String>): DoubleArray =
  DoubleArray(4) { (props[keys[it]] as? PropValue.Lit)?.let { l -> l.d ?: l.i?.toDouble() } ?: 0.0 }
