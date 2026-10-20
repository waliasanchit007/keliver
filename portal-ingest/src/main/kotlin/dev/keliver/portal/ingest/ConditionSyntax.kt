package dev.keliver.portal.ingest

import dev.keliver.portal.Cond
import dev.keliver.portal.CondJoin
import dev.keliver.portal.CondTest
import dev.keliver.portal.condOfTerms
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.toAny
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression

/** K6: a condition's source read as a [Cond], or why it is not grammar. */
internal sealed interface CondRead {
  data class Ok(val cond: Cond) : CondRead
  data class No(val why: String) : CondRead
}

/**
 * K3/K6: reads a condition's source text in the context it appears in — a
 * screen's bindings, or a component's parameters — as a [Cond]:
 *
 *  - ONE term: `x`, `!x`, `x.isEmpty()`, `x.isNotEmpty()`, `x.isBlank()`,
 *    `x.isNotBlank()`, `x == null` / `x != null` (a component parameter
 *    declared nullable), and (K6) `x == <operand>` / `x != <operand>` — the
 *    field on the left; the operand a String literal (no template), an Int
 *    literal (negative too; never one Kotlin reads as a Long) or `true` /
 *    `false`, or a design token of one of those kinds; the field, where its
 *    type is declared, declared exactly that kind (never nullable, never a
 *    Double);
 *  - (K6) two or more terms joined by `&&` only or by `||` only — no mixing,
 *    no parentheses, no negated group, no comment inside;
 *  - (K6, a component body) a derived Boolean local ([locals]) read as `x`
 *    or `!x` stands for its condition, in place: `!x` is its negation (De
 *    Morgan), and a local of several terms is read only where the result is
 *    still one join.
 *
 * [x] is a screen binding `b.x` (or, for a conditional VALUE inside a
 * Repeat, an item field `item.x`) or a component parameter. [typeOf] gives a
 * field's declared type ([UNKNOWN_TYPE] when it cannot be read, null when
 * none is declared). [pendingLocals] are locals declared after the condition
 * (a use before the definition); [readOnlyNames] are locals a local's own
 * initializer may not read (locals stay flat).
 */
internal class CondReader(
  private val ctx: NameCtx,
  private val tokens: TokenLookup? = null,
  private val typeOf: (String) -> String? = { null },
  private val locals: Map<String, Cond> = emptyMap(),
  private val pendingLocals: Set<String> = emptySet(),
  private val readOnlyNames: Set<String> = emptySet(),
) {
  fun read(text: String, itemScope: Set<String> = emptySet()): CondRead {
    val t = text.trim()
    val shown = t.oneLine()
    val e = runCatching { PsiEnv.factory.createExpression(t) }.getOrNull()
      ?: return CondRead.No("condition `$shown` does not parse")
    if (e.text != t) return CondRead.No("condition `$shown` does not parse as one expression")
    if (hasComment(e)) {
      return CondRead.No("condition `$shown` has a comment inside it (a condition is its terms only)")
    }
    var join: CondJoin? = null
    val parts = ArrayList<KtExpression>()
    fun flat(x: KtExpression): String? {
      if (x is KtBinaryExpression && (x.operationToken == KtTokens.ANDAND || x.operationToken == KtTokens.OROR)) {
        val j = if (x.operationToken == KtTokens.ANDAND) CondJoin.And else CondJoin.Or
        if (join != null && join != j) return MIXED.replace("%s", shown)
        join = j
        return flat(x.left ?: return "condition `$shown` does not parse") ?: flat(x.right ?: return "condition `$shown` does not parse")
      }
      parts += x
      return null
    }
    flat(e)?.let { return CondRead.No(it) }
    val terms = ArrayList<Cond>()
    var single: Cond? = null
    for (p in parts) {
      val c = when (val r = term(p, itemScope)) {
        is CondRead.Ok -> r.cond
        is CondRead.No -> return r
      }
      // A local of several terms is spliced in only where the join stays one.
      if (c.more.isNotEmpty() && parts.size > 1 && c.join != (join ?: CondJoin.And)) {
        return CondRead.No(MIXED.replace("%s", shown) + " once the local `${p.text.trim().removePrefix("!").trim()}` is read in place")
      }
      if (parts.size == 1) single = c
      terms += c.terms
    }
    // One part keeps its own join (a derived local of several terms, `!active` by De Morgan).
    val cond = single ?: condOfTerms(terms, join ?: CondJoin.And)
    // K6 review: a Boolean or emptiness term reads only a field whose declared type takes it.
    cond.terms.forEachIndexed { i, t -> typeWhy(t, cond.terms.take(i), cond.join)?.let { return CondRead.No("condition `$shown`: $it") } }
    return CondRead.Ok(cond)
  }

  /**
   * K6 review: why the Boolean or emptiness term [t] does not fit its field's
   * declared type (where one is declared and readable): a Boolean test reads a
   * `Boolean`; `isEmpty()` a `String`, `CharSequence`, collection or array;
   * `isBlank()` a `String` or `CharSequence`. A nullable one only where an
   * earlier term of the same condition smart-casts it (`x != null && …`,
   * `x == null || …`) — never in a screen, whose Bindings properties are not
   * smart-cast and which has no null test. Null = it fits.
   */
  private fun typeWhy(t: Cond, before: List<Cond>, join: CondJoin): String? {
    if (t.test != CondTest.Bool && t.test != CondTest.Empty && t.test != CondTest.Blank) return null
    val declared = typeOf(t.field)?.takeIf { it != UNKNOWN_TYPE }?.let(::plainType) ?: return null
    val base = declared.removeSuffix("?")
    val fits = when (t.test) {
      CondTest.Bool -> base == "Boolean"
      CondTest.Blank -> base == "String" || base == "CharSequence"
      else -> base == "String" || base == "CharSequence" || EMPTY_COLLECTIONS.any { base.startsWith("$it<") }
    }
    if (!fits) {
      return if (t.test == CondTest.Bool) "`${t.field}` is declared $declared, not a Boolean"
      else "`${t.field}` is declared $declared, which has no ${if (t.test == CondTest.Empty) "isEmpty()" else "isBlank()"}"
    }
    if (!declared.endsWith("?")) return null
    val guarded = before.any { it.field == t.field && it.test == CondTest.Null && it.negate == (join == CondJoin.And) }
    return if (guarded) null
    else "`${t.field}` is declared $declared: it is read only after `${t.field} != null &&` (or `${t.field} == null ||`) in the same condition"
  }

  /** One term — or, for a derived local, the condition it stands for. */
  private fun term(x: KtExpression, itemScope: Set<String>): CondRead {
    val t = x.text.trim()
    val shown = t.oneLine()
    if (x is KtParenthesizedExpression || (x is KtPrefixExpression && x.baseExpression is KtParenthesizedExpression)) {
      return CondRead.No("condition term `$shown`: parentheses group terms, and a condition is flat — no parentheses, no `!( … )`; join the terms with `&&` only or `||` only")
    }
    val id = "[A-Za-z_][A-Za-z0-9_]*"
    val ref = when (ctx) {
      is NameCtx.Screen -> ctx.bindingsParam?.let { "${Regex.escape(it)}\\.$id" + if (itemScope.isEmpty()) "" else "|$id\\.$id" }
        ?: return CondRead.No("condition `$shown`: the screen takes no bindings parameter")
      is NameCtx.Component -> "$id(?:\\.$id)?"
    }
    Regex("^($ref)\\.(isEmpty|isNotEmpty|isBlank|isNotBlank)\\(\\)$").find(t)?.let { m ->
      val f = field(m.groupValues[1], itemScope) ?: return outside(shown)
      return CondRead.Ok(
        when (m.groupValues[2]) {
          "isEmpty" -> Cond(f, CondTest.Empty)
          "isNotEmpty" -> Cond(f, CondTest.Empty, negate = true)
          "isBlank" -> Cond(f, CondTest.Blank)
          else -> Cond(f, CondTest.Blank, negate = true)
        },
      )
    }
    if (x is KtBinaryExpression && (x.operationToken == KtTokens.EQEQEQ || x.operationToken == KtTokens.EXCLEQEQEQ)) {
      return CondRead.No("condition `$shown`: `===`/`!==` compare identity; an equality is `==`/`!=`")
    }
    if (x is KtBinaryExpression && (x.operationToken == KtTokens.EQEQ || x.operationToken == KtTokens.EXCLEQ)) {
      return equality(x, itemScope, shown)
    }
    Regex("^(!\\s*)?($ref)$").find(t)?.let { m ->
      val name = m.groupValues[2]
      val negate = m.groupValues[1].isNotEmpty()
      if (ctx is NameCtx.Component && '.' !in name && name !in itemScope) {
        locals[name]?.let { return CondRead.Ok(if (negate) it.negated() else it) }
        if (name in pendingLocals) return CondRead.No("`$name` is used before its definition (its `val $name = …` comes after this use)")
        if (name in readOnlyNames) return CondRead.No("it reads the local `$name`: a derived local reads only parameters (locals stay flat)")
      }
      val f = field(name, itemScope) ?: return outside(shown)
      // An event parameter is a function: only a null test reads it.
      if (ctx is NameCtx.Component && f in ctx.eventParams) return outside(shown)
      return CondRead.Ok(Cond(f, negate = negate))
    }
    return outside(shown)
  }

  /** `x == <operand>` / `x != <operand>` (K6), or the K3 null test `x == null` / `x != null`. */
  private fun equality(x: KtBinaryExpression, itemScope: Set<String>, shown: String): CondRead {
    val negate = x.operationToken == KtTokens.EXCLEQ
    val left = x.left?.text?.trim() ?: return outside(shown)
    val right = x.right ?: return outside(shown)
    val rightText = right.text.trim()
    if (rightText == "null") {
      // A screen contract cannot declare a nullable field; a parameter must be declared nullable.
      if (ctx !is NameCtx.Component || left !in ctx.nullableParams) {
        return CondRead.No("condition `$shown` is outside the portal grammar: a null test reads only a component parameter declared nullable")
      }
      return CondRead.Ok(Cond(left, CondTest.Null, negate = negate))
    }
    if (isLocal(left, itemScope)) return CondRead.No("condition `$shown`: a derived local is read as `$left` or `!$left`, never compared")
    val f = field(left, itemScope)
    val rightField = field(rightText, itemScope)
    if (f == null) {
      return if (rightField != null) CondRead.No("condition `$shown`: the field goes on the left (`$rightText == …`), its one spelling")
      else outside(shown)
    }
    if (rightField != null || isLocal(rightText, itemScope)) {
      return CondRead.No("condition `$shown` compares two fields; an equality compares a field with an Int, String or Boolean literal or design token")
    }
    if (ctx is NameCtx.Component && f in ctx.eventParams) return outside(shown)
    val operand = when (val o = operand(rightText)) {
      is PropValue -> o
      else -> return CondRead.No("condition `$shown`: $o")
    }
    val kind = kindOf(operand)!!
    val declared = typeOf(f)?.takeIf { it != UNKNOWN_TYPE }?.let(::plainType)
    if (declared != null && declared.endsWith("?")) {
      return CondRead.No("condition `$shown`: `$left` is declared $declared — an equality on a nullable value is not grammar (only `== null` / `!= null`, on a component parameter)")
    }
    if (declared != null && declared != kind) {
      return CondRead.No("condition `$shown`: `$left` is declared $declared, compared with ${if (kind == "Int") "an" else "a"} $kind" +
        if (declared == "Double" || declared == "Float") " (a $declared is not compared: the device and the preview do not compare one alike)" else "")
    }
    return CondRead.Ok(Cond(f, CondTest.Eq, negate, operand.toTreeOperand()))
  }

  /** An equality's operand (a [PropValue.Lit] s/i/b or a [PropValue.Token] of one), or why [t] is none. */
  private fun operand(t: String): Any {
    if (t == "true" || t == "false") return PropValue.Lit("b", b = t == "true")
    Regex("^(-)?(0|[1-9][0-9]*)$").find(t)?.let { m ->
      val v = m.groupValues[2].toIntOrNull()
        ?: return "`$t` is a Long in Kotlin source, not an Int (an equality compares with an Int, String or Boolean literal or design token)"
      return PropValue.Lit("i", i = if (m.groupValues[1].isEmpty()) v else -v)
    }
    if (Regex("^-?[0-9]*\\.[0-9]+([eE][-+]?[0-9]+)?[fF]?$").matches(t) || Regex("^-?[0-9]+[fF]$").matches(t)) {
      return "`$t` is a Double or Float, which is not compared (the device and the preview do not compare one alike); an equality compares with an Int, String or Boolean literal or design token"
    }
    if (t.startsWith('"')) {
      return dev.keliver.portal.parseKotlinStringLiteral(t)?.let { PropValue.Lit("s", s = it) }
        ?: "`${t.oneLine()}` is not a plain string literal (a template or a raw string is not compared); an equality compares with an Int, String or Boolean literal or design token"
    }
    tokens?.lookup(t)?.let { tok ->
      return when (tok.value.tag) {
        "s", "i", "b" -> tok
        "d" -> "`$t` is a Double token, which is not compared (the device and the preview do not compare one alike)"
        else -> "`$t` is not an Int, String or Boolean design token"
      }
    }
    return "`${t.oneLine()}` is not an Int, String or Boolean literal or design token"
  }

  private fun kindOf(v: PropValue): String? = when (v) {
    is PropValue.Lit -> when (v.tag) { "s" -> "String"; "i" -> "Int"; "b" -> "Boolean"; else -> null }
    is PropValue.Token -> kindOf(v.value)
    else -> null
  }

  private fun PropValue.toTreeOperand(): Any? = when (this) {
    is PropValue.Token -> dev.keliver.portal.Token(ref, value.toAny(), import)
    is PropValue.Lit -> toAny()
    else -> null
  }

  private fun isLocal(name: String, itemScope: Set<String>): Boolean =
    ctx is NameCtx.Component && name !in itemScope && (name in locals || name in pendingLocals || name in readOnlyNames)

  /** The field a reference names in [ctx] — a screen binding's name, an item field as written, a parameter — or null. */
  private fun field(r: String, itemScope: Set<String>): String? = when (ctx) {
    is NameCtx.Screen -> {
      val head = r.substringBefore('.')
      when {
        '.' !in r -> null
        head == ctx.bindingsParam -> r.substringAfter('.').takeIf { '.' !in it }
        head in itemScope -> r.takeIf { r.count { it == '.' } == 1 }
        else -> null
      }
    }
    is NameCtx.Component -> when {
      '.' !in r -> r.takeIf { it in ctx.valueParams || it in ctx.eventParams }
      r.substringBefore('.') in itemScope -> r.takeIf { r.count { it == '.' } == 1 }
      else -> null
    }
  }

  private fun outside(shown: String) = CondRead.No(
    "condition `$shown` is outside the portal grammar (a Boolean field, `!field`, " +
      "`field.isEmpty()`/`isNotEmpty()`/`isBlank()`/`isNotBlank()`, `field == <literal or token>`/`!=`" +
      (if (ctx is NameCtx.Component) ", `param == null`/`!= null` on a nullable parameter, a derived Boolean local" else "") +
      "; terms joined by `&&` only or `||` only)",
  )

  private companion object {
    val EMPTY_COLLECTIONS = listOf("List", "MutableList", "Collection", "Set", "MutableSet", "Map", "MutableMap", "Array")
    const val MIXED = "condition `%s` mixes `&&` and `||` (a condition joins its terms with one of them)"
  }
}

/** One line, shortened, for a diagnostic. */
internal fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim().let { if (it.length > 80) it.take(77) + "…" else it }

/** K6: the fields a conditional value's terms read, in source order. */
internal fun PropValue.Conditional.docFields(): List<String> = listOf(field) + more.map { it.field }

/**
 * K6: surgical edits of a condition's SOURCE text. A changed condition with
 * the same shape — as many terms, the same join — rewrites only the terms
 * that changed, each spelled by [termText]; every other term keeps its bytes
 * (and the author's line breaks between terms). Any other change writes the
 * whole condition with [termText].
 */
internal object CondSyntax {
  fun rewrite(source: String, old: Cond, new: Cond, termText: (Cond) -> String): String {
    if (old == new) return source
    val spans = termSpans(source) ?: return termText(new)
    val o = old.terms
    val n = new.terms
    if (spans.size != o.size || n.size != o.size || (o.size > 1 && old.join != new.join)) return termText(new)
    var out = source
    for (i in spans.indices.reversed()) {
      if (o[i] == n[i]) continue
      val (start, end) = spans[i]
      out = out.substring(0, start) + termText(n[i]) + out.substring(end)
    }
    return out
  }

  /** The design-token imports [cond]'s equality operands need (structural, from the tokens). */
  fun imports(cond: Cond): Set<String> =
    cond.terms.mapNotNullTo(LinkedHashSet()) { (it.operand as? dev.keliver.portal.Token)?.importFq }

  /** Each term's [start, end) in [text] — terms joined by one of `&&`/`||` — or null when it is not such a condition. */
  private fun termSpans(text: String): List<Pair<Int, Int>>? {
    val e = runCatching { PsiEnv.factory.createExpression(text) }.getOrNull() ?: return null
    if (e.text != text) return null
    val base = e.textRange.startOffset
    var join: org.jetbrains.kotlin.com.intellij.psi.tree.IElementType? = null
    val out = ArrayList<Pair<Int, Int>>()
    fun flat(x: KtExpression): Boolean {
      if (x is KtBinaryExpression && (x.operationToken == KtTokens.ANDAND || x.operationToken == KtTokens.OROR)) {
        if (join != null && join != x.operationToken) return false
        join = x.operationToken
        return flat(x.left ?: return false) && flat(x.right ?: return false)
      }
      out += (x.textRange.startOffset - base) to (x.textRange.endOffset - base)
      return true
    }
    return if (flat(e)) out else null
  }
}

/** Whether a comment leaf sits anywhere inside [e]. */
internal fun hasComment(e: org.jetbrains.kotlin.com.intellij.psi.PsiElement): Boolean {
  val end = e.textRange.endOffset
  var leaf: org.jetbrains.kotlin.com.intellij.psi.PsiElement? = PsiTreeUtil.firstChild(e)
  while (leaf != null && leaf.textRange.startOffset < end) {
    if (leaf is PsiComment) return true
    leaf = PsiTreeUtil.nextLeaf(leaf)
  }
  return false
}
