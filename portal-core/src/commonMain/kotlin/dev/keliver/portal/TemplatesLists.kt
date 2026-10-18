package dev.keliver.portal

/**
 * K4: a String TEMPLATE over grammar references — `"Rating: $rating"`,
 * `"${b.count} items"`, `"${C.Brand} · ${item.city}"`. Each part is a
 * literal text segment (a `String`, its decoded value) or a reference: a
 * [Bind] (a component parameter, a screen binding field, an item field
 * `item.x`) or a [Token]. Preview expansion may replace a reference by the
 * value it stands for (a literal, a [Conditional], another [Template]); the
 * text is then what [templateText] evaluates.
 *
 * Arbitrary expressions (`${a + b}`, `${x.uppercase()}`) are not grammar:
 * the recognizer keeps such a call RawCode.
 */
public data class Template(val parts: List<Any?>)

/**
 * K4: a list value whose elements may be references — `listOf(C.Start,
 * C.End)`, `listOf(16777215, C.A, b.c)`. [kind] is the element kind's wire
 * tag: `"li"` (Int elements, a `List<Int>` prop) or `"ls"` (String elements,
 * a `List<String>` prop). Each item is a literal of that kind, a [Token] of
 * that kind, or a [Bind]. A list with only literal elements stays a plain
 * Kotlin `List` (as before K4).
 */
public data class ListOf(val kind: String, val items: List<Any?>)

/** The element source type of a [ListOf] of [kind] ("Int" / "String"); null for any other tag. */
public fun listElementType(kind: String): String? = when (kind) {
  "li" -> "Int"
  "ls" -> "String"
  else -> null
}

/** The [ListOf] kind of a list prop of Kotlin source type [sourceType] (`List<Int>` → "li"), else null. */
public fun listKindOf(sourceType: String?): String? = when (sourceType?.replace(" ", "")) {
  "List<Int>" -> "li"
  "List<String>" -> "ls"
  else -> null
}

/**
 * A scalar value as a template prints it: a String as itself, an Int or a
 * Boolean as Kotlin prints them, a Double in plain decimal notation (the
 * preview approximates the device, whose `toString()` uses exponent
 * notation outside 1e-3..1e7), a [Token] as its value. Null for anything
 * else (a reference without a value).
 */
public fun templateValueText(v: Any?): String? = when (val x = tokenValue(v)) {
  is String -> x
  is Int -> x.toString()
  is Long -> x.toString()
  is Boolean -> x.toString()
  is Double -> kotlinDoubleLiteral(x)
  is Template -> templateText(x) { null }
  else -> null
}

/**
 * [t]'s text: literal parts verbatim, each other part through [resolve]
 * (null = not known here) — then its own value ([templateValueText]), and
 * finally a `{field}` placeholder for an unresolved binding (as the preview
 * shows an unmocked one).
 */
public fun templateText(t: Template, resolve: (Any?) -> String?): String = buildString {
  for (p in t.parts) {
    when (p) {
      is String -> append(p)
      is Template -> append(templateText(p, resolve))
      else -> append(resolve(p) ?: templateValueText(p) ?: (p as? Bind)?.let { "{${it.field}}" } ?: "")
    }
  }
}

/** The items of a list value — a [ListOf]'s, or a plain list's; empty for anything else. */
public fun listItems(v: Any?): List<Any?> = when (v) {
  is ListOf -> v.items
  is List<*> -> v
  else -> emptyList()
}

/** True when [c] may follow a `$name` reference without becoming part of the name. */
private fun continuesIdentifier(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

private val SIMPLE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/**
 * A reference inside a string template: `$name` where that reads back as the
 * name ([next] = the character that follows it in the template), else
 * `${ref}` (a dotted reference always needs braces).
 */
public fun templateRef(ref: String, next: Char?): String =
  if (SIMPLE_NAME.matches(ref) && !continuesIdentifier(next)) "\$" + ref else "\${" + ref + "}"

/** [value] as the CONTENT of a Kotlin string literal (no quotes; `$`, `"`, `\` and controls escaped). */
public fun kotlinStringContent(value: String): String = kotlinStringLiteral(value).let { it.substring(1, it.length - 1) }

/**
 * [t] as a Kotlin string template, [ref] spelling a binding's reference
 * (`b.count`, `item.city`, a parameter's name); a token is written as its
 * reference. A part that is neither (a value preview expansion substituted)
 * is written as its text.
 */
public fun fmtTemplate(t: Template, ref: (String) -> String): String = buildString {
  append('"')
  t.parts.forEachIndexed { i, p ->
    val next = (t.parts.getOrNull(i + 1) as? String)?.firstOrNull()
    when (p) {
      is String -> append(kotlinStringContent(p))
      is Bind -> append(templateRef(ref(p.field), next))
      is Token -> append(templateRef(p.ref, next))
      else -> append(kotlinStringContent(templateValueText(p) ?: ""))
    }
  }
  append('"')
}

/** K4: the screen-binding fields a template's or a list's references read (dotted item fields included). */
public fun referencedBinds(v: Any?): List<Bind> = when (v) {
  is Template -> v.parts.flatMap { if (it is Bind) listOf(it) else referencedBinds(it) }
  is ListOf -> v.items.filterIsInstance<Bind>()
  is Conditional -> referencedBinds(v.whenTrue) + referencedBinds(v.whenFalse)
  else -> emptyList()
}
