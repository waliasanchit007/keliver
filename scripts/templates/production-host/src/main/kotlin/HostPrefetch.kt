package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * NF4 (cold start): a request YOUR first screen makes as soon as it shows (its first page of data),
 * declared in `KeliverConfig.prefetch`. The host sends it at process start (`KeliverHost.start()`,
 * from the Application), while the bundle loads and the screen's first composition runs, and the
 * screen's identical request takes the response instead of waiting for the network. The guest is
 * not changed and cannot tell (no protocol change: any bundle, old or new, benefits or is
 * unaffected).
 *
 * The request is a GET under `keliver.apiBaseUrl`, sent exactly as a guest's would be
 * (OkHttpHostHttp: the same client, the RequestPolicy, the checks on the wire): a declared request
 * the policy refuses is refused, and sends nothing. The guest's request takes it only when it is
 * the SAME request: GET, the same path and query, no body, and no header of its own (after the
 * headers OkHttpHostHttp drops). It is taken once, and only within [PrefetchedRequests]'
 * `maxAgeMillis` of being sent; a prefetch that failed, or answered anything but 2xx, is not used,
 * and the guest's request goes out as usual. Declare only a request whose answer does not change
 * in the app's first seconds (not one that depends on a sign-in the app has not done yet).
 */
class KeliverPrefetch(path: String, query: Map<String, String> = emptyMap()) {
  /** The path's segments: `/items` and `items` are the same request, as HostHttp sends them. */
  val segments: List<String> = path.split('/').filter { it.isNotEmpty() }
  val query: Map<String, String> = query.toMap()

  init {
    require(segments.isNotEmpty()) { "KeliverPrefetch: '$path' names no path under the API base" }
    require(segments.none { it == "." || it == ".." || '\\' in it || '?' in it || '#' in it }) {
      "KeliverPrefetch: '$path' is not a plain relative path (put the query in `query`)"
    }
  }

  fun toRequest(): HttpRequest = HttpRequest(method = "GET", path = segments.joinToString("/"), query = query)

  /** Whether [request] (the guest's), with [keptHeaders] (what OkHttpHostHttp would send of its headers), is this one. */
  fun matches(request: HttpRequest, keptHeaders: Map<String, String>): Boolean =
    request.method.equals("GET", ignoreCase = true) && request.body == null && keptHeaders.isEmpty() &&
      request.path.split('/').filter { it.isNotEmpty() } == segments && request.query == query

  override fun toString(): String = "GET /${segments.joinToString("/")}${if (query.isEmpty()) "" else "?…"}"
}

/**
 * The declared requests' responses (see [KeliverPrefetch]): [start] sends each once, [take] hands a
 * guest's matching request its response, once. Thread-safe.
 */
class PrefetchedRequests(
  declared: List<KeliverPrefetch>,
  private val maxAgeMillis: Long = DEFAULT_PREFETCH_MAX_AGE_MILLIS,
  /** Monotonic milliseconds (SystemClock.elapsedRealtime on a device). */
  private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  private val declared = declared.toList()
  private class Sent(val prefetch: KeliverPrefetch, val at: Long, val response: Deferred<HttpResponse>)
  private val sent = ArrayList<Sent>()
  private var started = false

  init {
    require(maxAgeMillis > 0) { "PrefetchedRequests: maxAgeMillis $maxAgeMillis is not a positive duration" }
  }

  /** Sends every declared request through [send] in [scope], once for this object's life. */
  @Synchronized
  fun start(scope: CoroutineScope, send: suspend (HttpRequest) -> HttpResponse) {
    if (started) return
    started = true
    for (p in declared) {
      sent += Sent(p, now(), scope.async { send(p.toRequest()) })
    }
  }

  /**
   * The response for [request] if it is a declared one, sent less than maxAgeMillis ago, and not
   * taken yet; it is taken now. Null otherwise (the caller sends the request itself).
   */
  @Synchronized
  fun take(request: HttpRequest, keptHeaders: Map<String, String>): Deferred<HttpResponse>? {
    val at = now()
    // Too old to hand out: dropped, so a later identical request goes to the network.
    sent.removeAll { at - it.at > maxAgeMillis }
    val i = sent.indexOfFirst { it.prefetch.matches(request, keptHeaders) }
    if (i < 0) return null
    return sent.removeAt(i).response
  }

  companion object {
    /** Long enough for a slow first start's code load and first composition; short enough to stay the start's own data. */
    const val DEFAULT_PREFETCH_MAX_AGE_MILLIS = 30_000L
  }
}

/**
 * [pending]'s response when it answered 2xx; null when it failed or answered anything else (the
 * caller then sends the request itself). The caller's own cancellation is not swallowed.
 */
internal suspend fun usablePrefetch(pending: Deferred<HttpResponse>): HttpResponse? {
  val response = try {
    pending.await()
  } catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    null
  }
  return response?.takeIf { it.status in 200..299 }
}
