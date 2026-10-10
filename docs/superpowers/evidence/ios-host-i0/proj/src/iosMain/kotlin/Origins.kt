package dev.keliver.measure.host

/** scheme://host[:port] of an http(s) URL, with the default port filled in. */
internal data class Origin(val scheme: String, val host: String, val port: Int)

private val URL_RE = Regex("""^(https?)://(\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+)(?::(\d{1,5}))?(/[^?#]*)?$""")

/** The bundle server: an http(s) URL with no query or fragment, as the build validated. */
internal class BundleServer private constructor(val base: String, val origin: Origin) {
  companion object {
    fun parse(url: String): BundleServer? = originOf(url)?.let { BundleServer(url.trimEnd('/'), it) }
  }

  /** [path] resolved against this server, or null if it lands on another origin. */
  fun resolveSameOrigin(path: String): String? {
    val url = when {
      path.startsWith("http://") || path.startsWith("https://") -> path
      path.startsWith("//") -> return null
      path.startsWith("/") -> "${origin.scheme}://${origin.host}:${origin.port}$path"
      else -> "$base/$path"
    }
    return url.takeIf { originOf(it) == origin }
  }

  fun owns(url: String?): Boolean = url != null && originOf(url) == origin
}

internal fun originOf(url: String): Origin? {
  val m = URL_RE.find(url.substringBefore('?').substringBefore('#')) ?: return null
  val scheme = m.groupValues[1].lowercase()
  val port = m.groupValues[3].toIntOrNull() ?: if (scheme == "https") 443 else 80
  return Origin(scheme, m.groupValues[2].lowercase(), port)
}

/** RFC 3986 percent-encoding of everything but the unreserved characters. */
internal fun percentEncode(s: String): String = buildString {
  for (b in s.encodeToByteArray()) {
    val c = b.toInt() and 0xff
    val ch = c.toChar()
    if (ch.isLetterOrDigit() && c < 0x80 || ch in "-._~") append(ch)
    else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
  }
}
