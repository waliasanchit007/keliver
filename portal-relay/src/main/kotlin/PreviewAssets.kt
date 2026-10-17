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
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
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
 *  - loopback callers only (the relay's socket is not loopback-only);
 *  - GET only; `http`/`https` URLs without user-info; the host must equal a
 *    listed host exactly (no wildcards, no subdomains);
 *  - nothing from the caller is forwarded — no cookies, no Authorization, no
 *    other header; the upstream request carries a fixed User-Agent and Accept;
 *  - redirects are followed by hand, at most [MAX_REDIRECTS], and only to
 *    listed hosts;
 *  - only a 200 whose Content-Type is `image/…` is served, and NOT
 *    `image/svg+xml`: the response is served from the relay's own origin,
 *    where an SVG opened directly would be an active document able to call
 *    the relay's write endpoints; the canvas has no SVG decoder either;
 *  - at most [maxBytes] per image, with connect/read/call timeouts;
 *  - successful images are kept in an in-memory LRU cache bounded by entries
 *    and bytes, for [ttlMillis];
 *  - the response names the caller's origin in Access-Control-Allow-Origin
 *    only when that origin is loopback, and carries `nosniff` and a sandbox CSP;
 *  - refusals are logged with the host and reason, never the URL's path or query.
 *
 * Preview only: devices load images themselves and never use this.
 */
class PreviewAssets(
  hosts: Collection<String>,
  private val client: OkHttpClient = defaultClient(),
  private val maxBytes: Int = 10 * 1024 * 1024,
  private val cacheMaxEntries: Int = 256,
  private val cacheMaxBytes: Long = 64L * 1024 * 1024,
  private val ttlMillis: Long = 10 * 60 * 1000L,
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

  private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {}
  private var cachedBytes = 0L

  /** JSON for `GET /preview-assets`: what the editor rewrites, and where to. */
  fun describeJson(): String =
    "{\"endpoint\":\"/preview-asset\",\"hosts\":[" +
      hosts.joinToString(",") { kotlinx.serialization.json.JsonPrimitive(it).toString() } + "]}"

  /** Fetch [rawUrl] (already decoded) for the canvas. */
  fun fetch(rawUrl: String?): Result {
    if (!enabled) return Result.Refused(404, "disabled")
    if (rawUrl.isNullOrBlank()) return Result.Refused(400, "missing url")
    var uri = parseAllowed(rawUrl) ?: return refuse(rawUrl, 403, "not an allowed http(s) host")
    val key = uri.toString()
    cached(key)?.let { return it }
    repeat(MAX_REDIRECTS + 1) {
      val request = Request.Builder()
        .url(uri.toString())
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", "image/*")
        .build()
      try {
        client.newCall(request).execute().use { response ->
          if (response.code in REDIRECTS) {
            val location = response.header("Location") ?: return refuse(uri, 502, "redirect without a location")
            val next = runCatching { uri.resolve(location) }.getOrNull()?.toString()?.let(::parseAllowed)
              ?: return refuse(uri, 403, "redirect to a host that is not listed")
            uri = next
            return@repeat
          }
          if (response.code != 200) return refuse(uri, 502, "upstream status ${response.code}")
          val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
          if (!type.startsWith("image/") || type.startsWith("image/svg")) {
            return refuse(uri, 415, "not a raster image (${type.ifEmpty { "no content type" }})")
          }
          val declared = response.body.contentLength()
          if (declared > maxBytes) return refuse(uri, 413, "larger than $maxBytes bytes")
          val bytes = response.body.byteStream().use { it.readNBytes(maxBytes + 1) }
          if (bytes.size > maxBytes) return refuse(uri, 413, "larger than $maxBytes bytes")
          val image = Result.Image(type, bytes)
          remember(key, image)
          return image
        }
      } catch (e: IOException) {
        // OkHttp tries a host's addresses in parallel and throws the LAST
        // failure; the others ride as suppressed. Name them all: an IPv6
        // "no route" can hide the TLS failure that actually mattered.
        val causes = (listOf(e) + e.suppressed).map { it.javaClass.simpleName }.distinct()
        return refuse(uri, 504, "upstream failed (${causes.joinToString("; ")})")
      }
    }
    return refuse(uri, 502, "more than $MAX_REDIRECTS redirects")
  }

  /** A URL this proxy may request, or null: http(s), no user-info, a listed host. */
  private fun parseAllowed(raw: String): URI? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.rawUserInfo != null) return null
    val host = uri.host?.lowercase() ?: return null
    if (host !in hosts) return null
    return uri
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

  /** Number of cached images (tests). */
  @Synchronized fun cacheSize(): Int = cache.size

  private fun refuse(url: Any, status: Int, reason: String): Result.Refused {
    val host = runCatching { URI(url.toString()).host }.getOrNull() ?: "?"
    log("portal-server: preview-asset refused for host $host: $reason")
    return Result.Refused(status, reason)
  }

  /** Serve one exchange: the whole HTTP contract of `/preview-asset`. */
  fun serve(ex: HttpExchange) {
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
      val origin = ex.requestHeaders.getFirst("Origin")
      if (origin != null && isLoopbackOrigin(origin)) {
        ex.responseHeaders.add("Access-Control-Allow-Origin", origin)
      }
      ex.responseHeaders.add("Vary", "Origin")
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
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .cookieJar(CookieJar.NO_COOKIES)
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .callTimeout(20, TimeUnit.SECONDS)
      .build()

    fun urlParam(rawQuery: String?): String? =
      (rawQuery ?: "").split('&').firstOrNull { it.startsWith("url=") }
        ?.removePrefix("url=")
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

    /** `http(s)://localhost|127.0.0.1|[::1][:port]` — the editor's own pages. */
    fun isLoopbackOrigin(origin: String): Boolean {
      val uri = runCatching { URI(origin) }.getOrNull() ?: return false
      return uri.scheme in setOf("http", "https") &&
        uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1") &&
        uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawUserInfo == null
    }
  }
}
