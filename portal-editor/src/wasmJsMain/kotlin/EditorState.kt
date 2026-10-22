/*
 * The State panel's model (E4/E6/E38): every value the canvas reads from the
 * screen's bindings — the fields its props are bound to, the fields its `if`
 * blocks test (which the derived contract does not list), and its lists' row
 * counts — with a coherent default for each, so the canvas shows one state the
 * app can actually be in, never every branch at once.
 *
 * Defaults come from a named [Scenario] and from realistic samples chosen by
 * the field's name; what the user sets overrides them and is kept per screen
 * in this browser. Nothing here is written to the app's source.
 */
import dev.keliver.portal.CondTest
import dev.keliver.portal.PropKind
import dev.keliver.portal.ScreenContract
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.condOf

/** How the State panel edits a value. */
internal enum class StateKind { Bool, Text, Int, Double, Rows }

/** Where a value is read: an `if` block, a bound prop, or a list's row count. */
internal enum class StateSource { Condition, Prop, List }

internal data class StateField(val name: String, val kind: StateKind, val source: StateSource)

/** Every value the canvas reads for [tree]: condition fields first, then bound props, then lists. */
internal fun stateFields(tree: WidgetNode, contract: ScreenContract): List<StateField> {
  val out = LinkedHashMap<String, StateField>()
  fun visit(n: WidgetNode) {
    if (n.type == "Condition") {
      condOf(n)?.terms?.forEach { t ->
        // A dotted field is an item's (inside a Repeat): its rows mock it.
        if ('.' in t.field || t.field in out) return@forEach
        val kind = when (t.test) {
          CondTest.Bool -> StateKind.Bool
          CondTest.Empty, CondTest.Blank -> StateKind.Text
          CondTest.Eq -> when (t.operand) {
            is Boolean -> StateKind.Bool
            is Number -> StateKind.Int
            else -> StateKind.Text
          }
          CondTest.Null -> return@forEach
        }
        out[t.field] = StateField(t.field, kind, StateSource.Condition)
      }
    }
    n.children.forEach(::visit)
  }
  visit(tree)
  contract.fields.forEach { (name, kind) ->
    if (name in out) return@forEach
    val k = when (kind) {
      PropKind.Bool -> StateKind.Bool
      PropKind.Int, PropKind.Color -> StateKind.Int
      PropKind.Double -> StateKind.Double
      else -> StateKind.Text
    }
    out[name] = StateField(name, k, StateSource.Prop)
  }
  fun lists(n: WidgetNode) {
    if (n.type == "Repeat") {
      (n.props["items"] as? String)?.takeIf { it.isNotBlank() && it !in out }?.let {
        out[it] = StateField(it, StateKind.Rows, StateSource.List)
      }
    }
    n.children.forEach(::lists)
  }
  lists(tree)
  return out.values.toList()
}

/** What a flag means for the screen's state, read from its name. */
internal enum class StateRole { Loading, Error, Empty, Content }

private fun words(name: String): List<String> =
  name.split('.').last().replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().split(' ', '_', '-').filter { it.isNotEmpty() }

internal fun roleOf(name: String): StateRole {
  val w = words(name)
  fun any(vararg k: String) = w.any { word -> k.any { word.startsWith(it) } }
  return when {
    any("loading", "refreshing", "pending", "polling", "shimmer", "skeleton", "busy", "fetching") -> StateRole.Loading
    any("error", "failed", "failure", "retry", "offline") -> StateRole.Error
    any("empty", "none", "nodata", "noresult") -> StateRole.Empty
    else -> StateRole.Content
  }
}

/** A coherent screen state: which kinds of flags are on. */
internal enum class Scenario(val label: String, val on: Set<StateRole>) {
  Loaded("Loaded", setOf(StateRole.Content)),
  Loading("Loading", setOf(StateRole.Loading)),
  Error("Error", setOf(StateRole.Error, StateRole.Content)),
  Empty("Empty", setOf(StateRole.Empty, StateRole.Content)),
}

/** The scenarios worth offering for [fields]: Loaded, plus each one some flag actually switches. */
internal fun scenariosFor(fields: List<StateField>): List<Scenario> {
  val roles = fields.filter { it.kind == StateKind.Bool || it.source == StateSource.Condition }.map { roleOf(it.name) }.toSet()
  return Scenario.entries.filter { s -> s == Scenario.Loaded || s.on.any { it != StateRole.Content && it in roles } }
}

/** A realistic sample for a text field, chosen by its name and never longer than real copy. */
internal fun sampleText(name: String): String {
  val w = words(name)
  fun any(vararg k: String) = w.any { word -> k.any { word.startsWith(it) } }
  return when {
    any("rate", "percent", "yield", "ytm", "return", "interest", "apr", "discount") -> "12.5%"
    any("initial") -> "AB"
    any("url", "image", "icon", "logo", "avatar", "photo", "thumbnail") -> ""
    any("date", "maturity", "expiry", "deadline", "day") -> "12 Jan 2027"
    any("time") -> "10:30 AM"
    any("count", "users", "units", "qty", "quantity", "number", "members") -> "1,250"
    any("amount", "price", "funds", "total", "payable", "invest", "balance", "value", "cost", "fee", "min", "max") -> "10,000"
    any("email") -> "alex@example.com"
    any("phone", "mobile") -> "+1 555 0100"
    any("message", "error", "description", "body", "subtitle", "disclaimer", "info", "note", "summary", "hint") ->
      "A short sample sentence."
    else -> w.joinToString(" ").replaceFirstChar { it.uppercase() }
  }
}

/** The value a field shows under [scenario] when the user has not set it. */
internal fun defaultValue(field: StateField, scenario: Scenario): String = when (field.kind) {
  StateKind.Bool -> (roleOf(field.name) in scenario.on).toString()
  // A text a condition tests (`isNotBlank()`): present when its role is on in this scenario.
  StateKind.Text -> if (field.source == StateSource.Condition && roleOf(field.name) !in scenario.on) "" else sampleText(field.name)
  StateKind.Int -> if (roleOf(field.name) == StateRole.Content && words(field.name).any { it.startsWith("count") || it.startsWith("total") }) "3" else "1"
  StateKind.Double -> "1.5"
  StateKind.Rows -> if (scenario == Scenario.Empty || scenario == Scenario.Loading) "0" else "3"
}

/** The mocks the canvas reads: [user] overrides on top of [scenario]'s defaults. */
internal fun effectiveMocks(fields: List<StateField>, scenario: Scenario, user: Map<String, String>): Map<String, String> {
  val out = LinkedHashMap<String, String>()
  fields.forEach { f -> out[f.name] = user[f.name] ?: defaultValue(f, scenario) }
  // Item-scoped mocks (rows: a|b|c) and anything else the user set stay.
  user.forEach { (k, v) -> if (k !in out) out[k] = v }
  return out
}

/** What the user chose for one screen: a scenario and the values they set. */
internal data class ScreenState(val scenario: Scenario = Scenario.Loaded, val values: Map<String, String> = emptyMap())

private val stateJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

internal fun encodeScreenState(s: ScreenState): String =
  kotlinx.serialization.json.buildJsonObject {
    put("scenario", kotlinx.serialization.json.JsonPrimitive(s.scenario.name))
    put("values", kotlinx.serialization.json.JsonObject(s.values.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
  }.toString()

internal fun decodeScreenState(text: String?): ScreenState = runCatching {
  val o = stateJson.parseToJsonElement(text ?: return ScreenState()) as kotlinx.serialization.json.JsonObject
  val scenario = (o["scenario"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    ?.let { n -> Scenario.entries.firstOrNull { it.name == n } } ?: Scenario.Loaded
  val values = (o["values"] as? kotlinx.serialization.json.JsonObject)
    ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
  ScreenState(scenario, values)
}.getOrDefault(ScreenState())
