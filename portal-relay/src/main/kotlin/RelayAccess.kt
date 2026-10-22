import com.sun.net.httpserver.Filter
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Who may reach the relay (KNOWN_BUGS U35, U36). The relay has write endpoints
 * (edits, write-back to the app's source, publish), so:
 *
 * - it listens on 127.0.0.1 unless `KELIVER_RELAY_BIND=all` ([bindAddress]);
 * - it refuses to start when something already answers on the port on either
 *   loopback address ([portTakenBy]): it would bind 127.0.0.1 beside a process
 *   holding `[::1]` or `*`, and clients that try `::1` first would reach that one;
 * - every request passes [admit]: its `Host` must name this machine (no DNS
 *   rebinding), and a request carrying an `Origin` must come from a loopback
 *   origin. A web page on any other origin, open in the developer's browser,
 *   is refused before the handler runs, whatever the method. A request a
 *   browser sends without an `Origin` (an `<img>` or a navigation) is refused
 *   when the browser marks it `Sec-Fetch-Site: cross-site`.
 *
 * The relay creates its server through [gate], so every context, wherever it
 * is registered (Relay.kt, HostReports.register), runs [admit] first.
 */
internal object RelayAccess {
  const val BIND_ENV = "KELIVER_RELAY_BIND"

  /** 127.0.0.1, literally: `getLoopbackAddress()` is `::1` under `-Djava.net.preferIPv6Addresses=true`. */
  val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

  /** Every interface, loopback included (`::` on a dual-stack JVM). */
  val WILDCARD: InetAddress = InetSocketAddress(0).address

  /** The Android emulator's (10.0.2.2) and Genymotion's (10.0.3.2) names for the host's loopback. */
  private val EMULATOR_HOST_ALIASES = setOf("10.0.2.2", "10.0.3.2")

  private val LOOPBACK_ORIGIN = Regex("""^https?://(localhost|127\.0\.0\.1|\[::1])(:\d{1,5})?$""", RegexOption.IGNORE_CASE)
  private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
  private val HOST_HEADER = Regex("""^(\[[0-9A-Fa-f:.]+]|[^\[\]:]+)(:\d{1,5})?$""")

  /** `KELIVER_RELAY_BIND`: unset or empty is loopback, `all` every interface; anything else is refused. */
  fun bindAddress(value: String?): InetAddress {
    val v = value?.trim().orEmpty()
    return when (v) {
      "" -> LOOPBACK
      "all" -> WILDCARD
      else -> throw IllegalArgumentException(
        "$BIND_ENV=$v is not supported: leave it unset (loopback only) or set it to 'all' (every interface, " +
          "for a physical phone on the same network)",
      )
    }
  }

  /**
   * The loopback address already answering on [port], or null. Probes `::1` and
   * 127.0.0.1, so a listener on either (or on `*`) counts.
   */
  fun portTakenBy(port: Int, timeoutMs: Int = 300): String? =
    listOf("::1", "127.0.0.1").firstOrNull { address ->
      runCatching {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(address), port), timeoutMs) }
      }.isSuccess
    }

  /**
   * Is [host] (a `Host` header) this machine? `localhost`, a loopback IP literal,
   * the emulators' aliases for the host's loopback, or the IP literal of
   * [arrivedOn], the local address the connection came in on (a phone's
   * `http://<LAN IP>:port` with `KELIVER_RELAY_BIND=all`). A domain name other
   * than `localhost` is refused: that is what DNS rebinding sends.
   */
  fun hostAllowed(host: String?, arrivedOn: InetAddress?): Boolean {
    val m = HOST_HEADER.matchEntire(host?.trim() ?: return false) ?: return false
    val name = m.groupValues[1].lowercase()
    if (name == "localhost" || name in EMULATOR_HOST_ALIASES) return true
    val literal = when {
      name.startsWith("[") -> name.removeSurrounding("[", "]").takeIf { ':' in it }
      IPV4_LITERAL.matches(name) -> name
      else -> null
    } ?: return false
    // An out-of-range dotted quad is not a literal to getByName: it would look it up.
    if (':' !in literal && literal.split('.').any { it.toInt() > 255 }) return false
    // Both shapes are literals: getByName parses them and looks nothing up.
    val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
    return address.isLoopbackAddress || (arrivedOn != null && sameAddress(address, arrivedOn))
  }

  /** A loopback origin: `http(s)://localhost`, `127.0.0.1` or `[::1]`, any port. */
  fun originAllowed(origin: String): Boolean = LOOPBACK_ORIGIN.matches(origin.trim())

  /**
   * Admits or refuses [ex] before any handler runs. On refusal it has answered
   * 403 and returns false. When admitted with an `Origin`, the CORS headers name
   * that origin (never `*`); a request without one (a native host, curl, MCP)
   * gets none, and needs none. A preflight (`OPTIONS`) is admitted or refused
   * by the same rules, so a foreign origin never learns it may send.
   */
  fun admit(ex: HttpExchange): Boolean {
    val host = ex.requestHeaders.getFirst("Host")
    if (!hostAllowed(host, ex.localAddress?.address)) {
      refuse(ex, "this relay answers only to localhost (Host: ${host ?: "none"})")
      return false
    }
    val origin = ex.requestHeaders.getFirst("Origin")
    ex.responseHeaders.set("Vary", "Origin")
    if (origin == null) {
      // No Origin: a native host, curl, or a browser's no-cors GET (an <img>, a
      // link). Browsers mark the last as cross-site; nothing legitimate is.
      // They send Sec-Fetch-Site only to https, localhost and 127.0.0.1, so a
      // page's GET to http://10.0.2.2 or a LAN address passes unmarked. That is
      // safe only because no GET changes anything: a handler that changes
      // state must refuse every method but POST (RelayAccessTest checks this).
      if (ex.requestHeaders.getFirst("Sec-Fetch-Site").equals("cross-site", ignoreCase = true)) {
        refuse(ex, "a cross-site request from a web page is refused")
        return false
      }
      return true
    }
    if (!originAllowed(origin)) {
      refuse(ex, "requests from a web page on another origin are refused")
      return false
    }
    ex.responseHeaders.set("Access-Control-Allow-Origin", origin)
    ex.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
    ex.responseHeaders.set("Access-Control-Allow-Headers", "*")
    // The editor reads its undo/redo depths from these (an editor on another loopback port).
    ex.responseHeaders.set("Access-Control-Expose-Headers", "X-Undo-Depth, X-Redo-Depth")
    return true
  }

  /** Runs [admit] before every handler of the context it is added to. */
  val FILTER: Filter = object : Filter() {
    override fun doFilter(ex: HttpExchange, chain: Filter.Chain) {
      if (admit(ex)) chain.doFilter(ex)
    }

    override fun description(): String = "RelayAccess.admit"
  }

  /**
   * [server], with [FILTER] on every context created through it, so no
   * registration (here or in another file) can skip the gate.
   */
  fun gate(server: HttpServer): HttpServer = AdmittingHttpServer(server)

  private class AdmittingHttpServer(private val inner: HttpServer) : HttpServer() {
    override fun createContext(path: String, handler: HttpHandler): HttpContext =
      inner.createContext(path, handler).also { it.filters.add(FILTER) }

    override fun createContext(path: String): HttpContext =
      inner.createContext(path).also { it.filters.add(FILTER) }

    override fun bind(addr: InetSocketAddress, backlog: Int) = inner.bind(addr, backlog)
    override fun start() = inner.start()
    override fun setExecutor(executor: java.util.concurrent.Executor?) { inner.executor = executor }
    override fun getExecutor(): java.util.concurrent.Executor? = inner.executor
    override fun stop(delay: Int) = inner.stop(delay)
    override fun removeContext(path: String) = inner.removeContext(path)
    override fun removeContext(context: HttpContext) = inner.removeContext(context)
    override fun getAddress(): InetSocketAddress = inner.address
  }

  private fun refuse(ex: HttpExchange, why: String) {
    // Drain nothing and run nothing: the handler never sees this request.
    println("portal-server: refused ${ex.requestMethod} ${ex.requestURI.path} — $why")
    val bytes = "{\"error\":\"forbidden\",\"reason\":\"${why.replace("\"", "'")}\"}".encodeToByteArray()
    ex.responseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(403, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
  }

  private fun sameAddress(a: InetAddress, b: InetAddress): Boolean {
    // A dual-stack socket can report an IPv4 peer as ::ffff:a.b.c.d.
    fun normal(x: InetAddress): InetAddress =
      if (x is Inet6Address && x.address.let { bytes -> bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte() }) {
        InetAddress.getByAddress(x.address.copyOfRange(12, 16))
      } else {
        x
      }
    return normal(a) == normal(b)
  }
}
