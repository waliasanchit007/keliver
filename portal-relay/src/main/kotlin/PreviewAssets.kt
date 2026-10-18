/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * K5: `GET /preview-asset?url=<encoded>` — the editor canvas's pass-through
 * for images on the app's own hosts.
 *
 * The canvas runs in a browser page on localhost and loads `AsyncImage` URLs
 * with `fetch()`. An image host that sends no CORS headers (most do not) cannot
 * be read from that page, so the canvas showed an empty image where the phone
 * shows the picture. The relay fetches it instead, for the hosts the app
 * lists in `keliver.portal.json` `previewAssetHosts` and no others.
 *
 * Rules, all enforced here:
 *  - off (404) unless at least one host is listed;
 *  - the caller's address must be loopback. That keeps other machines on the
 *    LAN off it (the relay's socket is not loopback-only); it does NOT stop a
 *    web page in the developer's own browser, which also connects from
 *    loopback — the relay's Host/Origin gate is what refuses those;
 *  - GET only; `http`/`https` URLs without user-info, on the scheme's default
 *    port only; the host must equal a listed host exactly (no wildcards, no
 *    subdomains). Listed names are validated by [PortalConfig] (no IP
 *    literals, no `localhost`); what a listed name resolves to in DNS is
 *    trusted, not checked;
 *  - nothing from the caller is forwarded — no cookies, no Authorization, no
 *    other header; the upstream request carries a fixed User-Agent and Accept;
 *  - redirects are followed by hand, at most [MAX_REDIRECTS], only to listed
 *    hosts on default ports, and never from https down to http;
 *  - one deadline ([deadlineMillis]) covers the whole request, every redirect
 *    hop included;
 *  - only a 200 with exactly one Content-Type naming one of [RASTER_TYPES]
 *    (parameters ignored; a list is refused) is served, under that canonical
 *    type — never the upstream's string. SVG is not raster: served from the
 *    relay's own origin, an SVG opened directly would be an active document
 *    able to call the relay's write endpoints; the canvas has no SVG decoder
 *    either;
 *  - at most [maxBytes] per image;
 *  - successful images are kept in an in-memory LRU cache bounded by entries
 *    and bytes, for [ttlMillis]; a failure is remembered for
 *    [failureTtlMillis] per URL, so a re-render does not ask again. The
 *    host (with its port) is remembered as well only when no connection to
 *    it could be made: DNS failed, the connection was refused or had no
 *    route, the connect itself timed out, or the TLS handshake failed. A
 *    host that accepted the connection is never marked, however slowly it
 *    answered: the deadline running out, a body that broke off or a
 *    protocol error costs that URL only, so one slow image does not fail
 *    every other image on its host. A redirect target the deadline left no
 *    time to contact is not marked either;
 *  - requests run on this endpoint's own small pool ([threads] threads,
 *    [queue] waiting), never on the relay's shared dispatcher: a slow host
 *    cannot stall the relay's handling of `/ops`, `/doc` or write-back. (The
 *    browser's few connections to the relay are the editor's to share: it
 *    runs at most two image fetches through here at once.) A full pool
 *    answers 503;
 *  - the response carries `nosniff` and a sandbox CSP; CORS headers are the
 *    relay's access gate's (RelayAccess: a loopback origin is echoed, any
 *    other is refused before this runs);
 *  - refusals are logged with the host and reason, never the URL's path or query.
 *
 * Preview only: devices load images themselves and never use this.
 *
 * [extraPorts] exists for tests whose stub upstream listens on an ephemeral
 * port; the relay never passes it.
 */
class PreviewAssets(
  hosts: Collection<String>,
  private val client: OkHttpClient = defaultClient(),
  private val maxBytes: Int = 10 * 1024 * 1024,
  private val cacheMaxEntries: Int = 256,
  private val cacheMaxBytes: Long = 64L * 1024 * 1024,
  private val ttlMillis: Long = 10 * 60 * 1000L,
  private val deadlineMillis: Long = 10_000L,
  private val failureTtlMillis: Long = 30_000L,
  private val threads: Int = 4,
  private val queue: Int = 16,
  private val extraPorts: Set<Int> = emptySet(),
  private val clock: () -> Long = System::currentTimeMillis,
  private val log: (String) -> Unit = ::println,
) {
  val hosts: Set<String> = hosts.map { it.lowercase() }.toSortedSet()
  val enabled: Boolean get() = hosts.isNotEmpty()

  sealed interface Result {
    data class Image(val contentType: String, val bytes: ByteArray) : Result
    data class Refused(val status: Int, val reason: String) : Result
  }

  private class Entry(val image: Result.Image, val at: Long)
  private class Failure(val refused: Result.Refused, val at: Long)

  private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {}
  private var cachedBytes = 0L

  // Remembered failures, keyed by URL and (hosts no connection reached) by host:port.
  private val failedUrls = boundedMap<Failure>(FAILURES_MAX)
  private val failedHosts = boundedMap<Failure>(FAILURES_MAX)

  // Each call reports whether it got a connection: a [Reach] rides as the request's tag.
  private val tracked: OkHttpClient = client.newBuilder()
    .eventListenerFactory { call -> call.request().tag(Reach::class.java) ?: EventListener.NONE }
    .build()

  /** Whether a call got a connection (new or pooled) to its host. */
  private class Reach : EventListener() {
    @Volatile var connected = false
    override fun connectionAcquired(call: Call, connection: Connection) {
      connected = true
    }
  }

  private val threadIds = AtomicInteger()
  private val pool = ThreadPoolExecutor(
    threads, threads, 30, TimeUnit.SECONDS,
    ArrayBlockingQueue(queue),
    { r -> Thread(r, "preview-asset-${threadIds.incrementAndGet()}").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
  ).apply { allowCoreThreadTimeOut(true) }

  /** JSON for `GET /preview-assets`: what the editor rewrites, and where to. */
  fun describeJson(): String =
    "{\"endpoint\":\"/preview-asset\",\"hosts\":[" +
      hosts.joinToString(",") { kotlinx.serialization.json.JsonPrimitive(it).toString() } + "]}"

  /** Fetch [rawUrl] (already decoded) for the canvas. Blocks for up to [deadlineMillis]. */
  fun fetch(rawUrl: String?): Result {
    if (!enabled) return Result.Refused(404, "disabled")
    if (rawUrl.isNullOrBlank()) return Result.Refused(400, "missing url")
    val start = when (val p = parseAllowed(rawUrl)) {
      is Parsed.Allowed -> p.uri
      is Parsed.Refused -> return refuse(rawUrl, p.status, p.reason)
    }
    val key = start.toString()
    cached(key)?.let { return it }
    recentFailure(key, hostKey(start))?.let { return it }
    return download(start, key)
  }

  private fun download(start: URI, key: String): Result {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis)
    var uri = start
    repeat(MAX_REDIRECTS + 1) {
      val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
      // Out of time before this hop: its host was never contacted, so only the URL is remembered.
      if (remaining <= 0) return failed(key, uri, unreachable = false, 504, "no image within $deadlineMillis ms")
      val reach = Reach()
      val request = try {
        Request.Builder()
          .url(uri.toString())
          .get()
          .header("User-Agent", USER_AGENT)
          .header("Accept", "image/*")
          .tag(Reach::class.java, reach)
          .build()
      } catch (e: IllegalArgumentException) {
        return failed(key, uri, unreachable = false, 400, "not a valid http(s) URL")
      }
      val call = tracked.newCall(request)
      // One deadline for the whole request: this hop gets what is left of it.
      call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
      try {
        call.execute().use { response ->
          if (response.code in REDIRECTS) {
            val location = response.header("Location")
              ?: return failed(key, uri, unreachable = false, 502, "redirect without a location")
            val target = runCatching { uri.resolve(location).toString() }.getOrNull()
              ?: return failed(key, uri, unreachable = false, 403, "redirect to an invalid URL")
            val next = when (val p = parseAllowed(target)) {
              is Parsed.Allowed -> p.uri
              is Parsed.Refused -> return failed(key, uri, unreachable = false, 403, "redirect refused: ${p.reason}")
            }
            if (uri.scheme.equals("https", ignoreCase = true) && next.scheme.equals("http", ignoreCase = true)) {
              return failed(key, uri, unreachable = false, 403, "redirect from https down to http")
            }
            uri = next
            return@repeat
          }
          if (response.code != 200) return failed(key, uri, unreachable = false, 502, "upstream status ${response.code}")
          val declaredTypes = response.headers("Content-Type")
          val type = rasterType(declaredTypes)
            ?: return failed(key, uri, unreachable = false, 415, "not a raster image (${describeTypes(declaredTypes)})")
          val declared = response.body.contentLength()
          if (declared > maxBytes) return failed(key, uri, unreachable = false, 413, "larger than $maxBytes bytes")
          val bytes = response.body.byteStream().use { it.readNBytes(maxBytes + 1) }
          if (bytes.size > maxBytes) return failed(key, uri, unreachable = false, 413, "larger than $maxBytes bytes")
          val image = Result.Image(type, bytes)
          remember(key, image)
          return image
        }
      } catch (e: IOException) {
        // The deadline (OkHttp's call timeout cancels the call): this URL only,
        // whether it ran out while connecting, waiting or reading the body.
        if (call.isCanceled() || deadline - System.nanoTime() <= 0) {
          return failed(key, uri, unreachable = false, 504, "no image within $deadlineMillis ms")
        }
        // OkHttp tries a host's addresses in parallel and throws the LAST
        // failure; the others ride as suppressed. Name them all: an IPv6
        // "no route" can hide the TLS failure that actually mattered.
        val causes = (listOf(e) + e.suppressed).map { it.javaClass.simpleName }.distinct()
        // The host is marked only if no connection to it was made and the
        // failure is one of connecting; after a connection, it is this URL's.
        val unreachable = !reach.connected && isConnectFailure(e)
        return failed(key, uri, unreachable, 504, "upstream failed (${causes.joinToString("; ")})")
      }
    }
    return failed(key, uri, unreachable = false, 502, "more than $MAX_REDIRECTS redirects")
  }

  private sealed interface Parsed {
    class Allowed(val uri: URI) : Parsed
    class Refused(val status: Int, val reason: String) : Parsed
  }

  /** A URL this proxy may request: http(s), no user-info, a listed host, the default port. */
  private fun parseAllowed(raw: String): Parsed {
    val notAllowed = Parsed.Refused(403, "not an allowed http(s) host")
    val uri = runCatching { URI(raw) }.getOrNull() ?: return notAllowed
    val scheme = uri.scheme?.lowercase() ?: return notAllowed
    if (scheme != "http" && scheme != "https") return notAllowed
    if (uri.rawUserInfo != null) return notAllowed
    val host = uri.host?.lowercase() ?: return notAllowed
    if (host !in hosts) return notAllowed
    val port = uri.port
    if (port == -1) return Parsed.Allowed(uri)
    if (port !in 1..65535) return Parsed.Refused(400, "invalid port")
    val default = if (scheme == "https") 443 else 80
    if (port != default && port !in extraPorts) return Parsed.Refused(403, "only the default $scheme port is allowed")
    return Parsed.Allowed(uri)
  }

  @Synchronized private fun cached(key: String): Result.Image? {
    val entry = cache[key] ?: return null
    if (clock() - entry.at > ttlMillis) {
      cache.remove(key)
      cachedBytes -= entry.image.bytes.size
      return null
    }
    return entry.image
  }

  @Synchronized private fun remember(key: String, image: Result.Image) {
    if (image.bytes.size > cacheMaxBytes) return
    cache.remove(key)?.let { cachedBytes -= it.image.bytes.size }
    cache[key] = Entry(image, clock())
    cachedBytes += image.bytes.size
    val it = cache.entries.iterator()
    while ((cache.size > cacheMaxEntries || cachedBytes > cacheMaxBytes) && it.hasNext()) {
      val eldest = it.next()
      cachedBytes -= eldest.value.image.bytes.size
      it.remove()
    }
  }

  /** A failure remembered for [key] or its [host], still within [failureTtlMillis]. Not logged again. */
  @Synchronized private fun recentFailure(key: String, host: String): Result.Refused? {
    val now = clock()
    for ((map, k) in listOf(failedUrls to key, failedHosts to host)) {
      val f = map[k] ?: continue
      if (now - f.at > failureTtlMillis) {
        map.remove(k)
        continue
      }
      return Result.Refused(f.refused.status, "${f.refused.reason} (failed less than ${failureTtlMillis / 1000} s ago)")
    }
    return null
  }

  /**
   * Log and remember a failed download for [key]; [unreachable] (no
   * connection to [at]'s host could be made) also marks that host and port.
   */
  private fun failed(key: String, at: URI, unreachable: Boolean, status: Int, reason: String): Result.Refused {
    val refused = refuse(at, status, reason)
    synchronized(this) {
      val f = Failure(refused, clock())
      failedUrls[key] = f
      if (unreachable) failedHosts[hostKey(at)] = f
    }
    return refused
  }

  /** `host:port`, the port defaulted from the scheme: one host's http and https are told apart. */
  private fun hostKey(uri: URI): String {
    val port = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
    return "${uri.host?.lowercase()}:$port"
  }

  /** Number of cached images (tests). */
  @Synchronized fun cacheSize(): Int = cache.size

  /** Requests running or waiting on the pool (tests). */
  fun pending(): Int = pool.activeCount + pool.queue.size

  /**
   * Stop taking requests (tests; the relay keeps the pool for its lifetime).
   * Requests already running or queued still finish and close their exchanges.
   */
  fun close() {
    pool.shutdown()
  }

  private fun refuse(url: Any, status: Int, reason: String): Result.Refused {
    val host = runCatching { URI(url.toString()).host }.getOrNull() ?: "?"
    log("portal-server: preview-asset refused for host $host: $reason")
    return Result.Refused(status, reason)
  }

  /**
   * Mount `/preview-asset` on [server]: the relay's wiring, which the tests
   * use too. GET only, checked here on the dispatcher thread before the pool
   * (and again in [respond]); a refused method's exchange is closed even if
   * sending its 405 fails.
   */
  fun install(server: HttpServer) {
    server.createContext("/preview-asset") { ex ->
      if (ex.requestMethod != "GET") {
        try {
          ex.responseHeaders.add("Allow", "GET")
          ex.sendResponseHeaders(405, -1)
        } catch (_: IOException) {
        } finally {
          ex.close()
        }
      } else {
        serve(ex)
      }
    }
  }

  /**
   * Serve one exchange. Called on the relay's dispatcher thread: hands the
   * exchange to this endpoint's own pool and returns at once, so a slow
   * upstream never holds the thread every other endpoint is served on. A full
   * pool is answered 503 here. The exchange is closed on every path.
   */
  fun serve(ex: HttpExchange) {
    try {
      pool.execute { respond(ex) }
    } catch (e: RejectedExecutionException) {
      log("portal-server: preview-asset busy (${pending()} requests in flight): 503")
      try {
        ex.responseHeaders.add("Retry-After", "1")
        ex.sendResponseHeaders(503, -1)
      } catch (_: IOException) {
      } finally {
        ex.close()
      }
    } catch (e: Throwable) {
      ex.close()
      throw e
    }
  }

  /** The whole HTTP contract of `/preview-asset`, on a pool thread. */
  private fun respond(ex: HttpExchange) {
    try {
      if (!enabled || ex.remoteAddress.address?.isLoopbackAddress != true) {
        ex.sendResponseHeaders(404, -1)
        return
      }
      if (ex.requestMethod != "GET") {
        ex.responseHeaders.add("Allow", "GET")
        ex.sendResponseHeaders(405, -1)
        return
      }
      ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
      ex.responseHeaders.add("Content-Security-Policy", "default-src 'none'; sandbox")
      val url = urlParam(ex.requestURI.rawQuery)
      when (val r = fetch(url)) {
        is Result.Image -> {
          ex.responseHeaders.add("Content-Type", r.contentType)
          ex.responseHeaders.add("Cache-Control", "private, max-age=300")
          ex.sendResponseHeaders(200, r.bytes.size.toLong())
          ex.responseBody.use { it.write(r.bytes) }
        }
        is Result.Refused -> {
          val body = "{\"error\":${kotlinx.serialization.json.JsonPrimitive(r.reason)}}".encodeToByteArray()
          ex.responseHeaders.add("Content-Type", "application/json")
          ex.sendResponseHeaders(r.status, body.size.toLong())
          ex.responseBody.use { it.write(body) }
        }
      }
    } catch (e: Exception) {
      log("portal-server: preview-asset failed: ${e.javaClass.simpleName}")
      runCatching { ex.sendResponseHeaders(500, -1) }
    } finally {
      ex.close()
    }
  }

  companion object {
    const val MAX_REDIRECTS: Int = 5
    const val USER_AGENT: String = "keliver-portal-preview/1"
    private const val FAILURES_MAX = 512
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** The image types served, each exactly as named here (the canonical type sent back). */
    val RASTER_TYPES: Set<String> = linkedSetOf(
      "image/png",
      "image/jpeg",
      "image/gif",
      "image/webp",
      "image/avif",
      "image/bmp",
      "image/x-icon",
      "image/vnd.microsoft.icon",
    )

    /**
     * The canonical raster type for an upstream's Content-Type header values,
     * or null: exactly one value, not a list, whose media type (parameters
     * dropped, case ignored) is one of [RASTER_TYPES].
     */
    fun rasterType(headerValues: List<String>): String? {
      val value = headerValues.singleOrNull() ?: return null
      if (',' in value) return null
      val base = value.substringBefore(';').trim().lowercase()
      return RASTER_TYPES.firstOrNull { it == base }
    }

    private fun describeTypes(values: List<String>): String = when {
      values.isEmpty() -> "no content type"
      else -> values.joinToString(" | ").take(80)
    }

    /**
     * A failure to reach the host at all: DNS, a refused or unroutable
     * connect, a connect timeout, a failed TLS handshake — anywhere in the
     * exception, its causes or (OkHttp's parallel attempts) its suppressed.
     * Called only when the call got no connection, so a [SocketTimeoutException]
     * here is the connect's own timeout, not a read; the call deadline is
     * handled before this.
     */
    internal fun isConnectFailure(e: Throwable): Boolean {
      val seen = HashSet<Throwable>()
      fun walk(t: Throwable?): Boolean {
        if (t == null || !seen.add(t)) return false
        if (t is UnknownHostException || t is ConnectException || t is NoRouteToHostException ||
          t is SocketTimeoutException || t is SSLHandshakeException
        ) {
          return true
        }
        return walk(t.cause) || t.suppressed.any { walk(it) }
      }
      return walk(e)
    }

    private fun <V> boundedMap(max: Int): MutableMap<String, V> = object : LinkedHashMap<String, V>(16, 0.75f, false) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > max
    }

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .cookieJar(CookieJar.NO_COOKIES)
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .build()

    fun urlParam(rawQuery: String?): String? =
      (rawQuery ?: "").split('&').firstOrNull { it.startsWith("url=") }
        ?.removePrefix("url=")
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
  }
}
