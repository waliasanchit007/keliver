/*
 * spike/keliver-web GATE 6 (Phase B) — network images (AsyncImage) on wasm.
 *
 * Coil 3 needs a network Fetcher and ships only Ktor- and OkHttp-based ones —
 * neither has a usable wasm variant (Ktor-no-wasm is the same reason keliver-http
 * is excluded from wasm). So we fetch the image bytes with the BROWSER'S OWN
 * fetch() (CORS handled natively by the browser) and hand them to Coil, which
 * decodes them via the skiko codec already in the bundle. This is the
 * wasm-native networking path — no Ktor, no extra engine.
 */
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.asImage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.await
import kotlin.js.Promise
import okio.Buffer
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.fetch.Response

/** A Coil [Fetcher] that loads http(s) images using the browser's native `fetch()`. */
internal class BrowserFetchFetcher(
  private val url: String,
  private val options: Options,
) : Fetcher {
  override suspend fun fetch(): FetchResult {
    // K5: an image on one of the app's declared hosts comes through the relay,
    // under the per-host and shared caps (the body read included). The
    // request is aborted if Coil cancels the load, so its browser connection
    // is freed together with its permit.
    var via: String? = null
    var status: Short? = null
    val bytes = try {
      PreviewAssetRouting.load(url, SERVER) { target ->
        via = target
        withAbortSignal { signal ->
          val response: Response = fetchWithSignal(target, signal).await()
          if (!response.ok) status = response.status
          check(response.ok) { "HTTP ${response.status} fetching $url" + if (target != url) " (through the relay)" else "" }
          val arrayBuffer: ArrayBuffer = response.arrayBuffer().await()
          val view = Int8Array(arrayBuffer)
          ByteArray(view.length) { view[it] }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      ImageLoadFailures.record(url, imageFailure(routed = via != null && via != url, status = status?.toInt()))
      throw e
    }
    return SourceFetchResult(
      source = ImageSource(Buffer().apply { write(bytes) }, options.fileSystem),
      mimeType = null,
      dataSource = DataSource.NETWORK,
    )
  }

  class Factory : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
      val scheme = data.scheme
      if (scheme != "http" && scheme != "https") return null
      return BrowserFetchFetcher(data.toString(), options)
    }
  }
}

/**
 * E29: the image hosts the canvas could not load from, and why. A failed image
 * used to be empty space, with one CORS error per image in the browser console;
 * the canvas now draws [BrokenImagePainter] in its place and the Preview
 * fidelity panel names each host and what to do about it.
 */
internal object ImageLoadFailures {
  private val byHost = LinkedHashMap<String, String>()

  /** Called when a host is added or its reason changes (the editor re-renders the panel). */
  var onChange: () -> Unit = {}

  fun hosts(): Map<String, String> = byHost.toMap()

  fun record(url: String, why: String) {
    val host = imageHost(url) ?: return
    if (byHost[host] == why) return
    byHost[host] = why
    onChange()
  }

  internal fun clear() = byHost.clear()
}

/** [url]'s body, or null when it cannot be fetched (no relay, a non-2xx answer). */
internal suspend fun fetchBytes(url: String): ByteArray? = try {
  val response: Response = kotlinx.browser.window.fetch(url).await()
  if (!response.ok) {
    null
  } else {
    val arrayBuffer: ArrayBuffer = response.arrayBuffer().await()
    val view = Int8Array(arrayBuffer)
    ByteArray(view.length) { view[it] }
  }
} catch (e: CancellationException) {
  throw e
} catch (e: Throwable) {
  null
}

/** The host of an http(s) URL (null for anything else). */
internal fun imageHost(url: String): String? =
  Regex("^https?://([^/:?#]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()

/** Why an image did not load, worded for what the author can do. */
internal fun imageFailure(routed: Boolean, status: Int?): String = when {
  routed -> "the relay could not fetch it" + (status?.let { " (HTTP $it)" } ?: "")
  status != null -> "the host answered HTTP $status"
  // fetch() rejects without a status: the browser refused to read it (no CORS headers) or the host is unreachable.
  else -> "the browser cannot read it from the editor's page (the host sends no CORS headers, or is unreachable)"
}

/** E29: what the canvas draws for an image that did not load — a tinted frame, crossed, never empty space. */
internal val brokenImage: coil3.Image by lazy {
  val side = 96
  val bitmap = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(side, side) }
  val canvas = org.jetbrains.skia.Canvas(bitmap)
  val s = side.toFloat()
  canvas.clear(0x14E5534B)
  val line = org.jetbrains.skia.Paint().apply { color = 0x66E5534B; strokeWidth = 2f; mode = org.jetbrains.skia.PaintMode.STROKE; isAntiAlias = true }
  canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(1f, 1f, s - 2f, s - 2f), line)
  canvas.drawLine(0f, 0f, s, s, line)
  canvas.drawLine(s, 0f, 0f, s, line)
  bitmap.setImmutable()
  bitmap.asImage()
}

/**
 * The browser's AbortController (kotlinx-browser does not declare it). An
 * interface made by [newAbortController], not an external class: a class
 * would add a compiler-generated symbol to the module's API.
 */
internal external interface AbortController : JsAny {
  val signal: AbortSignal
  fun abort()
}

private fun newAbortController(): AbortController = js("new AbortController()")

/** The browser's AbortSignal: the part of it this file reads. */
internal external interface AbortSignal : JsAny {
  val aborted: Boolean
}

/** `fetch(url, { signal })`: a request [signal] can abort, body read included. */
private fun fetchWithSignal(url: String, signal: AbortSignal): Promise<Response> =
  js("fetch(url, { signal: signal })")

/**
 * Run [block] with a fresh [AbortController]'s signal, and abort it unless
 * [block] returns normally: when the coroutine is cancelled (Coil dropped the
 * load; `Promise.await` is cancellable) or [block] fails before reading the
 * whole body. A cancelled fetch otherwise keeps its browser connection open
 * until the server answers, though its permit was already released.
 */
internal suspend fun <T> withAbortSignal(block: suspend (signal: AbortSignal) -> T): T {
  val controller = newAbortController()
  var finished = false
  try {
    return block(controller.signal).also { finished = true }
  } finally {
    if (!finished) controller.abort()
  }
}
