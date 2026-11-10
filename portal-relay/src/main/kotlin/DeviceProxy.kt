import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.Executors

/**
 * The device proxy (PLAN_2026-11 ED3): a second, read-only server that lets a
 * debug host on the emulator or a phone load the bundles the relay stores,
 * while the relay itself stays on loopback (KNOWN_BUGS U35).
 *
 * - Off unless `KELIVER_DEVICE_PROXY` is set: `loopback` (127.0.0.1, for the
 *   emulator's 10.0.2.2 and `adb reverse`), `all` (every interface, for a phone
 *   on the same network) or one of this machine's IP literals.
 * - Its own port (`KELIVER_DEVICE_PROXY_PORT`, default the relay's port + 1).
 * - It has no route to the relay: it reads the bundle store's files itself, and
 *   answers only `GET` on `/bundles/index.json`, `/bundles/events` and
 *   `/bundles/v<N>/<file>`. Every other method is 405, every other path 404,
 *   before anything touches the disk.
 * - It is created through [RelayAccess.gate], so the relay's Host / Origin
 *   checks (DNS rebinding, cross-site pages) apply to it too.
 */
internal class DeviceProxy(private val bundlesDir: File, private val events: BundleEvents?) {
  sealed interface Route {
    data object Index : Route
    data object Events : Route
    data class BundleFile(val version: Int, val name: String) : Route
    data object MethodNotAllowed : Route
    data object NotFound : Route
  }

  /** Starts it on [address]:[port]; the caller stops it. */
  fun start(address: InetAddress, port: Int): HttpServer {
    val server = RelayAccess.gate(HttpServer.create(InetSocketAddress(address, port), 0))
    server.createContext("/") { ex ->
      try {
        when (val r = route(ex.requestMethod, ex.requestURI.rawPath)) {
          Route.MethodNotAllowed -> {
            ex.responseHeaders.set("Allow", "GET")
            empty(ex, 405)
          }
          Route.NotFound -> empty(ex, 404)
          Route.Index -> {
            ex.responseHeaders.add("Cache-Control", "no-cache")
            send(ex, relayIndexJson(bundlesDir).encodeToByteArray(), "application/json")
          }
          Route.Events -> {
            val e = events
            if (e == null) { empty(ex, 404); return@createContext }
            if (!e.hasRoom()) { empty(ex, 503); return@createContext }
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.responseHeaders.add("Cache-Control", "no-cache")
            ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
            ex.sendResponseHeaders(200, 0)
            e.subscribe(ex.responseBody) { ex.close() } // stream stays open
          }
          is Route.BundleFile -> {
            val f = bundleFile(r) ?: run { empty(ex, 404); return@createContext }
            send(ex, f.readBytes(), if (r.name.endsWith(".json")) "application/json" else "application/octet-stream")
          }
        }
      } catch (t: Throwable) {
        println("portal-server: device proxy ${ex.requestURI.rawPath} failed: $t")
        runCatching { empty(ex, 500) }
      }
    }
    // Its own small pool: a slow phone never holds the relay's thread, and the
    // event streams (bounded by BundleEvents) do not hold a thread at all.
    server.executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "device-proxy").apply { isDaemon = true } }
    server.start()
    return server
  }

  /** The file [r] names, if it is a regular file inside the store and not reached through a link. */
  internal fun bundleFile(r: Route.BundleFile): File? {
    val dir = File(bundlesDir, "v${r.version}")
    val f = File(dir, r.name)
    val root = bundlesDir.canonicalFile
    if (!f.canonicalFile.path.startsWith(root.path + File.separator)) return null
    if (Files.isSymbolicLink(dir.toPath()) || Files.isSymbolicLink(f.toPath())) return null
    return f.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
  }

  private fun send(ex: HttpExchange, bytes: ByteArray, type: String) {
    ex.responseHeaders.add("Content-Type", type)
    ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
    ex.sendResponseHeaders(200, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
  }

  private fun empty(ex: HttpExchange, code: Int) {
    ex.sendResponseHeaders(code, -1)
    ex.close()
  }

  companion object {
    const val ENV = "KELIVER_DEVICE_PROXY"
    const val PORT_ENV = "KELIVER_DEVICE_PROXY_PORT"

    private val FILE = Regex("""^/bundles/v([1-9]\d{0,8})/([A-Za-z0-9_-][A-Za-z0-9._-]{0,127})$""")

    /**
     * What [method] on [rawPath] (undecoded, without the query) reaches. Pure:
     * the whole allow-list. Anything escaped, doubled or dotted is not on it.
     */
    fun route(method: String, rawPath: String?): Route {
      if (method != "GET") return Route.MethodNotAllowed
      val p = rawPath ?: return Route.NotFound
      if ('%' in p || '\\' in p || "//" in p || "/." in p) return Route.NotFound
      return when (p) {
        "/bundles/$INDEX_FILE" -> Route.Index
        "/bundles/events" -> Route.Events
        else -> FILE.matchEntire(p)?.let { Route.BundleFile(it.groupValues[1].toInt(), it.groupValues[2]) } ?: Route.NotFound
      }
    }

    /** `KELIVER_DEVICE_PROXY`: unset or empty is off (null); `loopback`, `all`, or one of [local]'s IP literals. */
    fun bindAddress(value: String?, local: () -> Set<InetAddress> = ::localAddresses): InetAddress? {
      val v = value?.trim().orEmpty()
      return when {
        v.isEmpty() -> null
        v == "loopback" -> RelayAccess.LOOPBACK
        v == "all" -> RelayAccess.WILDCARD
        else -> {
          val literal = v.takeIf { Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(it) || (':' in it && Regex("""^[0-9A-Fa-f:.]+$""").matches(it)) }
          val address = literal?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
          require(address != null && address in local()) {
            "$ENV=$v is not supported: leave it unset (off), or set it to 'loopback' (the emulator), " +
              "'all' (every interface, for a phone on the same network) or one of this machine's IP addresses"
          }
          address
        }
      }
    }

    /** `KELIVER_DEVICE_PROXY_PORT`, or [relayPort] + 1. */
    fun port(value: String?, relayPort: Int): Int {
      val v = value?.trim().orEmpty()
      if (v.isEmpty()) return relayPort + 1
      val p = v.toIntOrNull()
      require(p != null && p in 1..65535 && p != relayPort) { "$PORT_ENV=$v must be a port other than the relay's ($relayPort)" }
      return p
    }

    fun localAddresses(): Set<InetAddress> = runCatching {
      NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
    }.getOrDefault(emptySet())

    /**
     * The bundle server URLs a debug host can use for a proxy on [bound]:[port]:
     * the emulator's alias for loopback, and each LAN IPv4 address when it is
     * bound to every interface or to one.
     */
    fun urls(bound: InetAddress, port: Int, local: () -> Set<InetAddress> = ::localAddresses): List<String> {
      val hosts = when {
        bound.isLoopbackAddress -> listOf("10.0.2.2", "127.0.0.1")
        bound.isAnyLocalAddress ->
          listOf("10.0.2.2") + local().filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress }.sorted()
        bound is Inet4Address -> listOf(bound.hostAddress)
        else -> listOf("[${bound.hostAddress.substringBefore('%')}]")
      }
      return hosts.map { "http://$it:$port" }
    }

    /** The relay's `GET /device-proxy` answer: off, or each URL with its QR code (an SVG made here). */
    fun statusJson(urls: List<String>?): String {
      if (urls == null) return "{\"enabled\":false,\"env\":\"$ENV\"}"
      val items = urls.joinToString(",") { u ->
        "{\"url\":\"$u\",\"qrSvg\":\"${QrCode.encode(u).toSvg().replace("\"", "\\\"")}\"}"
      }
      return "{\"enabled\":true,\"urls\":[$items]}"
    }
  }
}
