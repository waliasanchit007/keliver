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
    val bytes = PreviewAssetRouting.load(url, SERVER) { target ->
      withAbortSignal { signal ->
        val response: Response = fetchWithSignal(target, signal).await()
        check(response.ok) { "HTTP ${response.status} fetching $url" + if (target != url) " (through the relay)" else "" }
        val arrayBuffer: ArrayBuffer = response.arrayBuffer().await()
        val view = Int8Array(arrayBuffer)
        ByteArray(view.length) { view[it] }
      }
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
