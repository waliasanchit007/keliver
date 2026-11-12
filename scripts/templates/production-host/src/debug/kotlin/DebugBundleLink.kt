package @@PACKAGE@@

/**
 * DEBUG builds only (ED3): the debug link that points this host at the relay's device proxy,
 * which the editor's "On a device" panel shows as a QR code:
 *
 *   <scheme>://keliver-debug/bundles?server=<the proxy's URL, percent-encoded>
 *   keliver-com.example.shop://keliver-debug/bundles?server=http%3A%2F%2F192.168.1.20%3A8078
 *
 * The scheme is this app's own (`keliver-` and the app's package, lower case, `_` as `-`). The
 * server must be on this device, the local network or a private VPN (see [server]), on any port
 * from [MIN_PORT] up: the device proxy listens on the relay's port + 1 unless
 * KELIVER_DEVICE_PROXY_PORT moved it, and a host need not know which.
 *
 * Pure Kotlin, and the same file in the Android host (src/debug) and the iOS host
 * (src/debuglink): a release build compiles neither it nor the link's handler. DebugBundleLinkTest
 * (also shared) pins what it accepts.
 */
internal object DebugBundleLink {
  /** The link's host and path: nothing else reaches the handler. */
  const val HOST = "keliver-debug"
  const val PATH = "/bundles"

  /** Longer links are refused before anything else is read. */
  const val MAX_LENGTH = 512

  /** The lowest port a link may name: no well-known (system) port. */
  const val MIN_PORT = 1024

  /**
   * The bundle server [link] names, as `http://<address>:<port>` (or https), or null unless every
   * one of these holds:
   * - [link] is printable ASCII of at most [MAX_LENGTH] characters, with no fragment;
   * - its scheme is [scheme] and its host [HOST] (either in any case), with no user or port, and
   *   its path is exactly [PATH];
   * - its query is exactly one `server=` parameter, percent-encoded at most once;
   * - that server is `http://` or `https://` (lower case), then an IPv4 literal, a bracketed IPv6
   *   literal or `localhost` (never a host name: a link cannot send the host to a name some DNS
   *   answers), then `:` and a port from [MIN_PORT] to 65535, then nothing or `/`: no user, query,
   *   fragment or other path;
   * - that address is loopback, private, link-local or shared (carrier-grade NAT, which VPNs such
   *   as Tailscale use): IPv4 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16 or 100.64/10, or
   *   IPv6 `::1`, fc00::/7 or fe80::/10. A link never sends the host to the internet.
   * What it returns has no trailing `/`, and an IPv6 literal in lower case.
   */
  fun server(link: String?, scheme: String): String? {
    if (link == null || link.isEmpty() || link.length > MAX_LENGTH) return null
    if (link.any { it <= ' ' || it >= '\u007f' } || '#' in link) return null
    val sep = link.indexOf("://")
    if (sep <= 0 || !link.substring(0, sep).equals(scheme, ignoreCase = true)) return null
    val rest = link.substring(sep + 3)
    if (rest.length < HOST.length || !rest.substring(0, HOST.length).equals(HOST, ignoreCase = true)) return null
    val afterHost = rest.substring(HOST.length)
    val prefix = "$PATH?server="
    if (!afterHost.startsWith(prefix)) return null
    val encoded = afterHost.substring(prefix.length)
    if (encoded.isEmpty() || encoded.any { !(it.isAsciiLetterOrDigit() || it in "%._~-:/[]") }) return null
    val value = percentDecode(encoded) ?: return null
    return bundleServer(value)
  }

  /** [value] if it is a bundle server as [server] states, without its trailing `/`, or null. */
  internal fun bundleServer(value: String): String? {
    val scheme = when {
      value.startsWith("http://") -> "http"
      value.startsWith("https://") -> "https"
      else -> return null
    }
    var rest = value.substring(scheme.length + 3)
    if (rest.endsWith("/")) rest = rest.dropLast(1)
    if ('/' in rest || '@' in rest || '?' in rest || '#' in rest || '%' in rest) return null
    val host: String
    val portText: String
    if (rest.startsWith("[")) {
      val close = rest.indexOf(']')
      if (close < 0) return null
      host = rest.substring(1, close).lowercase()
      if (!ipv6(host)) return null
      if (!rest.startsWith(":", close + 1)) return null
      portText = rest.substring(close + 2)
    } else {
      val colon = rest.indexOf(':')
      if (colon <= 0 || rest.indexOf(':', colon + 1) >= 0) return null
      host = rest.substring(0, colon)
      if (host != "localhost" && !ipv4(host)) return null
      portText = rest.substring(colon + 1)
    }
    if (portText.isEmpty() || portText.length > 5 || portText.any { it !in '0'..'9' } || portText.startsWith("0")) return null
    val port = portText.toInt()
    if (port !in MIN_PORT..65535) return null
    val shown = if (':' in host) "[$host]" else host
    return "$scheme://$shown:$port"
  }

  /**
   * A dotted-quad IPv4 literal (four decimal octets, no leading zeros) in 127/8, 10/8, 172.16/12,
   * 192.168/16, 169.254/16 or 100.64/10.
   */
  internal fun ipv4(s: String): Boolean {
    val parts = s.split('.')
    if (parts.size != 4) return false
    val o = parts.map { p ->
      if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' } || (p.length > 1 && p.startsWith("0"))) return false
      p.toInt().also { if (it > 255) return false }
    }
    return when (o[0]) {
      127, 10 -> true
      172 -> o[1] in 16..31
      192 -> o[1] == 168
      169 -> o[1] == 254
      100 -> o[1] in 64..127
      else -> false
    }
  }

  /**
   * An IPv6 literal without a zone or an embedded IPv4 part that is `::1` (loopback), in fc00::/7
   * (unique local) or in fe80::/10 (link-local).
   */
  internal fun ipv6(s: String): Boolean {
    if (s.isEmpty() || s.length > 39 || s.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':') }) return false
    val halves = s.split("::")
    if (halves.size > 2) return false
    fun groups(h: String): List<Int>? =
      if (h.isEmpty()) emptyList() else h.split(':').takeIf { g -> g.all { it.length in 1..4 } }?.map { it.toInt(16) }
    val head = groups(halves[0]) ?: return false
    val tail = if (halves.size == 2) groups(halves[1]) ?: return false else emptyList()
    val count = head.size + tail.size
    if (if (halves.size == 2) count > 7 else count != 8) return false
    val all = head + List(8 - count) { 0 } + tail
    if (all.subList(0, 7).all { it == 0 } && all[7] == 1) return true // ::1
    return (all[0] and 0xfe00) == 0xfc00 || (all[0] and 0xffc0) == 0xfe80
  }

  /** `%XX` sequences decoded (exactly two hex digits each), or null for a malformed one or a decoded `%`. */
  private fun percentDecode(s: String): String? {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c != '%') {
        out.append(c)
        i++
        continue
      }
      val hi = s.getOrNull(i + 1)?.digitToIntOrNull(16) ?: return null
      val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16) ?: return null
      val decoded = (hi * 16 + lo).toChar()
      if (decoded <= ' ' || decoded >= '\u007f' || decoded == '%') return null
      out.append(decoded)
      i += 3
    }
    return out.toString()
  }

  private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
