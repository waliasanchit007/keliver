import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The signature name every Keliver host verifies manifests with: before W8 the only one, and since W8 always the last one. */
internal const val PORTAL_SIGNING_KEY_NAME = "portal-ed25519"

/** DER prefix that wraps a raw 32-byte Ed25519 public key as X.509 SubjectPublicKeyInfo. */
private val ED25519_X509_PREFIX = byteArrayOf(
  0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
)

/** W8: the name a key's own signature carries (hosts: BundleIndex.kt keySignatureName). */
internal fun keySignatureName(publicKeyHex: String): String = "keliver-ed25519-${publicKeyHex.lowercase().take(16)}"

private val KEY_SIGNATURE_NAME = Regex("keliver-ed25519-[0-9a-f]{16}")

/**
 * This app's public keys from a key file's text: one per line (blank lines
 * ignored), at most two — this app's key, then the next one during a rotation
 * (W8, docs/KEY_ROTATION.md). Lower-case hex; a reason when malformed.
 */
internal fun parsePublicKeys(text: String): Result<List<String>> = runCatching {
  val keys = text.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
  require(keys.isNotEmpty() && keys.all { unhex(it)?.size == 32 }) { "this app's public key is not 64 hex digits" }
  require(keys.size <= 2) { "this app's public key file holds ${keys.size} keys; at most two (this app's key, then the next one)" }
  require(keys.toSet().size == keys.size) { "this app's public key file lists the same key twice" }
  keys
}

/** What [checkSignatures] found: the listed keys whose signatures verified, in key-file order, and why it is refused (null: accepted). */
internal class SignatureCheck(val signers: List<String>, val problem: String?)

/**
 * Why the manifest [manifestJson] would be refused by a production host that
 * trusts the key(s) in [publicKeyHex], or null when it would load.
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
 * holds it to Zipline's own signer and verifier, and to manifests a real
 * compile task signed.
 */
internal fun publishedSignatureProblem(manifestJson: String, publicKeyHex: String): String? =
  checkSignatures(manifestJson, publicKeyHex).problem

/**
 * The signatures of [manifestJson] against this app's key file text [publicKeysText] (W8).
 *
 * Every Keliver signing block writes `portal-ed25519` LAST: before W8 it was the
 * only signature; since W8 each signing key first signs under its own name
 * ([keySignatureName]) and the first key also signs as `portal-ed25519`. Zipline
 * checks only the first signature a host trusts, so the order is part of the
 * format. Accepted when:
 * - `portal-ed25519` is present and last;
 * - every signature that names a listed key verifies against it. `portal-ed25519`
 *   names the first signing key: in a W8 manifest, the key of the first named
 *   signature (checked when listed); in an older one, it must verify against
 *   the file's FIRST key, the one hosts trust under that name;
 * - at least one listed key signed;
 * - when the file's FIRST key (this app's key) signed, `portal-ed25519` is its
 *   signature too: hosts from before W8 trust only that name, with that key.
 *   A signing key file that lists the next key first would strand them all.
 * A named signature of a key the file does not list is not checked (no host
 * that trusts only listed keys reads it).
 *
 * With [authenticOnly] (the source of a `--republish`, signed again anyway) the
 * question is only whether a listed key signed it: no signature that names a
 * listed key may fail, and one of them, or `portal-ed25519`, must verify
 * against a listed key. The order rules above do not apply.
 */
internal fun checkSignatures(manifestJson: String, publicKeysText: String, authenticOnly: Boolean = false): SignatureCheck {
  fun refused(problem: String, signers: List<String> = emptyList()) = SignatureCheck(signers, problem)
  val root = runCatching { Json.parseToJsonElement(manifestJson) as? JsonObject }.getOrNull()
    ?: return refused("the manifest is not a JSON object")
  val signatures = (root["unsigned"] as? JsonObject)?.get("signatures") as? JsonObject
  val legacyHex = (signatures?.get(PORTAL_SIGNING_KEY_NAME) as? JsonPrimitive)?.content
  if (signatures == null || legacyHex.isNullOrEmpty()) {
    return refused(
      "the bundle is UNSIGNED: its manifest has no $PORTAL_SIGNING_KEY_NAME signature " +
        "(signatures present: ${signatures?.keys?.takeIf { it.isNotEmpty() } ?: "none"})",
    )
  }
  val keys = parsePublicKeys(publicKeysText).getOrElse { return refused(it.message ?: "this app's public key is not usable") }
  val payload = Json.encodeToString(JsonElement.serializer(), JsonObject(root - "unsigned")).encodeToByteArray()
  if (authenticOnly) {
    val signers = linkedSetOf<String>()
    for ((name, sig) in signatures) {
      val hex = (sig as? JsonPrimitive)?.content.orEmpty()
      if (name == PORTAL_SIGNING_KEY_NAME) {
        keys.firstOrNull { verifies(payload, it, hex) }?.let { signers += it }
      } else {
        val key = keys.firstOrNull { keySignatureName(it) == name } ?: continue
        if (!verifies(payload, key, hex)) return refused("the $name signature does not verify against this app's public key ${key.take(16)}…")
        signers += key
      }
    }
    if (signers.isEmpty()) return refused("the manifest is signed by another key, so it does not verify against this app's public key (signatures: ${signatures.keys})")
    return SignatureCheck(keys.filter { it in signers }, null)
  }
  if (signatures.keys.last() != PORTAL_SIGNING_KEY_NAME) {
    return refused("the $PORTAL_SIGNING_KEY_NAME signature is not the last one (signatures: ${signatures.keys}); hosts check only the first signature they trust")
  }
  val signers = linkedSetOf<String>()
  val named = signatures.keys.filter { it != PORTAL_SIGNING_KEY_NAME }
  for (name in named) {
    if (!KEY_SIGNATURE_NAME.matches(name)) return refused("the manifest carries a signature this tool does not know: '$name'")
    val key = keys.firstOrNull { keySignatureName(it) == name } ?: continue
    val hex = (signatures[name] as? JsonPrimitive)?.content.orEmpty()
    if (!verifies(payload, key, hex)) return refused("the $name signature does not verify against this app's public key ${key.take(16)}…")
    signers += key
  }
  if (named.isNotEmpty()) {
    // portal-ed25519 is the first signing key's: check it when that key is listed.
    keys.firstOrNull { keySignatureName(it) == named.first() }?.let { first ->
      if (!verifies(payload, first, legacyHex)) {
        return refused("the $PORTAL_SIGNING_KEY_NAME signature does not verify against this app's public key ${first.take(16)}… (the first signer's)")
      }
    }
    if (keys.first() in signers && keySignatureName(keys.first()) != named.first()) {
      return refused(
        "this app's key (${keySignatureName(keys.first())}, the first line of the public key file) signed, but " +
          "$PORTAL_SIGNING_KEY_NAME was signed by ${named.first()}: hosts from before W8 trust only " +
          "$PORTAL_SIGNING_KEY_NAME, with this app's key, and would refuse the bundle. List this app's key FIRST in " +
          "the signing key file, and the next key second (docs/KEY_ROTATION.md)",
      )
    }
  } else {
    // A manifest from a signing block before W8: hosts check portal-ed25519 against their FIRST key.
    if (!verifies(payload, keys.first(), legacyHex)) {
      return refused(
        "the $PORTAL_SIGNING_KEY_NAME signature does not verify against this app's public key" +
          if (keys.size > 1) " (the first line of the key file; a bundle signed by the next key needs the W8 signing block)" else "",
      )
    }
    signers += keys.first()
  }
  if (signers.isEmpty()) {
    return refused(
      "the manifest is signed by another key, so it does not verify against this app's public key" +
        "${if (keys.size > 1) "s" else ""} (signatures: ${signatures.keys})",
    )
  }
  return SignatureCheck(keys.filter { it in signers }, null)
}

private fun verifies(payload: ByteArray, publicKeyHex: String, signatureHex: String): Boolean = runCatching {
  val publicKey = unhex(publicKeyHex)?.takeIf { it.size == 32 } ?: return false
  val signature = unhex(signatureHex) ?: return false
  val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_X509_PREFIX + publicKey))
  Signature.getInstance("Ed25519").run {
    initVerify(key)
    update(payload)
    verify(signature)
  }
}.getOrDefault(false)

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
