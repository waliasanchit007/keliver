import dev.keliver.portal.document.DocJson
import dev.keliver.portal.document.OpAck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The relay's answer to one op batch, as the editor acts on it. */
internal sealed interface OpsReply {
  /** Applied; the document is at [version]. */
  data class Accepted(val version: Long) : OpsReply

  /** 409: raced another edit — rebase on the relay's document. */
  data object Rebase : OpsReply

  /** Refused (the edit is not in the document): roll it back. */
  data class Rejected(val error: String?) : OpsReply

  /** A success status the editor cannot read: maybe applied — refetch the document, never roll back. */
  data object Unknown : OpsReply

  /** No HTTP answer at all (network error, timeout, abort): not applied as far as the editor knows. */
  data object NetworkError : OpsReply
}

/** [status] null or 0 = the request never completed. */
internal fun opsReply(status: Int?, body: String?): OpsReply {
  if (status == null || status == 0) return OpsReply.NetworkError
  if (status == 409) return OpsReply.Rebase
  val ack = body?.let { runCatching { DocJson.decodeFromString<OpAck>(it) }.getOrNull() }
  return when {
    ack?.ok == true -> OpsReply.Accepted(ack.version)
    ack != null -> OpsReply.Rejected(ack.error)
    status in 200..299 -> OpsReply.Unknown
    else -> OpsReply.Rejected("edit rejected (HTTP $status)")
  }
}

/**
 * K3 last review round: the relay's last write-back of the screen
 * (`writeBack` on the change stream): [outcome] `surgical`, `export`,
 * `created` — or `refused` (the change would have lost code in the .kt file,
 * which is left as it is) / `failed`, when [written] is false. S4: or the
 * file was read — `external` (the document is the file again) or
 * `discarded` (read while the document was ahead of it: that change is gone).
 */
internal data class WriteBackNotice(val version: Long, val outcome: String, val written: Boolean, val message: String)

/** The `writeBack` of a change event's JSON [data], or null (none, or unreadable). */
internal fun writeBackOf(data: String): WriteBackNotice? {
  val o = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
  val wb = o["writeBack"] as? JsonObject ?: return null
  return runCatching {
    WriteBackNotice(
      version = wb["version"]!!.jsonPrimitive.longOrNull!!,
      outcome = wb["outcome"]!!.jsonPrimitive.content,
      written = wb["written"]?.jsonPrimitive?.booleanOrNull ?: true,
      message = wb["message"]?.jsonPrimitive?.content.orEmpty(),
    )
  }.getOrNull()
}

/**
 * Which write-back warnings to show: a refused, failed or discarded change
 * once per document version. [showing] = the document is ahead of its file
 * (refused / failed); a write that lands, a read of the file, or an event
 * with no status (the relay sends its current one with every event) clears
 * it. A discard is told, but the document is the file again.
 */
internal class WriteBackWarnings {
  private var warned = -1L

  /** Whether a not-written warning is current (the document is ahead of its file). */
  var showing: Boolean = false
    private set

  /** The warning text to show now for [n], or null (nothing new). */
  fun next(n: WriteBackNotice?): String? {
    if (n == null || n.written) {
      showing = false
      return null
    }
    showing = n.outcome != "discarded"
    if (n.version == warned) return null
    warned = n.version
    return n.message
  }
}
