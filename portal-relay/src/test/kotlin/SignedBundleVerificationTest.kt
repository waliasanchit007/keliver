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
}
