import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestSigner
import app.cash.zipline.loader.ManifestVerifier
import java.io.File
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * The relay refuses to store a bundle that a production host would refuse. Its
 * check reimplements Zipline's signature payload with the JDK's Ed25519, so
 * every case here is also put to Zipline's own [ManifestVerifier]: the two must
 * agree, or the relay is either refusing good bundles or storing bad ones.
 */
class PublishSignatureTest {
  /** Reference-app run 37505534432: manifests a real Zipline compile task signed, and that app's public key. */
  private val run13 = File("../docs/superpowers/evidence/reference-app/ci-run-37505534432")
  private val appKey = File(run13, "app-public-key.hex").readText().trim()

  private fun manifest(name: String) = File(run13, "manifest-$name.zipline.json").readText()

  /** What a production host decides: true if Zipline's verifier accepts it for [publicKeyHex]. */
  private fun ziplineAccepts(manifestJson: String, publicKeyHex: String): Boolean = runCatching {
    val verifier = ManifestVerifier.Builder().addEd25519(PORTAL_SIGNING_KEY_NAME, publicKeyHex.decodeHex()).build()
    verifier.verify(manifestJson.encodeUtf8(), ZiplineManifest.decodeJson(manifestJson))
    true
  }.getOrDefault(false)

  private fun assertAgree(manifestJson: String, publicKeyHex: String, expectAccepted: Boolean): String? {
    val problem = publishedSignatureProblem(manifestJson, publicKeyHex)
    assertEquals(expectAccepted, ziplineAccepts(manifestJson, publicKeyHex), "Zipline's verifier")
    assertEquals(expectAccepted, problem == null, "the relay's check: $problem")
    return problem
  }

  private fun edit(manifestJson: String, change: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): String {
    val root = Json.parseToJsonElement(manifestJson).jsonObject.toMutableMap()
    change(root)
    return Json.encodeToString(JsonObject.serializer(), JsonObject(root))
  }

  @Test
  fun bundlesACompileTaskSignedWithThisAppsKeyAreAccepted() {
    assertAgree(manifest("v1"), appKey, expectAccepted = true)
    assertAgree(manifest("v2"), appKey, expectAccepted = true)
  }

  @Test
  fun aBundleSignedWithAnotherAppsKeyIsRefused() {
    val problem = assertAgree(manifest("foreign"), appKey, expectAccepted = false)
    assertTrue("does not verify" in problem!!, problem)
  }

  @Test
  fun anUnsignedBundleIsRefusedAndSaysSo() {
    val unsigned = edit(manifest("v1")) { root ->
      val u = root.getValue("unsigned").jsonObject.toMutableMap()
      u["signatures"] = JsonObject(emptyMap())
      root["unsigned"] = JsonObject(u)
    }
    val problem = assertAgree(unsigned, appKey, expectAccepted = false)
    assertTrue("UNSIGNED" in problem!!, problem)
  }

  @Test
  fun anyChangeToTheSignedPartIsRefused() {
    val retargeted = edit(manifest("v1")) { it["mainModuleId"] = JsonPrimitive("./elsewhere.js") }
    assertAgree(retargeted, appKey, expectAccepted = false)
  }

  @Test
  fun theUnsignedPartIsNotCovered() {
    // As in Zipline: baseUrl and freshAtEpochMs live under `unsigned` and may change.
    val moved = edit(manifest("v1")) { root ->
      val u = root.getValue("unsigned").jsonObject.toMutableMap()
      u["baseUrl"] = JsonPrimitive("https://cdn.example.com/bundles/v1/")
      root["unsigned"] = JsonObject(u)
    }
    assertAgree(moved, appKey, expectAccepted = true)
  }

  @Test
  fun agreesWithZiplinesOwnSigner() {
    // Raw 32-byte keys, the way the relay's ensureKeys() stores them.
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    fun raw32(encoded: ByteArray) = encoded.copyOfRange(encoded.size - 32, encoded.size)
    val publicHex = raw32(pair.public.encoded).toByteString().hex()
    val signer = ManifestSigner.Builder().addEd25519(PORTAL_SIGNING_KEY_NAME, raw32(pair.private.encoded).toByteString()).build()
    val signed = signer.sign(ZiplineManifest.decodeJson(manifest("v2"))).encodeJson()
    assertAgree(signed, publicHex, expectAccepted = true)
    assertAgree(signed, appKey, expectAccepted = false)
  }

  @Test
  fun malformedInputIsAReasonNotACrash() {
    assertNotNull(publishedSignatureProblem("not json", appKey))
    assertNotNull(publishedSignatureProblem("[]", appKey))
    assertNotNull(publishedSignatureProblem(manifest("v1"), "abc"))
    assertNull(publishedSignatureProblem(manifest("v1"), "  $appKey\n"))
  }
}
