/*
 * spike/keliver-web portal M2 — the tree relay.
 * A minimal JDK HttpServer (no deps). The browser portal POSTs the serialized
 * WidgetNode tree to /tree; the Android device polls GET /tree. CORS-open so the
 * browser can POST. Binds 0.0.0.0 so the emulator can reach it at 10.0.2.2:8077.
 */
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

private const val PORT = 8077

@Volatile
private var currentTree: String = "{}"

private fun cors(ex: HttpExchange) {
  ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
  ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
  ex.responseHeaders.add("Access-Control-Allow-Headers", "*")
}

fun main() {
  val server = HttpServer.create(InetSocketAddress(PORT), 0)
  server.createContext("/tree") { ex ->
    cors(ex)
    when (ex.requestMethod) {
      "OPTIONS" -> { ex.sendResponseHeaders(204, -1); ex.close() }
      "POST" -> {
        currentTree = ex.requestBody.readBytes().decodeToString()
        ex.sendResponseHeaders(204, -1); ex.close()
        println("relay: stored tree (${currentTree.length} chars)")
      }
      "GET" -> {
        val body = currentTree.encodeToByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
      }
      else -> { ex.sendResponseHeaders(405, -1); ex.close() }
    }
  }
  server.executor = null
  server.start()
  println("relay: listening on :$PORT  (POST/GET /tree)  — emulator reaches it at 10.0.2.2:$PORT")
}
