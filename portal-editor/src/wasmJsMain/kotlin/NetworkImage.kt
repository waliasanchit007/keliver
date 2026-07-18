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
import kotlinx.browser.window
import kotlinx.coroutines.await
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
    val response: Response = window.fetch(url).await()
    check(response.ok) { "HTTP ${response.status} fetching $url" }
    val arrayBuffer: ArrayBuffer = response.arrayBuffer().await()
    val view = Int8Array(arrayBuffer)
    val bytes = ByteArray(view.length) { view[it] }
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
