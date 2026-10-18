package dev.keliver.portal.ingest

import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import dev.keliver.portal.kotlinStringContent
import dev.keliver.portal.kotlinStringLiteral
import dev.keliver.portal.parseKotlinStringLiteral
import dev.keliver.portal.templateRef
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * K4: string templates in source (`"Rating: $rating"`, `"${b.count} items"`).
 *
 * A template is grammar when each embedded expression is a grammar
 * reference (the caller's [ref]: a binding, an item field, a component
 * parameter, a design token) — never an arbitrary expression. The text
 * between references is decoded with the plain-literal rules (K2/U33: `\n`,
 * `\"`, `\$`, `\uXXXX`, `${'$'}`), so its meaning survives an edit.
 *
 * Write-back is per PART: an edit rewrites only the parts that changed (the
 * common leading and trailing parts keep their source bytes — the author's
 * `$x` / `${x}` and escapes); a rewritten part is spelled canonically.
 */
internal object TemplateSyntax {
  /** One part of a template in source: its value (null for a reference before resolution) and its span in the expression's text. */
  data class SourcePart(val literal: String?, val ref: KtExpression?, val start: Int, val end: Int) {
    val isLiteral: Boolean get() = ref == null
  }

  /**
   * The token tags a template reads: String, Int, Boolean. Never a Double — the
   * device (Kotlin/JS) prints `1.0` as `1`, the preview as `1.0`; as K2 refuses
   * a Double inside a String token's own template.
   */
  private val TEMPLATE_TOKEN_TAGS = setOf("s", "i", "b")

  /**
   * The parts of [e] — literal runs (decoded) and references — or null when
   * it is not a plain double-quoted template (a raw string, an error, an
   * unknown escape).
   */
  fun sourceParts(e: KtStringTemplateExpression): List<SourcePart>? {
    if (e.text.startsWith("\"\"\"") || !e.text.endsWith("\"") || e.text.length < 2) return null
    if (PsiTreeUtil.hasErrorElements(e)) return null
    val base = e.textRange.startOffset
    val out = mutableListOf<SourcePart>()
    var runStart = -1
    var runEnd = -1
    val run = StringBuilder()
    fun flush(): Boolean {
      if (runStart < 0) return true
      val v = parseKotlinStringLiteral("\"" + run + "\"") ?: return false
      out += SourcePart(v, null, runStart, runEnd)
      runStart = -1
      run.clear()
      return true
    }
    for (entry in e.entries) {
      val s = entry.textRange.startOffset - base
      val en = entry.textRange.endOffset - base
      val dollar = entry is KtBlockStringTemplateEntry && entry.expression?.text == "'$'"
      when {
        entry is KtLiteralStringTemplateEntry || entry is KtEscapeStringTemplateEntry || dollar -> {
          if (runStart < 0) runStart = s
          runEnd = en
          run.append(entry.text)
        }
        entry is KtSimpleNameStringTemplateEntry || entry is KtBlockStringTemplateEntry -> {
          if (!flush()) return null
          out += SourcePart(null, entry.expression ?: return null, s, en)
        }
        else -> return null
      }
    }
    if (!flush()) return null
    return out
  }

  /**
   * [text] as a template: a [PropValue.Template] when it holds at least one
   * reference and each one resolves through [ref] to a [PropValue.Bind] or a
   * [PropValue.Token] of a String, Int or Boolean kind; null otherwise (a
   * plain literal, or not grammar).
   */
  fun parse(e: KtExpression, ref: (KtExpression) -> PropValue?): PropValue.Template? {
    val str = e as? KtStringTemplateExpression ?: return null
    val parts = sourceParts(str) ?: return null
    if (parts.all { it.isLiteral }) return null
    return PropValue.Template(
      parts.map { p ->
        if (p.isLiteral) lit(p.literal!!) else {
          when (val v = ref(p.ref!!)) {
            is PropValue.Bind -> v
            is PropValue.Token -> v.takeIf { it.value.tag in TEMPLATE_TOKEN_TAGS } ?: return null
            else -> return null
          }
        }
      },
    )
  }

  /** The parts of a template value — a template's own, a String literal as one text part (none when empty). */
  fun partsOf(v: PropValue?): List<PropValue>? = when {
    v is PropValue.Template -> v.parts
    v is PropValue.Lit && v.tag == "s" -> if (v.s.isNullOrEmpty()) emptyList() else listOf(v)
    else -> null
  }

  /**
   * The template [source] (an argument expression's text) with only the parts
   * that changed between [old] and [new] rewritten; [spell] is a reference's
   * source (`bindings.count`, `item.city`, `C.Brand`). Null when [source] does
   * not read as [old], or the result would not read as [new].
   */
  fun surgical(source: String, old: List<PropValue>, new: List<PropValue>, spell: (PropValue) -> String?): String? {
    val e = runCatching { PsiEnv.factory.createExpression(source) }.getOrNull() as? KtStringTemplateExpression ?: return null
    if (e.text != source) return null
    val src = sourceParts(e) ?: return null
    if (src.size != old.size || src.indices.any { src[it].isLiteral != (old[it] is PropValue.Lit) }) return null
    var p = 0
    while (p < old.size && p < new.size && old[p] == new[p]) p++
    var q = 0
    while (q < old.size - p && q < new.size - p && old[old.size - 1 - q] == new[new.size - 1 - q]) q++
    if (p == old.size && p == new.size) return source
    var start = if (p == 0) 1 else src[p - 1].end
    val end = if (q == 0) source.length - 1 else src[old.size - q].start
    val after = source.getOrNull(end)?.takeIf { end < source.length - 1 }
    val middle = new.subList(p, new.size - q)
    var text = spellParts(middle, after, spell) ?: return null
    // A kept `$name` before the rewrite must not run into new text that continues an identifier.
    val prev = src.getOrNull(p - 1)
    val first = text.firstOrNull()
    if (prev != null && !prev.isLiteral && !source.startsWith("\${", prev.start) &&
      first != null && (first.isLetterOrDigit() || first == '_')
    ) {
      text = "\${" + prev.ref!!.text + "}" + text
      start = prev.start
    }
    val out = source.substring(0, start) + text + source.substring(end)
    return out.takeIf { readsAs(it, new, spell) }
  }

  /** [parts] as template source (no quotes); a reference followed by [after] is braced where it must be. */
  fun spellParts(parts: List<PropValue>, after: Char?, spell: (PropValue) -> String?): String? = buildString {
    parts.forEachIndexed { i, part ->
      when (part) {
        is PropValue.Lit -> append(kotlinStringContent(part.s ?: return null))
        else -> {
          val next = (parts.getOrNull(i + 1) as? PropValue.Lit)?.s?.firstOrNull()
            ?: if (i == parts.lastIndex) after else '$'
          append(templateRef(spell(part) ?: return null, next))
        }
      }
    }
  }

  /** The canonical source of a template's [parts], quotes included. */
  fun canonical(parts: List<PropValue>, spell: (PropValue) -> String?): String? =
    spellParts(parts, null, spell)?.let { "\"$it\"" }

  /** Whether [text] is a template whose parts are [expected] (a reference compared by its spelling). */
  private fun readsAs(text: String, expected: List<PropValue>, spell: (PropValue) -> String?): Boolean {
    val e = runCatching { PsiEnv.factory.createExpression(text) }.getOrNull() as? KtStringTemplateExpression ?: return false
    if (e.text != text) return false
    val parts = sourceParts(e) ?: return false
    if (parts.size != expected.size) return false
    return parts.indices.all { i ->
      val x = expected[i]
      if (parts[i].isLiteral) x is PropValue.Lit && x.s == parts[i].literal
      else x !is PropValue.Lit && spell(x) == parts[i].ref!!.text.trim()
    }
  }
}

/**
 * K4: `listOf(…)` values whose elements may be references
 * (`listOf(C.Start, C.End)`). Write-back rewrites only the changed elements
 * when the list keeps its length; otherwise the list is written whole.
 */
internal object ListSyntax {
  /** The elements of [e] when it is a plain `listOf(a, b, …)` call (no type argument, no lambda); null otherwise. */
  fun elements(e: KtExpression): List<KtExpression>? {
    val call = e as? KtCallExpression ?: return null
    if (call.calleeExpression?.text != "listOf" || call.typeArgumentList != null || call.lambdaArguments.isNotEmpty()) return null
    if (PsiTreeUtil.hasErrorElements(call)) return null
    return call.valueArguments.map { a ->
      if (a.getArgumentName() != null || a.getSpreadElement() != null) return null
      a.getArgumentExpression() ?: return null
    }
  }

  /** The elements of a list value — a [PropValue.ListOf]'s, or a literal list's as literals; null for anything else. */
  fun itemsOf(v: PropValue?): List<PropValue>? = when {
    v is PropValue.ListOf -> v.items
    v is PropValue.Lit && v.tag == "li" -> v.li.orEmpty().map { lit(it) }
    v is PropValue.Lit && v.tag == "ls" -> v.ls.orEmpty().map { lit(it) }
    else -> null
  }

  /** An element's canonical source. */
  fun spellItem(v: PropValue, spell: (PropValue) -> String?): String? = when {
    v is PropValue.Lit && v.tag == "i" -> v.i?.toString()
    v is PropValue.Lit && v.tag == "s" -> v.s?.let(::kotlinStringLiteral)
    v is PropValue.Lit -> null
    else -> spell(v)
  }

  /** [source] with only the changed elements rewritten — null when the length changed or [source] is not such a list. */
  fun surgical(source: String, old: List<PropValue>, new: List<PropValue>, spell: (PropValue) -> String?): String? {
    if (old.size != new.size) return null
    val e = runCatching { PsiEnv.factory.createExpression(source) }.getOrNull() ?: return null
    if (e.text != source) return null
    val items = elements(e) ?: return null
    if (items.size != old.size) return null
    val base = e.textRange.startOffset
    var out = source
    for (i in old.indices.reversed()) {
      if (old[i] == new[i]) continue
      val text = spellItem(new[i], spell) ?: return null
      val r = items[i].textRange
      out = out.substring(0, r.startOffset - base) + text + out.substring(r.endOffset - base)
    }
    return out
  }
}
