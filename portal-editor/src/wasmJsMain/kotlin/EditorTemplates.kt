import dev.keliver.portal.Bind
import dev.keliver.portal.ListOf
import dev.keliver.portal.PropKind
import dev.keliver.portal.Template
import dev.keliver.portal.Token
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.canonicalValue
import dev.keliver.portal.document.lit
import dev.keliver.portal.document.toPropValue
import dev.keliver.portal.listElementType
import dev.keliver.portal.tokenFits
import dev.keliver.portal.templateTokenFits
import dev.keliver.portal.mockedIntElement
import org.w3c.dom.HTMLElement

// K4: the properties panel for a string template and a list with references.
//
// A template is edited as text with its references in braces:
//   Rating: {rating}/{◆C.Max} for {item.city}
// `{field}` is a screen binding, `{item.x}` an item field, `{◆Owner.Name}` a
// design token (String, Int or Boolean — never a Double, which the device
// prints as `1` where the preview prints `1.0`); `{{` and `}}` are literal
// braces. A list is edited as its elements — `, `-separated for an Int list
// (`16777215, ◆C.Start, @accent`, `#RRGGBB` for a colour), `|`-separated for
// a String list — `◆Ref` a token, `@field` a binding. In a String list a
// backslash takes the next character literally (`\@at`, `\◆`, `\|`, `\\`,
// `\"`), and an element in double quotes keeps its spaces (`" a "`, `""` the
// empty text): every list is spelled so it reads back as itself. Invalid
// text is shown, never sent.

private val FIELD = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$")

/** A parse: the value, or why the text is not one. */
internal data class Parsed(val value: PropValue?, val error: String?)

/** [t] as the panel shows it. */
internal fun templateSource(t: Template): String = buildString {
  for (p in t.parts) {
    when (p) {
      is String -> append(p.replace("{", "{{").replace("}", "}}"))
      is Bind -> append("{").append(p.field).append("}")
      is Token -> append("{◆").append(p.ref).append("}")
      else -> append(p?.toString().orEmpty().replace("{", "{{").replace("}", "}}"))
    }
  }
}

/**
 * The panel's template text as a document value — a canonical
 * [PropValue.Template], or a plain String literal when no reference is left.
 * [known] names the tokens the text may use by reference (the token list,
 * and the ones the current value already holds).
 */
internal fun parseTemplateSource(text: String, known: Map<String, PropValue.Token>): Parsed {
  val parts = mutableListOf<PropValue>()
  val run = StringBuilder()
  var i = 0
  fun flush() {
    if (run.isNotEmpty()) parts += lit(run.toString())
    run.clear()
  }
  while (i < text.length) {
    val c = text[i]
    when {
      c == '{' && text.startsWith("{{", i) -> { run.append('{'); i += 2 }
      c == '}' && text.startsWith("}}", i) -> { run.append('}'); i += 2 }
      c == '}' -> return Parsed(null, "a lone } (write }} for a brace)")
      c == '{' -> {
        val close = text.indexOf('}', i + 1)
        if (close < 0) return Parsed(null, "a { without its } (write {{ for a brace)")
        val ref = text.substring(i + 1, close).trim()
        flush()
        parts += when {
          ref.startsWith("◆") -> known[ref.removePrefix("◆").trim()] ?: return Parsed(null, "no design token ${ref.removePrefix("◆").trim()}")
          FIELD.matches(ref) -> PropValue.Bind(ref)
          else -> return Parsed(null, "{$ref} is not a field name, an item field (item.x) or a token (◆C.Name)")
        }
        i = close + 1
      }
      else -> { run.append(c); i++ }
    }
  }
  flush()
  return Parsed(canonicalValue(PropValue.Template(parts)), null)
}

/**
 * Whether a prop of [kind] holding [value] is edited as list elements
 * ([listRow]): an Int or String list holding a literal list, a list with
 * references, or nothing yet — not a list bound as a whole (`options =
 * b.options`), which keeps its binding row.
 */
internal fun editsAsList(kind: PropKind, value: Any?): Boolean =
  (kind == PropKind.IntList || kind == PropKind.StringList) && (value == null || value is List<*> || value is ListOf)

/**
 * Whether the panel offers [t] inside a template: a String, Int or Boolean
 * token (never a Double) — by the kind the server declared, which every
 * target reads alike (Kotlin/JS cannot tell a Double value from an Int).
 */
internal fun templateTokenOffered(t: EditorToken): Boolean = !t.ambiguous && t.kind in TEMPLATE_TOKEN_KINDS && templateTokenFits(t.value)

private val TEMPLATE_TOKEN_KINDS = setOf("String", "Int", "Boolean")

/**
 * A String list element's literal [s] as the panel spells it, so it reads
 * back as itself: `\` and `|` escaped; a leading `@`, `◆` or `"` escaped
 * (else it would read as a binding, a token or a quote); an empty text or one
 * with spaces at either end in double quotes (else they would be trimmed).
 */
private fun listLiteralSource(s: String): String {
  val quoted = s.isEmpty() || s.first().isWhitespace() || s.last().isWhitespace()
  val body = buildString {
    s.forEachIndexed { i, c ->
      when {
        c == '\\' || c == '|' -> append('\\').append(c)
        c == '"' && (quoted || i == 0) -> append("\\\"")
        !quoted && i == 0 && (c == '@' || c == '◆') -> append('\\').append(c)
        else -> append(c)
      }
    }
  }
  return if (quoted) "\"$body\"" else body
}

/** [v]'s elements as the panel shows them ([kind] says the separator and the literal spelling). */
internal fun listSource(v: Any?, kind: PropKind): String {
  val items = when (v) {
    is ListOf -> v.items
    is List<*> -> v
    else -> emptyList()
  }
  return items.joinToString(if (kind == PropKind.StringList) "|" else ", ") { e ->
    when (e) {
      is Token -> "◆${e.ref}"
      is Bind -> "@${e.field}"
      is String -> if (kind == PropKind.StringList) listLiteralSource(e) else e
      else -> e?.toString().orEmpty()
    }
  }
}

/** One character of list text: [escaped] when a backslash took it literally. */
private data class ListChar(val c: Char, val escaped: Boolean)

/**
 * [text] split at each unescaped [sep] into elements, each trimmed of the
 * unescaped whitespace at its ends — or the reason it cannot be (a lone
 * trailing backslash). Blank text is no element.
 */
private fun listElements(text: String, sep: Char): Pair<List<List<ListChar>>?, String?> {
  if (text.isBlank()) return emptyList<List<ListChar>>() to null
  val out = mutableListOf<List<ListChar>>()
  var cur = mutableListOf<ListChar>()
  var i = 0
  while (i < text.length) {
    val c = text[i]
    when {
      c == '\\' -> {
        if (i + 1 >= text.length) return null to "a lone \\ at the end (write \\\\ for a backslash)"
        cur += ListChar(text[i + 1], escaped = true)
        i += 2
        continue
      }
      c == sep -> { out += cur; cur = mutableListOf() }
      else -> cur += ListChar(c, escaped = false)
    }
    i++
  }
  out += cur
  return out.map { e ->
    val start = e.indexOfFirst { it.escaped || !it.c.isWhitespace() }
    if (start < 0) emptyList() else e.subList(start, e.indexOfLast { it.escaped || !it.c.isWhitespace() } + 1)
  } to null
}

private fun List<ListChar>.text(): String = joinToString("") { it.c.toString() }

/**
 * The panel's list text as a document value of [kind] (an Int or a String
 * list): a [PropValue.ListOf] when an element is a reference, else the plain
 * literal list. A token element must be of the element kind.
 */
internal fun parseListSource(text: String, kind: PropKind, known: Map<String, PropValue.Token>): Parsed {
  val strings = kind == PropKind.StringList
  val tag = if (strings) "ls" else "li"
  val (elements, why) = listElements(text, if (strings) '|' else ',')
  if (elements == null) return Parsed(null, why)
  val items = elements.map { chars ->
    if (chars.isEmpty()) return Parsed(null, "an empty element (${if (strings) "write \"\" for an empty text" else "remove the extra ,"})")
    val e = chars.text()
    val head = chars.first().takeIf { !it.escaped }?.c
    when {
      // A quoted String element: its text as written, spaces included.
      strings && head == '"' && chars.size >= 2 && chars.last() == ListChar('"', false) -> lit(chars.subList(1, chars.size - 1).text())
      head == '◆' -> {
        val t = known[e.removePrefix("◆").trim()] ?: return Parsed(null, "no design token ${e.removePrefix("◆").trim()}")
        if (!tokenFits(listElementType(tag), t.value.let { v -> v.s ?: v.i ?: v.d ?: v.b })) {
          return Parsed(null, "${t.ref} is not ${if (strings) "a String" else "an Int"}")
        }
        t
      }
      head == '@' -> e.removePrefix("@").trim().takeIf { FIELD.matches(it) }?.let { PropValue.Bind(it) }
        ?: return Parsed(null, "$e is not a field name")
      strings -> lit(e)
      e.startsWith("#") -> hexArgb(e)?.let { lit(it) } ?: return Parsed(null, "$e is not a #RRGGBB or #AARRGGBB colour")
      else -> e.toIntOrNull()?.let { lit(it) } ?: return Parsed(null, "$e is not an Int, a token (◆C.Name) or a binding (@field)")
    }
  }
  return Parsed(canonicalValue(PropValue.ListOf(tag, items)), null)
}

/** `#RRGGBB` (opaque) or `#AARRGGBB` — the rule a mocked element follows on the wire and in the preview too. */
private fun hexArgb(s: String): Int? = s.takeIf { it.trim().startsWith("#") }?.let(::mockedIntElement)

/** The tokens [v] already holds (also those the token list no longer offers), by reference. */
internal fun tokensIn(v: Any?): Map<String, PropValue.Token> = when (v) {
  is Template -> v.parts.filterIsInstance<Token>().associate { it.ref to it.toPropValue() }
  is ListOf -> v.items.filterIsInstance<Token>().associate { it.ref to it.toPropValue() }
  else -> emptyMap()
}

/**
 * Every token the panel may name: the token list's (not ambiguous; a
 * String, Int or Boolean — never a Double, [templateTokenOffered]) and
 * [current]'s.
 */
internal fun knownTokens(current: Any?, tokens: List<EditorToken> = editorTokens): Map<String, PropValue.Token> =
  tokens.filter(::templateTokenOffered).associate { it.ref to it.toPropValue() } + tokensIn(current)

/**
 * A text field for a template or a list: it shows [source], sends each valid
 * edit through [send], and marks an invalid one (red, with the reason as its
 * tooltip) without sending it. [extra] adds buttons beside it.
 */
internal fun refValueEditor(
  source: String,
  multiline: Boolean,
  placeholder: String,
  parse: (String) -> Parsed,
  send: (PropValue) -> Unit,
): Pair<HTMLElement, (String) -> Unit> {
  val field: HTMLElement
  val read: () -> String
  val write: (String) -> Unit
  if (multiline) {
    val area = Ui.textarea()
    area.rows = source.lines().size.coerceIn(1, 6)
    area.value = source
    field = area
    read = { area.value }
    write = { area.value = it }
  } else {
    val input = Ui.input()
    input.setAttribute("type", "text")
    input.value = source
    field = input
    read = { input.value }
    write = { input.value = it }
  }
  field.setAttribute("placeholder", placeholder)
  fun check() {
    val p = parse(read())
    if (p.error != null) {
      field.setAttribute("style", "border-color:#e5484d;")
      field.setAttribute("title", p.error)
    } else {
      field.setAttribute("style", "border-color:var(--accent);")
      field.setAttribute("title", placeholder)
      send(p.value!!)
    }
  }
  field.addEventListener("input", { _ -> check() })
  field.setAttribute("style", "border-color:var(--accent);")
  field.setAttribute("title", placeholder)
  // Append text (a picked token) and send.
  return field to { more -> write(read() + more); check() }
}
