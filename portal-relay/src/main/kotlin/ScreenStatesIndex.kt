import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.keliver.portal.ingest.ScreenStateRecognizer
import dev.keliver.portal.ingest.ScreenStates
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
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
 * screens, for `GET /states`. The screen .kt files are the only copy; each
 * file's parse is kept until the file's modification time, size or inode changes, so
 * a request re-parses only the screens edited since the last one.
 *
 * A screen's JSON: `{"states":[{"name","function","route","routeType","fixtures"}],
 * "problems":["…"]}`. `fixtures` is the HTTP fixture set the editor gives the
 * persona (`httpFixtureSet`) when it previews the state.
 */
class ScreenStatesIndex(
  private val recognize: (fileName: String, source: String) -> ScreenStates = ScreenStateRecognizer::recognize,
) {
  /**
   * [fileKey] is the inode where the platform has one (null otherwise): it catches a file
   * replaced by another with the same size and modification time (`cp -p`, `rsync -t`,
   * an editor that writes a new file and renames it over the old one).
   */
  private class Parsed(val modified: FileTime, val size: Long, val fileKey: Any?, val states: ScreenStates)

  /** By absolute path. Bounded by the screen files there are: a gone file's entry is dropped. */
  private val parsed = ConcurrentHashMap<String, Parsed>()

  /** [file]'s states; none when it doesn't exist (a store draft that has no .kt yet). */
  fun forFile(file: File): ScreenStates {
    val key = file.absolutePath
    val attrs = runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java) }.getOrNull()
    if (attrs == null || !attrs.isRegularFile) {
      parsed.remove(key)
      return ScreenStates(emptyList())
    }
    parsed[key]?.let {
      if (it.modified == attrs.lastModifiedTime() && it.size == attrs.size() && it.fileKey == attrs.fileKey()) {
        return it.states
      }
    }
    // Attributes are read before the text: a write in between leaves an entry the next
    // request sees as changed, never a stale one that looks current.
    val states = runCatching { recognize(file.name, file.readText()) }.getOrElse {
      // Not kept: a read that failed is tried again next time.
      return ScreenStates(emptyList(), listOf("cannot read ${file.name}: ${it.message}"))
    }
    parsed[key] = Parsed(attrs.lastModifiedTime(), attrs.size(), attrs.fileKey(), states)
    return states
  }

  /** Every screen in [dir] (`<screen>.kt`) with at least one state or problem, by name. */
  fun forDir(dir: File): Map<String, ScreenStates> {
    val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") } ?: emptyArray()).sortedBy { it.name }
    val present = files.mapTo(HashSet()) { it.absolutePath }
    val dirPath = dir.absoluteFile
    parsed.keys.removeIf { File(it).parentFile == dirPath && it !in present }
    return files
      .associate { it.name.removeSuffix(".kt") to forFile(it) }
      .filterValues { it.states.isNotEmpty() || it.problems.isNotEmpty() }
  }

  /** What `GET /states` needs from the relay: where a project's screens are, and which exist. */
  interface Screens {
    fun dir(project: String): File

    /** A store document or a `.kt` (see Relay.kt's knownScreen). */
    fun known(project: String, screen: String): Boolean

    fun names(project: String): List<String>
  }

  companion object {
    /**
     * `GET /states[?project=<p>][&screen=<s>]`, read-only. `project` defaults to
     * `default`; `?screen=` names one screen (404 when the app has no such screen),
     * without it every screen that declares states or has problems. Any other
     * method is 405. [wrap] is the relay's handler (OPTIONS, errors).
     */
    fun register(
      server: HttpServer,
      index: ScreenStatesIndex,
      screens: Screens,
      wrap: (HttpExchange, () -> Unit) -> Unit,
    ) {
      server.createContext("/states") { ex ->
        wrap(ex) {
          if (ex.requestMethod != "GET") {
            ex.responseHeaders.add("Allow", "GET")
            send(ex, 405, null)
            return@wrap
          }
          val q = query(ex)
          val project = safe(q["project"] ?: "default")
          val dir = screens.dir(project)
          val screen = q["screen"]?.let(::safe)
          when {
            screen == null -> send(ex, 200, toJson(index.forDir(dir)))
            !screens.known(project, screen) -> send(
              ex,
              404,
              buildJsonObject { put("error", UnknownScreen(project, screen, screens.names(project)).message) }.toString(),
            )
            else -> send(ex, 200, toJson(screen, index.forFile(File(dir, "$screen.kt"))))
          }
        }
      }
    }

    fun toJson(screen: String, states: ScreenStates): String =
      buildJsonObject {
        put("screen", screen)
        screenJson(states).forEach { (k, v) -> put(k, v) }
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

    private fun send(ex: HttpExchange, code: Int, body: String?) {
      if (body == null) { ex.sendResponseHeaders(code, -1); ex.close(); return }
      val bytes = body.encodeToByteArray()
      ex.responseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(code, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
  }
}
