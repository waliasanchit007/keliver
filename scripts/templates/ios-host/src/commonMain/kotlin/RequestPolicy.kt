package @@PACKAGE@@

/**
 * What a guest's HostHttp requests may reach (adoption item 3, K2; the same
 * rules as the Android host's). Checked before anything goes on the network,
 * in Keliver code, so an app does not write its own allow-list in a bridge:
 *
 * - **one origin**: the scheme, host and port of [origin] (`https://api.example.com`),
 *   which must be the API base's (`API_BASE_URL`); otherwise every request is
 *   refused. It must be `https` unless [allowCleartext] (a DEBUG binary only:
 *   never `true` in a release; a release build also refuses an http API base);
 * - **exact `(method, path)` pairs**: [allowed], paths relative to the API
 *   base, compared segment by segment (`"orders/recent"`). No patterns: a path
 *   with an id in it is a different path;
 * - **state-changing calls** (any method but GET, HEAD and OPTIONS) only when
 *   [allowStateChanging] is set for this build, even if a pair allows them.
 *
 * Not restricted: the query string, and headers other than the ones the host
 * drops (the method-override and path-override headers among them). With your
 * credentials ([Keliver.requestHeaders], B2) a guest's `Authorization`,
 * `Proxy-Authorization` and `Cookie` are dropped too; without them a guest
 * can set those.
 *
 * Set it with `Keliver.shared.requestPolicy` before the host starts. Without
 * one, every path under the API base is allowed, as before; an app embedding
 * Keliver screens should set one. Pure Kotlin: its tests run on the JVM
 * (`./gradlew -p host-ios jvmTest`).
 */
public class RequestPolicy(
  public val origin: String,
  allowed: Set<Rule>,
  public val allowStateChanging: Boolean,
  public val allowCleartext: Boolean,
) {
  public val allowed: Set<Rule> = allowed.toSet()

  /** One allowed request: [method] (any case) and [path], relative to the API base. */
  public class Rule(method: String, path: String) {
    public val method: String = method.uppercase()
    public val path: String = normalize(path)

    override fun equals(other: Any?): Boolean = other is Rule && other.method == method && other.path == path
    override fun hashCode(): Int = 31 * method.hashCode() + path.hashCode()
    override fun toString(): String = "$method /$path"
  }

  /** Why no request to the API base [base] may go (another origin, or cleartext this build does not allow), or null. */
  internal fun baseRefusal(base: String): String? {
    val b = originOf(base) ?: return "the API base '$base' is not an http(s) URL with a host"
    val o = originOf(origin) ?: return "the policy's origin '$origin' is not an http(s) URL with a host"
    if (b != o) return "the API base $b is not the policy's origin $o"
    if (b.scheme != "https" && !allowCleartext) return "the API base is ${b.scheme}: only https is allowed in this build"
    return null
  }

  /** Why [method] to [path] (relative to the API base, as the guest sent it) is refused, or null. */
  internal fun refusal(method: String, path: String): String? {
    val m = method.uppercase()
    if (m !in READ_ONLY_METHODS && !allowStateChanging) return "$m is a state-changing call, and this build does not allow them"
    val rule = Rule(m, path)
    if (rule !in allowed) return "$rule is not in the allow-list"
    return null
  }

  /** A URL's scheme, host and port: what "the same origin" compares. */
  internal data class Origin(val scheme: String, val host: String, val port: Int) {
    override fun toString(): String = "$scheme://$host:$port"
  }

  internal companion object {
    private val READ_ONLY_METHODS = setOf("GET", "HEAD", "OPTIONS")

    /** "/a//b/" → "a/b": the form [Rule] paths and request paths are compared in. */
    fun normalize(path: String): String = path.split('/').filter { it.isNotEmpty() }.joinToString("/")

    /** `https://Host:443/x` → (https, host, 443); null when not http(s), without a host, or with user info. */
    fun originOf(url: String): Origin? {
      val scheme = url.substringBefore("://", "").lowercase()
      if (scheme != "http" && scheme != "https") return null
      val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
      if (authority.isEmpty() || '@' in authority) return null
      val host: String
      val port: String?
      if (authority.startsWith("[")) { // an IPv6 literal
        val end = authority.indexOf(']')
        if (end < 0) return null
        host = authority.substring(0, end + 1)
        port = authority.substring(end + 1).removePrefix(":").ifEmpty { null }
      } else {
        host = authority.substringBefore(':')
        port = if (':' in authority) authority.substringAfter(':') else null
      }
      if (host.isEmpty() || host == "[]") return null
      val p = if (port == null) (if (scheme == "https") 443 else 80) else port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
      return Origin(scheme, host.lowercase(), p)
    }
  }
}
