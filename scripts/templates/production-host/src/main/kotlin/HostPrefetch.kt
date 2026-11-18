package @@PACKAGE@@

import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * NF4 (cold start): a request YOUR first screen makes as soon as it shows (its first page of data),
 * declared in `KeliverConfig.prefetch`. The host sends it when the process starts toward a screen
 * (the first activity's creation, KeliverHost.start()'s watch: never in a start for a push, a job
 * or a broadcast that shows nothing), while the bundle loads and the screen's first composition
 * runs, and the screen's identical request takes the response instead of waiting for the network.
 * The guest is not changed and cannot tell (no protocol change: any bundle, old or new, benefits
 * or is unaffected).
 *
 * The request is a GET under `keliver.apiBaseUrl`, sent exactly as a guest's would be
 * (OkHttpHostHttp: the same client, the RequestPolicy, the checks on the wire): a declared request
 * the policy refuses is refused, and sends nothing. The guest's request takes it only when it is
 * the SAME request: GET, the same path and query, no body, and no header of its own (after the
 * headers OkHttpHostHttp drops). It is taken once, and only within [PrefetchedRequests]'
 * `maxAgeMillis` of its answer ARRIVING; a prefetch that failed, answered anything but 2xx, or is
 * still in flight `maxWaitMillis` after the guest asked, is not used, and the guest's request goes
 * out as usual.
 *
 * Fresh data, and the right user's: any request other than a GET through the guests' HostHttp drops
 * every response not taken yet (the guest may have changed what it would show), and so does
 * `KeliverHost.discardPrefetched()`, which the app calls on sign-out or a change of user. The app's
 * credentials (its `httpClient`'s session) must be ready BEFORE `keliver.start()`: the prefetch goes
 * out with whatever they are then.
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
  fun matches(request: HttpRequest, keptHeaders: Map<String, String>): Boolean = mismatch(request, keptHeaders) == null

  /** Whether [request] is a GET of this one's path (a near match, when it is not the same request). */
  internal fun samePath(request: HttpRequest): Boolean =
    request.method.equals("GET", ignoreCase = true) && request.path.split('/').filter { it.isNotEmpty() } == segments

  /** Why [request] is not this one (names only: no header or query values, which can be secrets); null when it is. */
  internal fun mismatch(request: HttpRequest, keptHeaders: Map<String, String>): String? = when {
    !request.method.equals("GET", ignoreCase = true) -> "method ${request.method.uppercase()}"
    request.body != null -> "it has a body"
    request.path.split('/').filter { it.isNotEmpty() } != segments -> "another path"
    request.query != query -> "another query (keys ${request.query.keys.sorted()} against ${query.keys.sorted()}, or other values)"
    keptHeaders.isNotEmpty() -> "headers of its own: ${keptHeaders.keys.map { it.lowercase() }.sorted()}"
    else -> null
  }

  override fun toString(): String = "GET /${segments.joinToString("/")}${if (query.isEmpty()) "" else "?…"}"
}

/**
 * The declared requests' responses (see [KeliverPrefetch]). They are sent once both [arm] (the
 * HostHttp, from KeliverHost.run()) and [uiStarting] (the first activity's creation) have been
 * called, in either order: a process that never shows a screen sends nothing. [responseFor] hands
 * a guest's matching request its response, once; [discard] drops what was not taken. Thread-safe.
 */
class PrefetchedRequests(
  declared: List<KeliverPrefetch>,
  /** How long a response is kept after it ARRIVED (not after it was sent). */
  private val maxAgeMillis: Long = DEFAULT_PREFETCH_MAX_AGE_MILLIS,
  /** How long a guest's request waits for a prefetch still in flight before it goes out itself. */
  private val maxWaitMillis: Long = DEFAULT_PREFETCH_MAX_WAIT_MILLIS,
  /** Monotonic milliseconds (SystemClock.elapsedRealtime on a device). */
  private val now: () -> Long = { System.nanoTime() / 1_000_000 },
  /** What went unused, and why (KeliverHost passes Log.d in debug builds only); never header or query values. */
  private val log: (String) -> Unit = {},
) {
  private val declared = declared.toList()
  private class Sent(val prefetch: KeliverPrefetch) {
    lateinit var response: Deferred<HttpResponse>
    /** When the answer (or the failure) arrived; -1 while in flight. */
    @Volatile var arrivedAt: Long = -1
  }
  private val sent = ArrayList<Sent>()
  private var sender: Pair<CoroutineScope, suspend (HttpRequest) -> HttpResponse>? = null
  private var uiStarted = false
  private var started = false

  init {
    require(maxAgeMillis > 0) { "PrefetchedRequests: maxAgeMillis $maxAgeMillis is not a positive duration" }
    require(maxWaitMillis > 0) { "PrefetchedRequests: maxWaitMillis $maxWaitMillis is not a positive duration" }
  }

  /** How the requests go out: [send] in [scope]. Nothing is sent before [uiStarting]. */
  @Synchronized
  fun arm(scope: CoroutineScope, send: suspend (HttpRequest) -> HttpResponse) {
    if (sender == null) sender = scope to send
    sendIfReady()
  }

  /** The process is starting toward a screen (its first activity is being created). */
  @Synchronized
  fun uiStarting() {
    uiStarted = true
    sendIfReady()
  }

  /** Sends every declared request, once for this object's life, when both [arm] and [uiStarting] came. */
  private fun sendIfReady() {
    val (scope, send) = sender ?: return
    if (!uiStarted || started) return
    started = true
    for (p in declared) {
      val s = Sent(p)
      s.response = scope.async {
        try {
          send(p.toRequest())
        } finally {
          s.arrivedAt = now()
        }
      }
      // Dropped (and logged) when it expires, whether or not a request ever asks for it.
      scope.launch {
        s.response.join()
        delay(maxAgeMillis + 1)
        sweep()
      }
      sent += s
    }
  }

  /** Drops every response not taken yet (any non-GET through HostHttp; KeliverHost.discardPrefetched()). */
  @Synchronized
  fun discard(why: String) {
    for (s in sent) {
      log("${s.prefetch} dropped untaken: $why")
      s.response.cancel()
    }
    sent.clear()
  }

  @Synchronized
  private fun sweep() = expire(now())

  private fun expire(at: Long) {
    sent.removeAll { s ->
      val old = s.arrivedAt >= 0 && at - s.arrivedAt > maxAgeMillis
      if (old) log("${s.prefetch} expired untaken (its answer arrived ${at - s.arrivedAt} ms ago, over $maxAgeMillis)")
      old
    }
  }

  /**
   * The response for [request] if it is a declared one whose answer arrived less than
   * maxAgeMillis ago (or is still in flight), not taken yet; it is taken now. Null otherwise (the
   * caller sends the request itself).
   */
  @Synchronized
  fun take(request: HttpRequest, keptHeaders: Map<String, String>): Deferred<HttpResponse>? {
    // Too old to hand out: dropped, so a later identical request goes to the network.
    expire(now())
    val i = sent.indexOfFirst { it.prefetch.matches(request, keptHeaders) }
    if (i < 0) {
      sent.firstOrNull { it.prefetch.samePath(request) }?.let { near ->
        log("${near.prefetch} not taken by a GET of the same path: ${near.prefetch.mismatch(request, keptHeaders)}")
      }
      return null
    }
    return sent.removeAt(i).response
  }

  /**
   * The prefetched response for [request] when it is usable: taken ([take]), arrived within
   * maxWaitMillis, and 2xx. Null otherwise: the caller sends the request itself. The caller's own
   * cancellation is not swallowed.
   */
  suspend fun responseFor(request: HttpRequest, keptHeaders: Map<String, String>): HttpResponse? {
    val pending = take(request, keptHeaders) ?: return null
    val what = "the prefetch of GET /${request.path.trimStart('/')}"
    val response = try {
      withTimeoutOrNull(maxWaitMillis) { pending.await() }
    } catch (e: Exception) {
      currentCoroutineContext().ensureActive()
      log("$what failed (${e::class.java.simpleName}): the request goes out itself")
      return null
    }
    if (response == null) {
      pending.cancel()
      log("$what still in flight after $maxWaitMillis ms: the request goes out itself")
      return null
    }
    if (response.status !in 200..299) {
      log("$what answered ${response.status}: the request goes out itself")
      return null
    }
    return response
  }

  /** Tests: waits until every sent request has answered or failed. */
  internal suspend fun settled() {
    val all = synchronized(this) { sent.map { it.response } }
    all.forEach { it.join() }
  }

  companion object {
    /**
     * 5 s after the answer arrives. The response must outlive the gap between its arrival and the
     * guest's request: on the A12 (NF0, THREE_WAY_BENCHMARK.md) the guest asks about 1.4 s after the
     * process start with a cached bundle and about 1.8 s on a first launch, and the answer is there
     * a round trip after the first activity's creation (about 0.4 s), so 1–1.5 s; 5 s is three
     * times that. A start that downloads its code first can take longer: the guest's request then
     * goes out itself, as without a prefetch. Short, so the answer is still the start's own: a
     * change made through HostHttp drops it anyway (any non-GET), and the app drops it on sign-out.
     */
    const val DEFAULT_PREFETCH_MAX_AGE_MILLIS = 5_000L

    /**
     * 1 s. A prefetch sent at the activity's creation has normally answered before the guest asks
     * (on the A12, about 1 s later); one still in flight then a second later is likely stalled (a
     * network switch), and waiting on it to OkHttp's read timeout (10 s or more) would make the
     * first screen slower than no prefetch at all. The worst case is now 1 s over not having one.
     */
    const val DEFAULT_PREFETCH_MAX_WAIT_MILLIS = 1_000L
  }
}
