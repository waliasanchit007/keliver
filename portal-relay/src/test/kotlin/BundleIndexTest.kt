import app.cash.zipline.loader.ZiplineHttpClient
import hosttemplate.ManifestPinningHttpClient
import hosttemplate.manifestPathOk
import hosttemplate.pickFromIndex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.IOException

/**
 * The production hosts' index reader and manifest pin: the template source
 * itself (scripts/templates/production-host/src/main/kotlin/BundleIndex.kt),
 * compiled into this test by portal-relay's build.gradle.
 */
class BundleIndexTest {
  private val sha = "ab".repeat(32)
  private val caps = listOf("host-sql@1")

  private fun entry(
    sequence: Long,
    extra: String = "",
    manifest: String = "v$sequence/manifest.zipline.json",
    capabilities: String = "\"host-sql@1\"",
    widgetVersion: Int = 1,
    sha256: String = sha,
  ) = """{"sequence":$sequence,"version":$sequence,"widgetVersion":$widgetVersion,"capabilities":[$capabilities],
    |"manifest":"$manifest","manifestSha256":"$sha256"$extra}""".trimMargin()

  private fun index(vararg entries: String) = """{"format":1,"entries":[${entries.joinToString(",")}]}"""

  @Test
  fun theHighestSequenceWinsWhateverTheOrder() {
    val pick = pickFromIndex(index(entry(3), entry(9, manifest = "v2/manifest.zipline.json"), entry(5)), caps).getOrThrow()
    assertEquals(9L, pick.sequence)
    assertEquals("v2/manifest.zipline.json", pick.manifestPath)
    assertEquals(sha, pick.manifestSha256)
  }

  @Test
  fun entriesThisHostCannotRunAreSkipped() {
    val skipped = listOf(
      entry(10, extra = ""","channel":"beta""""),
      entry(11, extra = ""","constraints":{"minHostVersion":2}"""),
      entry(12, extra = ""","constraints":"x""""),
      entry(13, widgetVersion = 2),
      entry(14, capabilities = "\"host-sql@1\",\"host-http@1\""),
      entry(15, manifest = "../v1/manifest.zipline.json"),
      entry(16, manifest = "/bundles/v1/manifest.zipline.json"),
      entry(17, manifest = "https://elsewhere.example/m.json"),
      entry(18, sha256 = "abc"),
      entry(19, extra = ""","channel":7"""),
      """{"sequence":20,"widgetVersion":1,"capabilities":[],"manifest":"v20/m.json"}""",
      """{"sequence":-1,"widgetVersion":1,"capabilities":[],"manifest":"v/m.json","manifestSha256":"$sha"}""",
      "42",
    )
    val pick = pickFromIndex(index(entry(1), *skipped.toTypedArray()), caps).getOrThrow()
    assertEquals(1L, pick.sequence)
    // Each one alone gives no bundle at all.
    for (e in skipped) assertTrue(pickFromIndex(index(e), caps).isFailure, e)
  }

  @Test
  fun stableAndAnEmptyConstraintSetAreUsable() {
    assertEquals(4L, pickFromIndex(index(entry(4, extra = ""","channel":"stable","constraints":{}""")), caps).getOrThrow().sequence)
    assertEquals(4L, pickFromIndex(index(entry(4, capabilities = "")), emptyList()).getOrThrow().sequence)
  }

  @Test
  fun anIndexThatIsNotFormatOneIsRefused() {
    for (bad in listOf("nope", "[]", """{"format":2,"entries":[${entry(1)}]}""", """{"entries":[${entry(1)}]}""", """{"format":1}""")) {
      assertTrue(pickFromIndex(bad, caps).isFailure, bad)
    }
  }

  @Test
  fun manifestPaths() {
    assertTrue(manifestPathOk("v1/manifest.zipline.json"))
    for (bad in listOf("", "/v1/m.json", "v1//m.json", "./m.json", "v1/../m.json", "http://x/m.json", "v1/m.json?x", "v1\\m.json", "v 1/m.json")) {
      assertFalse(manifestPathOk(bad), bad)
    }
  }

  private class Fake(val bodies: Map<String, ByteString>) : ZiplineHttpClient() {
    override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>) = bodies.getValue(url)
  }

  @Test
  fun theManifestIsHeldToTheIndexHashAndNothingElseIs() = runBlocking {
    val manifest = "{\"modules\":{}}".encodeUtf8()
    val url = "https://cdn.example/bundles/v1/manifest.zipline.json"
    val module = "https://cdn.example/bundles/v1/main.zipline"
    val fake = Fake(mapOf(url to manifest, module to "code".encodeUtf8()))
    assertEquals(manifest, ManifestPinningHttpClient(fake, url, manifest.sha256().hex()).download(url, emptyList()))
    assertEquals("code".encodeUtf8(), ManifestPinningHttpClient(fake, url, sha).download(module, emptyList()))
    val e = assertFailsWith<IOException> { ManifestPinningHttpClient(fake, url, sha).download(url, emptyList()) }
    assertTrue("manifest sha256 mismatch" in e.message!!, e.message)
  }

  @Test
  fun theAndroidAndIosHostsShipTheSameFile() {
    val android = File("../scripts/templates/production-host/src/main/kotlin/BundleIndex.kt").readText()
    val ios = File("../scripts/templates/ios-host/src/iosMain/kotlin/BundleIndex.kt").readText()
    assertEquals(android, ios)
  }
}
