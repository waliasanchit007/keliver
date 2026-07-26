import dev.keliver.capabilities.HostHttp
import dev.keliver.capabilities.HostHttpReplayException
import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.capabilities.HostHttpResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.xhr.XMLHttpRequest

private val HTTP_JSON = Json { ignoreUnknownKeys = true }
private var replaySessionCounter = 0

internal class RelayPreviewHostHttp(
  server: String,
  project: String,
  fixtureSet: String,
  private val onMiss: (String) -> Unit,
) : HostHttp {
  private val session = "preview-${++replaySessionCounter}-${Random.nextInt().toUInt().toString(16)}"
  private val endpoint =
    "$server/http-replay?project=$project&fixtureSet=$fixtureSet&session=$session"

  override suspend fun execute(request: HostHttpRequest): HostHttpResponse =
    suspendCoroutine { continuation ->
      val xhr = XMLHttpRequest()
      xhr.open("POST", endpoint)
      xhr.setRequestHeader("Content-Type", "application/json")
      xhr.addEventListener("load", { _ ->
        if (xhr.status.toInt() in 200..299) {
          runCatching {
            HTTP_JSON.decodeFromString<HostHttpResponse>(xhr.responseText)
          }.onSuccess(continuation::resume)
            .onFailure(continuation::resumeWithException)
        } else {
          val reason = parseReplayReason(xhr.responseText)
          onMiss(reason)
          continuation.resumeWithException(HostHttpReplayException(reason))
        }
      })
      xhr.addEventListener("error", { _ ->
        val reason = "relay request failed"
        onMiss(reason)
        continuation.resumeWithException(HostHttpReplayException(reason))
      })
      xhr.send(HTTP_JSON.encodeToString(request))
    }
}

private fun parseReplayReason(payload: String): String =
  runCatching {
    HTTP_JSON.parseToJsonElement(payload).jsonObject["reason"]?.jsonPrimitive?.content
  }.getOrNull() ?: "unknown replay failure"
