import dev.keliver.portal.ComponentRegistry
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
 * The tokens that may stand for wire key [key] on a node of [nodeType]: of
 * exactly the prop's plain literal type (never a Dp, Float or enum prop), not
 * ambiguous, matching [query] (by reference or value), by reference.
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
  return tokens
    .filter { !it.ambiguous && tokenFits(type, it.value) }
    .filter { q.isEmpty() || q in it.ref.lowercase() || q in it.value.toString().lowercase() }
    .sortedBy { it.ref }
}
