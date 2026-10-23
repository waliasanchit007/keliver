import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.PropKind
import dev.keliver.portal.editableProps
import dev.keliver.portal.modifierSpecs
import dev.keliver.portal.document.PropValue
import dev.keliver.portal.document.lit
import dev.keliver.portal.propSourceType
import dev.keliver.portal.tokenFits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// K2: the app's design tokens (`object C { const val Ink = … }`), from the
// relay's GET /tokens. A Color/Int/Text/Double/Bool prop can be bound to one:
// it shows as a token chip, the picker lists the tokens of a fitting kind, and
// "detach" writes the resolved literal instead.

/** One token as `/tokens` lists it. [value] is the resolved Kotlin value. */
internal data class EditorToken(
  val ref: String,
  val kind: String,
  val value: Any?,
  val import: String?,
  val file: String?,
  val ambiguous: Boolean,
) {
  fun toPropValue(): PropValue.Token = PropValue.Token(ref, lit(value), import)
}

internal var editorTokens: List<EditorToken> = emptyList()
  private set

internal fun setEditorTokens(tokens: List<EditorToken>) { editorTokens = tokens }

internal fun parseTokens(json: String): List<EditorToken> = runCatching {
  Json.parseToJsonElement(json).jsonArray.mapNotNull { el ->
    val o = el.jsonObject
    val kind = o["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null
    val raw = o["value"]?.takeIf { it !is JsonNull }?.jsonPrimitive ?: return@mapNotNull null
    val value: Any = when (kind) {
      "String" -> raw.content
      "Int" -> raw.intOrNull ?: return@mapNotNull null
      "Long" -> raw.longOrNull ?: return@mapNotNull null
      "Double" -> raw.doubleOrNull ?: return@mapNotNull null
      "Boolean" -> raw.booleanOrNull ?: return@mapNotNull null
      else -> return@mapNotNull null
    }
    EditorToken(
      ref = o["ref"]!!.jsonPrimitive.content,
      kind = kind,
      value = value,
      import = o["import"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
      file = o["file"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
      ambiguous = o["ambiguous"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
  }
}.getOrDefault(emptyList())

/**
 * E13: whether an Int token's value is a colour — an ARGB with a non-zero
 * alpha byte (≥ 0x01000000 or negative). No size, count, weight or code is
 * that large, and an Int constant written as `0xFF…` is exactly that; a fully
 * transparent colour (`0x00RRGGBB`) reads as a number.
 */
internal fun isColorValue(value: Any?): Boolean = value is Int && (value ushr 24) != 0

private val NUMBER_WORDS = setOf(
  "max", "min", "limit", "count", "size", "unlimited", "none", "amount", "total", "lines", "weight",
  "index", "id", "code", "duration", "ms", "millis", "seconds", "dp", "px", "step", "cap", "threshold",
  "price", "fee", "balance", "budget",
)
private val COLOR_WORDS = setOf(
  "color", "colour", "argb", "tint", "bg", "background", "ink", "fill", "stroke", "border", "scrim", "overlay", "shadow",
)

/**
 * Review: whether Int token [ref] = [value] is a colour. Its value must be one ([isColorValue]);
 * then a name that says number wins — `UNLIMITED = -1`, `AMOUNT_CAP = 20_000_000` — unless it
 * also says colour (`BorderMaxArgb`). Any other value with an alpha byte is a colour (`Veil`).
 */
internal fun isColorToken(ref: String, value: Any?): Boolean {
  if (!isColorValue(value)) return false
  val words = ref.substringAfterLast('.').replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase()
    .split(' ', '_', '-').filter { it.isNotEmpty() }
  return when {
    words.any { it in COLOR_WORDS } -> true
    words.any { it in NUMBER_WORDS } -> false
    else -> true
  }
}

/** The catalog kind of wire key [key] on a [nodeType] node (a widget prop, a modifier argument, a component parameter). */
internal fun propKindOf(nodeType: String, key: String, components: ComponentRegistry): PropKind? =
  if (key.startsWith("mod.")) {
    modifierSpecs.firstOrNull { it.name == key.removePrefix("mod.").substringBefore('.') }
      ?.props?.firstOrNull { it.name == key.removePrefix("mod.").substringAfter('.') }?.kind
  } else {
    (components.spec(nodeType)?.props ?: editableProps(nodeType)).firstOrNull { it.name == key }?.kind
  }

/**
 * The tokens that may stand for wire key [key] on a node of [nodeType]: of
 * exactly the prop's plain literal type (never a Dp, Float or enum prop), not
 * ambiguous, matching [query] (by reference or value), by reference. E13: of
 * the prop's meaning too — a colour prop takes colour tokens, any other Int
 * prop only Int tokens that are not colours ([isColorToken]).
 */
internal fun tokensFor(
  nodeType: String,
  key: String,
  components: ComponentRegistry,
  tokens: List<EditorToken> = editorTokens,
  query: String = "",
): List<EditorToken> {
  val type = propSourceType(nodeType, key, components)
  val q = query.trim().lowercase()
  val kind = propKindOf(nodeType, key, components)
  return tokens
    .filter { !it.ambiguous && tokenFits(type, it.value) }
    .filter { type != "Int" || kind == null || isColorToken(it.ref, it.value) == (kind == PropKind.Color) }
    .filter { q.isEmpty() || q in it.ref.lowercase() || q in it.value.toString().lowercase() }
    .sortedBy { it.ref }
}
