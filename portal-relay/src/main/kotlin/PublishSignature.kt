import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The signing-key name every Keliver host verifies manifests with. */
internal const val PORTAL_SIGNING_KEY_NAME = "portal-ed25519"

/** DER prefix that wraps a raw 32-byte Ed25519 public key as X.509 SubjectPublicKeyInfo. */
private val ED25519_X509_PREFIX = byteArrayOf(
  0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
)

/**
 * Why the manifest [manifestJson] would be refused by a production host that
 * trusts [publicKeyHex], or null when it carries a [PORTAL_SIGNING_KEY_NAME]
 * signature that verifies against it.
 *
 * The relay does not sign: the app's Zipline compile task does. A build whose
 * signing block is missing, sits above `kotlin {}`, or cannot find the store
 * still compiles — UNSIGNED, without an error — and every production host then
 * refuses the bundle. This is the check that turns that into a refused publish.
 *
 * The signed payload is Zipline 1.22's (`app.cash.zipline.internal.
 * signaturePayload`): the manifest JSON with its top-level `unsigned` member
 * removed, re-encoded compactly in the original key order. It is checked with
 * the JDK's Ed25519 so the relay needs no Zipline runtime; PublishSignatureTest
 * holds it to Zipline's own signer and to manifests a real compile task signed.
 */
internal fun publishedSignatureProblem(manifestJson: String, publicKeyHex: String): String? {
  val root = runCatching { Json.parseToJsonElement(manifestJson) as? JsonObject }.getOrNull()
    ?: return "the manifest is not a JSON object"
  val signatures = (root["unsigned"] as? JsonObject)?.get("signatures") as? JsonObject
  val signatureHex = (signatures?.get(PORTAL_SIGNING_KEY_NAME) as? JsonPrimitive)?.content
  if (signatureHex.isNullOrEmpty()) {
    return "the bundle is UNSIGNED: its manifest has no $PORTAL_SIGNING_KEY_NAME signature " +
      "(signatures present: ${signatures?.keys?.takeIf { it.isNotEmpty() } ?: "none"})"
  }
  val publicKey = unhex(publicKeyHex.trim())?.takeIf { it.size == 32 }
    ?: return "this app's public key is not 64 hex digits"
  val signature = unhex(signatureHex) ?: return "the $PORTAL_SIGNING_KEY_NAME signature is not hex"
  val payload = Json.encodeToString(JsonElement.serializer(), JsonObject(root - "unsigned"))
  val verified = runCatching {
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_X509_PREFIX + publicKey))
    Signature.getInstance("Ed25519").run {
      initVerify(key)
      update(payload.encodeToByteArray())
      verify(signature)
    }
  }.getOrElse { return "the $PORTAL_SIGNING_KEY_NAME signature could not be checked: ${it.message}" }
  return if (verified) null else "the $PORTAL_SIGNING_KEY_NAME signature does not verify against this app's public key"
}

private fun unhex(s: String): ByteArray? {
  if (s.length % 2 != 0) return null
  val out = ByteArray(s.length / 2)
  for (i in out.indices) {
    val hi = Character.digit(s[2 * i], 16)
    val lo = Character.digit(s[2 * i + 1], 16)
    if (hi < 0 || lo < 0) return null
    out[i] = ((hi shl 4) or lo).toByte()
  }
  return out
}
