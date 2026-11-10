import dev.keliver.portal.ingest.ScreenStateRecognizer
import dev.keliver.portal.ingest.ScreenStates
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * S1 (ADOPTION_PLAN item 5): the `@ScreenState` declarations of an app's
 * screens, for `GET /states`. Read from the screen .kt files on each request —
 * the files are the only copy, so there is nothing to keep fresh.
 *
 * A screen's JSON: `{"states":[{"name","function","route","routeType","fixtures"}],
 * "problems":["…"]}`. `fixtures` is the HTTP fixture set the editor gives the
 * persona (`httpFixtureSet`) when it previews the state.
 */
object ScreenStatesIndex {
  fun forFile(file: File): ScreenStates =
    if (!file.isFile) ScreenStates(emptyList())
    else runCatching { ScreenStateRecognizer.recognize(file.name, file.readText()) }
      .getOrElse { ScreenStates(emptyList(), listOf("cannot read ${file.name}: ${it.message}")) }

  /** Every screen in [dir] (`<screen>.kt`) with at least one state or problem, by name. */
  fun forDir(dir: File): Map<String, ScreenStates> =
    (dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") } ?: emptyArray())
      .sortedBy { it.name }
      .associate { it.name.removeSuffix(".kt") to forFile(it) }
      .filterValues { it.states.isNotEmpty() || it.problems.isNotEmpty() }

  fun toJson(screen: String, states: ScreenStates): String =
    buildJsonObject {
      put("screen", screen)
      putAll(screenJson(states))
    }.toString()

  fun toJson(all: Map<String, ScreenStates>): String =
    buildJsonObject {
      putJsonObject("screens") { all.forEach { (name, s) -> put(name, screenJson(s)) } }
    }.toString()

  private fun screenJson(s: ScreenStates): JsonObject = buildJsonObject {
    putJsonArray("states") {
      s.states.forEach { st ->
        add(
          buildJsonObject {
            put("name", st.name)
            put("function", st.function)
            put("route", st.route)
            put("routeType", JsonPrimitive(st.routeType))
            put("fixtures", st.fixtures)
          },
        )
      }
    }
    put("problems", buildJsonArray { s.problems.forEach { add(it) } })
  }

  private fun kotlinx.serialization.json.JsonObjectBuilder.putAll(o: JsonObject) =
    o.forEach { (k, v) -> put(k, v) }
}
