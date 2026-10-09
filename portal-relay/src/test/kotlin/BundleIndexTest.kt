import app.cash.zipline.loader.ZiplineHttpClient
import hosttemplate.ManifestPinningHttpClient
import hosttemplate.manifestPathOk
import hosttemplate.manifestSequence
import hosttemplate.rollbackProblem
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
  fun aHostTakesItsOwnChannelAndStableAndNoOther() {
    val idx = index(
      entry(3),
      entry(4, extra = ""","channel":"stable""""),
      entry(5, extra = ""","channel":"beta""""),
      entry(6, extra = ""","channel":"canary""""),
    )
    // W4.4: a stable host never sees beta; a beta host gets beta's newest, and is never behind stable.
    assertEquals(4L, pickFromIndex(idx, caps).getOrThrow().sequence)
    assertEquals(5L, pickFromIndex(idx, caps, channel = "beta").getOrThrow().sequence)
    assertEquals(6L, pickFromIndex(idx, caps, channel = "canary").getOrThrow().sequence)
    val stableAhead = index(entry(5, extra = ""","channel":"beta""""), entry(7, extra = ""","channel":"stable""""))
    assertEquals(7L, pickFromIndex(stableAhead, caps, channel = "beta").getOrThrow().sequence)
    // A promotion: the same sequence and manifest on two channels.
    val promoted = index(entry(4), entry(5, extra = ""","channel":"beta""""), entry(5, extra = ""","channel":"stable""""))
    assertEquals(5L, pickFromIndex(promoted, caps).getOrThrow().sequence)
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
  fun theRollbackFloorRefusesOlderOrUnsequencedManifests() {
    assertEquals(null, rollbackProblem(null, 0)) // a host that has never run a sequenced bundle
    assertEquals(null, rollbackProblem(3, 0))
    assertEquals(null, rollbackProblem(5, 5)) // the same bundle again (a restart, the cache)
    assertEquals(null, rollbackProblem(6, 5))
    assertTrue("below 5" in rollbackProblem(4, 5)!!)
    assertTrue("no signed sequence" in rollbackProblem(null, 5)!!)
  }

  @Test
  fun theSequenceIsReadOnlyFromWellFormedMetadata() {
    assertEquals(7L, manifestSequence(mapOf("keliver.sequence" to "7")))
    for (bad in listOf("", "0", "07", "-1", "7x", " 7", "99999999999999999999")) {
      assertEquals(null, manifestSequence(mapOf("keliver.sequence" to bad)), bad)
    }
    assertEquals(null, manifestSequence(emptyMap()))
    assertEquals(12L, manifestSequence("""{"modules":{},"metadata":{"keliver.sequence":"12"}}"""))
    assertEquals(null, manifestSequence("""{"modules":{},"metadata":{"keliver.sequence":12}}""")) // a number, not the signed string
    assertEquals(null, manifestSequence("""{"modules":{}}"""))
    assertEquals(null, manifestSequence("not json"))
  }

  @Test
  fun theManifestDownloadIsHeldToTheRollbackFloor() = runBlocking {
    val url = "https://cdn.example/bundles/v4/manifest.zipline.json"
    val module = "https://cdn.example/bundles/v4/main.zipline"
    val v4 = """{"modules":{},"metadata":{"keliver.sequence":"4"}}""".encodeUtf8()
    val fake = Fake(mapOf(url to v4, module to "code".encodeUtf8()))
    // At or above the floor: passes, with or without an index hash (the relay's legacy lookup has none).
    assertEquals(v4, ManifestPinningHttpClient(fake, url, null, floor = { 4 }).download(url, emptyList()))
    assertEquals(v4, ManifestPinningHttpClient(fake, url, v4.sha256().hex(), floor = { 3 }).download(url, emptyList()))
    // Below it: refused, even when the index hash matches.
    val e = assertFailsWith<IOException> { ManifestPinningHttpClient(fake, url, v4.sha256().hex(), floor = { 5 }).download(url, emptyList()) }
    assertTrue("rollback refused: sequence 4 is below 5" in e.message!!, e.message)
    // Modules are never checked against the floor.
    assertEquals("code".encodeUtf8(), ManifestPinningHttpClient(fake, url, null, floor = { 99 }).download(module, emptyList()))
  }

  @Test
  fun theAndroidAndIosHostsShipTheSameFile() {
    val android = File("../scripts/templates/production-host/src/main/kotlin/BundleIndex.kt").readText()
    val ios = File("../scripts/templates/ios-host/src/iosMain/kotlin/BundleIndex.kt").readText()
    assertEquals(android, ios)
  }
}
