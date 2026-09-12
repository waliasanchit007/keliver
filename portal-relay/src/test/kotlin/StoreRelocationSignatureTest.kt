import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestSigner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.ByteString.Companion.decodeHex

/**
 * Sign a real Zipline manifest with a DISPOSABLE store's private key, so that
 * `scripts/keliver-store-recovery-check.sh` can relocate the app and then
 * verify the same bundle against whatever public key the app resolves
 * afterwards. If relocation moved the app to a different identity — U23 — the
 * verification that follows fails.
 *
 * Signing lives in a test rather than the check script because the private key
 * must never leave the JVM: nothing here prints, copies or logs it.
 *
 * Skipped, like [SignedBundleVerificationTest], unless its properties are set,
 * so the ordinary test run stays fast.
 */
class StoreRelocationSignatureTest {
  @Test
  fun signAManifestWithTheStoresIdentity() {
    val privPath = System.getProperty("keliver.sign.privkey")
    val srcPath = System.getProperty("keliver.sign.manifest")
    val outPath = System.getProperty("keliver.sign.out")
    val given = listOfNotNull(privPath, srcPath, outPath).size
    if (given == 0) {
      // Only the ordinary fast run may skip. Partial configuration is a broken
      // driver, not a reason to pass — see U26.
      println("StoreRelocationSignatureTest: skipped (no signing properties)")
      return
    }
    assertEquals(
      3, given,
      "driven signing is misconfigured: privkey=$privPath manifest=$srcPath out=$outPath. " +
        "All three of -Dkeliver.sign.{privkey,manifest,out} are required.",
    )

    val src = File(srcPath!!)
    assertTrue(src.isFile, "no manifest to sign at $src")
    assertTrue(File(privPath!!).isFile, "no private key at $privPath")
    val manifest = ZiplineManifest.decodeJson(src.readText())

    val signer = ManifestSigner.Builder()
      .addEd25519("portal-ed25519", File(privPath).readText().trim().decodeHex())
      .build()
    val signed = signer.sign(manifest)
    assertTrue("portal-ed25519" in signed.signatures.keys, "signing produced no portal-ed25519 signature")

    val out = File(outPath!!)
    out.parentFile?.mkdirs()
    // The BYTES are what a verifier checks, so write exactly what was signed.
    out.writeText(signed.encodeJson())
    assertTrue(out.isFile && out.length() > 0, "nothing was written to $out")
    println("StoreRelocationSignatureTest: signed manifest written to $out")
  }
}
