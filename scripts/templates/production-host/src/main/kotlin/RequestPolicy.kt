package @@PACKAGE@@

import okhttp3.HttpUrl

/**
 * What a guest's HostHttp requests may reach (adoption item 3, K1). Checked
 * before anything goes on the network, in Keliver code, so an app does not
 * write its own allow-list in a bridge:
 *
 * - **one origin**: the scheme, host and port of [origin], which must be the
 *   API base's (`keliver.apiBaseUrl`); otherwise every request is refused.
 *   It must be `https` unless [allowCleartext] (debug builds only: pass
 *   `BuildConfig.DEBUG`, never `true`; a release build's `fromBuild` config
 *   also refuses an http API base at build time);
 * - **exact `(method, path)` pairs**: [allowed], paths relative to the API
 *   base, compared segment by segment (`"orders/recent"`). No patterns: a path
 *   with an id in it is a different path;
 * - **state-changing calls** (any method but GET, HEAD and OPTIONS) only when
 *   [allowStateChanging] is set for this build, even if a pair allows them.
 *
 * Not restricted: the query string, and headers other than the ones
 * [OkHttpHostHttp] drops (the method-override headers among them). A guest can
 * set `Authorization` or `Cookie` unless your client's interceptor sets them.
 *
 * Without a policy ([KeliverConfig.requestPolicy] null) every path under the
 * API base is allowed, as before; an app embedding Keliver screens should set
 * one.
 */
class RequestPolicy(
  val origin: HttpUrl,
  val allowed: Set<Rule>,
  val allowStateChanging: Boolean = false,
  val allowCleartext: Boolean = false,
) {
  /** One allowed request: [method] (any case) and [path], relative to the API base. */
  class Rule(method: String, path: String) {
    val method: String = method.uppercase()
    val path: String = normalize(path)

    override fun equals(other: Any?) = other is Rule && other.method == method && other.path == path
    override fun hashCode() = 31 * method.hashCode() + path.hashCode()
    override fun toString() = "$method /$path"
  }

  /**
   * Why no request to the API base [base] may go (this policy names another origin, or cleartext
   * this build does not allow), or null. [OkHttpHostHttp] checks it once, when it is built.
   */
  internal fun baseRefusal(base: HttpUrl): String? = when {
    base.scheme != origin.scheme || base.host != origin.host || base.port != origin.port ->
      "the API base ${base.scheme}://${base.host}:${base.port} is not the policy's origin ${origin.scheme}://${origin.host}:${origin.port}"
    base.scheme != "https" && !allowCleartext -> "the API base is ${base.scheme}: only https is allowed in this build"
    else -> null
  }

  /** Why [method] to [path] (relative to the API base, as the guest sent it) is refused, or null. */
  internal fun refusal(method: String, path: String): String? {
    val m = method.uppercase()
    if (m !in READ_ONLY_METHODS && !allowStateChanging) return "$m is a state-changing call, and this build does not allow them"
    val rule = Rule(m, path)
    if (rule !in allowed) return "$rule is not in the allow-list"
    return null
  }

  companion object {
    private val READ_ONLY_METHODS = setOf("GET", "HEAD", "OPTIONS")

    /** "/a//b/" → "a/b": the form [Rule] paths and request paths are compared in. */
    internal fun normalize(path: String): String = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
  }
}
