package dev.keliver.portal

/**
 * K2: a prop value bound to a design-token CONSTANT in the app's own sources
 * (`colorArgb = C.Ink`, `url = Assets.Logo`). [ref] is the reference exactly as
 * source writes it (`C.Ink`, or a bare top-level `Ink`) — the exporter and
 * write-back write it back; [value] is the constant's value computed at ingest
 * (a String, Int, Double or Boolean) — what the preview renders; [importFq] is
 * what a file using the reference must import (`app.components.C`, or the
 * top-level constant itself), null when nothing is needed.
 *
 * A token is grammar only where the source takes a plain literal of its kind:
 * a String/Int/Double/Boolean prop or modifier argument ([tokenFits]). A
 * reference that stops resolving, or resolves to a different kind, is RawCode
 * on the next ingest — never a silently wrong value.
 */
public data class Token(val ref: String, val value: Any?, val importFq: String? = null)

/** A prop value with any [Token] replaced by its resolved value (everything else unchanged). */
public fun tokenValue(v: Any?): Any? = if (v is Token) v.value else v

/** The Kotlin type of a token value: "String", "Int", "Long", "Double", "Boolean" — null for anything else. */
public fun tokenKindOf(value: Any?): String? = when (value) {
  is String -> "String"
  is Int -> "Int"
  is Long -> "Long"
  is Double -> "Double"
  is Boolean -> "Boolean"
  else -> null
}

/**
 * True when a token whose value is [value] may stand where the source type is
 * [sourceType] (see [propSourceType]): only the four plain-literal types, and
 * only the same one — Kotlin converts none of them implicitly (an Int constant
 * is not a Double, a Double is not a Float or a Dp, an Int is not a
 * `Constraint`).
 */
public fun tokenFits(sourceType: String?, value: Any?): Boolean =
  sourceType != null && sourceType in TOKEN_SOURCE_TYPES && tokenKindOf(value) == sourceType

private val TOKEN_SOURCE_TYPES = setOf("String", "Int", "Double", "Boolean")

/**
 * The Kotlin source type of the value at wire key [key] on a node of
 * [nodeType]: a widget prop ("Int", "Double", "Dp", "Constraint", "Float", …),
 * a modifier argument (`mod.<Name>.<prop>`, from [ModifierSpec.argTypes]; a
 * Margin side is "Dp"), or a project component's scalar parameter (from its
 * signature). Null when unknown.
 */
public fun propSourceType(nodeType: String, key: String, components: ComponentRegistry = EmptyComponentRegistry): String? {
  if (key.startsWith("mod.")) {
    val name = key.removePrefix("mod.").substringBefore('.')
    val prop = key.removePrefix("mod.$name.").takeIf { it != key } ?: return null
    val spec = modifierSpecs.firstOrNull { it.name == name } ?: return null
    if ('.' in prop) return if (spec.argTypes.contains("Margin")) "Dp" else null // a Margin side
    val idx = spec.props.indexOfFirst { it.name == prop }.takeIf { it >= 0 } ?: return null
    // argTypes has one entry per ARGUMENT; a Margin argument spans four props.
    var cursor = 0
    for (type in spec.argTypes) {
      val width = if (type == "Margin") 4 else 1
      if (idx < cursor + width) return if (type == "Margin") "Dp" else type
      cursor += width
    }
    return null
  }
  widgetPropSourceTypes["$nodeType.$key"]?.let { return it }
  val comp = components.spec(nodeType) ?: return null
  val prop = comp.props.firstOrNull { it.name == key } ?: return null
  // A registry built without signatures (the editor's) still knows the scalar kind.
  return comp.paramTypes[key]?.trim()?.removeSuffix("?")?.trim() ?: kotlinTypeOf(prop.kind)
}

/**
 * Records the import a [Token] needs when it is written as a reference for a
 * literal of [sourceType] (the exporter writes the resolved value otherwise).
 */
internal fun MutableSet<String>.addTokenImport(v: Any?, sourceType: String?) {
  // K3: a conditional value writes each branch.
  if (v is Conditional) {
    addTokenImport(v.whenTrue, sourceType)
    addTokenImport(v.whenFalse, sourceType)
    return
  }
  // K4: a template writes every token part as its reference (a String, Int or
  // Boolean token prints into the String; never a Double — templateTokenFits);
  // a list writes each element token of its element kind.
  if (v is Template) {
    v.parts.forEach { p -> if (p is Token) p.importFq?.let { add(it) } else if (p is Template) addTokenImport(p, sourceType) }
    return
  }
  if (v is ListOf) {
    v.items.forEach { addTokenImport(it, listElementType(v.kind)) }
    return
  }
  // K7: an arithmetic value writes each Int token operand as its reference; `x.dp` needs `dp`
  // (`Dp(x.toDouble())` needs `Dp`, which a Dp prop always imports).
  if (v is Arith || v is IntDp) {
    arithOperands(v).forEach { o -> if (o is Token && o.value is Int) o.importFq?.let { add(it) } }
    if (v is IntDp) add(if (v.ctor) "dev.keliver.ui.Dp" else "dev.keliver.ui.dp")
    return
  }
  if (v is Token && tokenKindOf(v.value) == sourceType) v.importFq?.let { add(it) }
}
