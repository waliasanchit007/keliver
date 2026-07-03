/*
 * spike/keliver-web portal — the local-first PORTAL SERVER (P2).
 * Evolved from the M2 tree relay: a JDK HttpServer (still no deps) that now
 * persists projects/screens/drafts as files under ~/.keliver-portal/ so a
 * browser refresh never loses work.
 *
 * Endpoints (all CORS-open; the browser editor drives them):
 *   GET  /projects                          -> ["default", ...]
 *   POST /projects            body=name     -> 204 (creates project + "main" screen)
 *   GET  /screens?project=P                 -> ["main", ...]
 *   POST /screens?project=P   body=name     -> 204
 *   GET  /draft?project=P&screen=S          -> stored tree JSON or {}
 *   PUT  /draft?project=P&screen=S body=json-> 204
 *   POST /active?project=P&screen=S         -> 204 (which screen the device mirrors)
 *   GET|POST /tree                          -> LEGACY, unchanged shape: the ACTIVE
 *                                              screen's draft. The device guest keeps
 *                                              polling GET /tree exactly as before.
 *
 * Store: ~/.keliver-portal/<project>/<screen>.json ; active screen in
 * ~/.keliver-portal/active (two lines: project, screen). default/main auto-created.
 * Binds 0.0.0.0 so the emulator reaches it at 10.0.2.2:8077.
 */
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder

private const val PORT = 8077

private val root = File(System.getProperty("user.home"), ".keliver-portal")
private val activeFile = File(root, "active")

/** project/screen names are path segments — restrict to a safe charset. */
private fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unnamed" }

private fun screenFile(project: String, screen: String) = File(File(root, safe(project)), "${safe(screen)}.json")

private fun activeScreen(): Pair<String, String> {
  val lines = runCatching { activeFile.readLines() }.getOrDefault(emptyList())
  return (lines.getOrNull(0) ?: "default") to (lines.getOrNull(1) ?: "main")
}

private fun setActive(project: String, screen: String) {
  activeFile.writeText("${safe(project)}\n${safe(screen)}\n")
}

private fun ensureDefaults() {
  root.mkdirs()
  val main = screenFile("default", "main")
  if (!main.exists()) { main.parentFile.mkdirs(); main.writeText("{}") }
  if (!activeFile.exists()) setActive("default", "main")
}

private fun cors(ex: HttpExchange) {
  ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
  ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
  ex.responseHeaders.add("Access-Control-Allow-Headers", "*")
}

private fun query(ex: HttpExchange): Map<String, String> =
  (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
    val (k, v) = it.split('=', limit = 2)
    k to URLDecoder.decode(v, "UTF-8")
  }

private fun respond(ex: HttpExchange, code: Int, body: String? = null, contentType: String = "application/json") {
  if (body == null) { ex.sendResponseHeaders(code, -1); ex.close(); return }
  val bytes = body.encodeToByteArray()
  ex.responseHeaders.add("Content-Type", contentType)
  ex.sendResponseHeaders(code, bytes.size.toLong())
  ex.responseBody.use { it.write(bytes) }
}

private fun jsonList(items: List<String>): String =
  items.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }

private fun handle(ex: HttpExchange, block: () -> Unit) {
  cors(ex)
  if (ex.requestMethod == "OPTIONS") { respond(ex, 204); return }
  runCatching(block).onFailure {
    println("portal-server: ${ex.requestURI} failed: $it")
    runCatching { respond(ex, 500, "{\"error\":\"${it.message}\"}") }
  }
}

fun main() {
  ensureDefaults()
  val server = HttpServer.create(InetSocketAddress(PORT), 0)

  server.createContext("/projects") { ex ->
    handle(ex) {
      when (ex.requestMethod) {
        "GET" -> respond(ex, 200, jsonList(root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()))
        "POST" -> {
          val name = safe(ex.requestBody.readBytes().decodeToString().trim())
          val main = screenFile(name, "main")
          main.parentFile.mkdirs()
          if (!main.exists()) main.writeText("{}")
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  server.createContext("/screens") { ex ->
    handle(ex) {
      val project = safe(query(ex)["project"] ?: "default")
      when (ex.requestMethod) {
        "GET" -> respond(
          ex, 200,
          jsonList(
            File(root, project).listFiles { f -> f.name.endsWith(".json") }
              ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList(),
          ),
        )
        "POST" -> {
          val name = safe(ex.requestBody.readBytes().decodeToString().trim())
          val f = screenFile(project, name)
          f.parentFile.mkdirs()
          if (!f.exists()) f.writeText("{}")
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  server.createContext("/draft") { ex ->
    handle(ex) {
      val q = query(ex)
      val project = q["project"] ?: "default"
      val screen = q["screen"] ?: "main"
      val f = screenFile(project, screen)
      when (ex.requestMethod) {
        "GET" -> respond(ex, 200, if (f.exists()) f.readText() else "{}")
        "PUT" -> {
          f.parentFile.mkdirs()
          f.writeText(ex.requestBody.readBytes().decodeToString())
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  server.createContext("/active") { ex ->
    handle(ex) {
      when (ex.requestMethod) {
        "GET" -> {
          val (p, s) = activeScreen()
          respond(ex, 200, "{\"project\":\"$p\",\"screen\":\"$s\"}")
        }
        "POST" -> {
          val q = query(ex)
          setActive(q["project"] ?: "default", q["screen"] ?: "main")
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  // LEGACY device endpoint — unchanged wire shape. Mirrors the ACTIVE screen's draft.
  server.createContext("/tree") { ex ->
    handle(ex) {
      val (p, s) = activeScreen()
      val f = screenFile(p, s)
      when (ex.requestMethod) {
        "GET" -> respond(ex, 200, if (f.exists()) f.readText() else "{}")
        "POST" -> {
          f.parentFile.mkdirs()
          f.writeText(ex.requestBody.readBytes().decodeToString())
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  server.executor = null
  server.start()
  println("portal-server: :$PORT — store=$root  active=${activeScreen()}  (/projects /screens /draft /active /tree)")
}
