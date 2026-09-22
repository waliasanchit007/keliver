import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestVerifier
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

/**
 * A published bundle must be signed by the key in THIS repo's resolved store,
 * and must verify against the public key the device hosts embed from that same
 * store. Signing and verifying used to read a hardcoded ~/.keliver-portal, so
 * once the relay moved to a per-app store they could disagree silently.
 *
 * Driven by scripts/keliver-verify-signed-bundle.sh, which produces a real
 * signed manifest with disposable keys and passes their paths in. Skipped when
 * those properties are absent, so the ordinary test run stays fast.
 */
class SignedBundleVerificationTest {
  private val manifestPath: String? = System.getProperty("keliver.verify.manifest")
  private val pubKeyPath: String? = System.getProperty("keliver.verify.pubkey")

  /**
   * Both properties or neither. A run that sets one of them is a misconfigured
   * driver, and treating it as "skipped" is how this suite reported success
   * while verifying nothing (U26) — the skip branch is only for the ordinary
   * fast test run, where neither is set.
   */
  private fun skipUnlessDriven(): Boolean {
    if (manifestPath != null && pubKeyPath != null) return false
    assertTrue(
      manifestPath == null && pubKeyPath == null,
      "driven verification is misconfigured: manifest=$manifestPath pubkey=$pubKeyPath. " +
        "Both -Dkeliver.verify.manifest and -Dkeliver.verify.pubkey are required.",
    )
    println("SignedBundleVerificationTest: skipped (no manifest/pubkey properties)")
    return true
  }

  @Test
  fun theBundleIsSignedAndVerifiesAgainstTheStoresPublicKey() {
    if (skipUnlessDriven()) return
    val manifestFile = File(manifestPath)
    val pubKeyFile = File(pubKeyPath)
    assertTrue(manifestFile.isFile, "no manifest at $manifestFile")
    assertTrue(pubKeyFile.isFile, "no public key at $pubKeyFile")

    val bytes = manifestFile.readBytes()
    val manifest = ZiplineManifest.decodeJson(bytes.decodeToString())

    // 1. it is actually signed — an unsigned bundle must not pass as success
    assertTrue(
      manifest.signatures.isNotEmpty(),
      "the manifest carries no signatures; an unsigned bundle cannot count as success",
    )
    assertTrue(
      "portal-ed25519" in manifest.signatures.keys,
      "expected a portal-ed25519 signature, got ${manifest.signatures.keys}",
    )

    // 2. it verifies against the public key the hosts embed, with Zipline's
    //    own verifier — signature checks are NOT disabled here
    val verifier = ManifestVerifier.Builder()
      .addEd25519("portal-ed25519", pubKeyFile.readText().trim().decodeHex())
      .build()
    val usedKey = verifier.verify(bytes.toByteString(), manifest)
    assertEquals("portal-ed25519", usedKey)
  }

  @Test
  fun aTamperedManifestIsRejected() {
    if (skipUnlessDriven()) return
    val bytes = File(manifestPath).readBytes()
    val manifest = ZiplineManifest.decodeJson(bytes.decodeToString())
    val verifier = ManifestVerifier.Builder()
      .addEd25519("portal-ed25519", File(pubKeyPath).readText().trim().decodeHex())
      .build()
    // flip one byte of the signed payload
    val tampered = bytes.decodeToString().replaceFirst("\"unsigned\"", "\"unsigneD\"").encodeToByteArray()
    val failed = runCatching { verifier.verify(tampered.toByteString(), manifest) }.isFailure
    assertTrue(failed, "a tampered manifest must not verify — the check would be vacuous otherwise")
  }

  /**
   * A DIFFERENT key must not verify this manifest (#78).
   *
   * "Signed, and the signature checks out" is not the property that matters
   * here. The failure this whole store contract exists to prevent is a bundle
   * signed by one identity and verified against ANOTHER — which happens when
   * the publisher and the host resolve different stores. Tampering proves the
   * payload is covered; it does not prove the check is bound to a particular
   * key, because a verifier that ignored the key entirely would still reject a
   * mangled payload.
   *
   * The foreign key is generated here rather than being random bytes, and the
   * test PROVES it is a genuine Ed25519 public key before using it — otherwise
   * a rejection could mean "that key was malformed" rather than "that key is
   * not the signer", and the assertion would be worth nothing.
   */
  @Test
  fun aDifferentPublicKeyDoesNotVerify() {
    if (skipUnlessDriven()) return
    val bytes = File(manifestPath).readBytes()
    val manifest = ZiplineManifest.decodeJson(bytes.decodeToString())

    // Without this, an UNSIGNED manifest would also "not verify" and this test
    // would pass while proving nothing. The first test asserts the same thing,
    // but a test that is only sound when another test runs is not sound.
    assertTrue(
      manifest.signatures.isNotEmpty(),
      "the manifest carries no signatures, so 'a different key does not verify' is vacuous",
    )

    val foreign = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val foreignRaw = rawEd25519PublicKey(foreign.public as java.security.interfaces.EdECPublicKey)

    // Self-check: the bytes really are this keypair's public key. Sign something
    // with the private half and verify it with the JDK using the SAME raw
    // encoding we are about to hand Zipline.
    val probe = "keliver foreign-key self-check".toByteArray()
    val signature = java.security.Signature.getInstance("Ed25519").run {
      initSign(foreign.private); update(probe); sign()
    }
    val rebuilt = java.security.KeyFactory.getInstance("Ed25519").generatePublic(
      java.security.spec.X509EncodedKeySpec(x509FromRawEd25519(foreignRaw)),
    )
    val selfCheck = java.security.Signature.getInstance("Ed25519").run {
      initVerify(rebuilt); update(probe); verify(signature)
    }
    assertTrue(
      selfCheck,
      "the foreign key fixture is not a usable Ed25519 public key, so rejecting it would " +
        "prove nothing about key binding",
    )
    // ...and it must not be the real one, or this asserts the opposite of what it says.
    val realRaw = File(pubKeyPath).readText().trim()
    assertTrue(
      foreignRaw.joinToString("") { "%02x".format(it) } != realRaw.lowercase(),
      "the foreign key equals the store's key",
    )

    // CONTROL, inside this test rather than relying on another one: the REAL key
    // must verify these exact bytes here. That is what makes the foreign-key
    // failure below attributable to the KEY. Without it, anything that broke
    // verification generally — a malformed manifest, a decode error, a missing
    // class — would satisfy "it did not verify" and the test would pass for a
    // reason that has nothing to do with identity binding.
    val realVerifier = ManifestVerifier.Builder()
      .addEd25519("portal-ed25519", realRaw.decodeHex())
      .build()
    assertEquals(
      "portal-ed25519",
      realVerifier.verify(bytes.toByteString(), manifest),
      "the store's own key does not verify this manifest, so nothing below is attributable",
    )

    val verifier = ManifestVerifier.Builder()
      .addEd25519("portal-ed25519", foreignRaw.toByteString())
      .build()
    val outcome = runCatching { verifier.verify(bytes.toByteString(), manifest) }
    assertTrue(
      outcome.isFailure,
      "a manifest signed by the store's key verified against a DIFFERENT key — signature " +
        "checking is not bound to the identity, which is the mismatch this contract exists " +
        "to prevent",
    )
    // AND IT MUST FAIL FOR THE RIGHT REASON. `isFailure` alone accepts any
    // throwable, so an unrelated runtime error would have passed as a rejection.
    // The control above proves the manifest and the verifier work; what remains
    // is that this particular failure is the signature check refusing, not an
    // IO/decode/linkage accident.
    val error = outcome.exceptionOrNull()!!
    // The predicate must be able to say NO, or asserting it proves nothing. An
    // unrelated failure is checked against the same function, in the same run.
    // The negative control MENTIONS the manifest on purpose. An IOException about
    // a disk misses every alternative trivially and only proves the predicate is
    // not constant-true; a missing manifest file is the realistic unrelated
    // failure, and it is exactly what a looser predicate would have accepted.
    assertTrue(
      !looksLikeSignatureRejection(
        java.io.FileNotFoundException("/tmp/nope/manifest.zipline.json (No such file)"),
      ),
      "the signature-rejection predicate accepts an unrelated failure that merely mentions " +
        "the manifest, so asserting it is worth nothing",
    )
    assertTrue(
      looksLikeSignatureRejection(error),
      "verification with a different key failed, but not as a signature rejection — got " +
        "${error::class.qualifiedName}: ${error.message}. An unrelated runtime failure must " +
        "not be read as proof of key binding.",
    )
  }

  /** Does this throwable read as the signature check refusing, rather than an accident? */
  private fun looksLikeSignatureRejection(t: Throwable): Boolean {
    // "manifest" is NOT in this list: FileNotFoundException on
    // manifest.zipline.json, and every ZiplineManifest* class name, would satisfy
    // it — which is an unrelated failure being read as proof of key binding.
    val text = "${t::class.qualifiedName}: ${t.message}"
    return text.contains("signature", ignoreCase = true) ||
      text.contains("verif", ignoreCase = true)
  }

  /** RFC 8032 raw encoding: little-endian y, high bit of the last byte = x parity. */
  private fun rawEd25519PublicKey(key: java.security.interfaces.EdECPublicKey): ByteArray {
    val y = key.point.y.toByteArray()          // big-endian, possibly with a sign byte
    val out = ByteArray(32)
    var i = y.size - 1
    var j = 0
    while (i >= 0 && j < 32) { out[j] = y[i]; i--; j++ }
    if (key.point.isXOdd) out[31] = (out[31].toInt() or 0x80).toByte()
    return out
  }

  /** Wrap raw Ed25519 public key bytes in the X.509 SubjectPublicKeyInfo the JDK parses. */
  private fun x509FromRawEd25519(raw: ByteArray): ByteArray =
    byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + raw
}
