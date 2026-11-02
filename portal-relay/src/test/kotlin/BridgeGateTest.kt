import hosttemplate.HostBridge
import hosttemplate.HostFacts
import hosttemplate.ManifestPinningHttpClient
import hosttemplate.NeededBridge
import hosttemplate.bridgeProblem
import hosttemplate.manifestBridgeProblem
import hosttemplate.manifestBridges
import hosttemplate.parseBridges
import hosttemplate.pickFromIndex
import hosttemplate.reportReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.IOException

/**
 * B4 (docs/BRIDGE_CODEGEN_DESIGN.md, section "B4"): the hosts' bridge gate, in the template's
 * BundleIndex.kt: an index entry's `bridges` constraint at lookup, and the manifest's signed
 * `keliver.bridges` at load. Also holds the publisher's reading of that value (StaticIndex.kt)
 * to the hosts'.
 */
class BridgeGateTest {
  private val sha = "ab".repeat(32)
  private val shape = "cd5829702ffe49be"
  private val other = "0123456789abcdef"

  private fun entry(sequence: Long, bridges: String?) =
    """{"sequence":$sequence,"version":$sequence,"widgetVersion":1,"capabilities":[],
      |"manifest":"v$sequence/manifest.zipline.json","manifestSha256":"$sha"
      |${if (bridges != null) ",\"constraints\":{\"bridges\":$bridges}" else ""}}""".trimMargin()

  private fun index(vararg entries: String) = """{"format":1,"entries":[${entries.joinToString(",")}]}"""

  private fun facts(served: Map<String, HostBridge>) = HostFacts(installId = "", hostVersion = null, floor = 0, bridges = served)

  private fun pick(served: Map<String, HostBridge>, vararg entries: String) =
    pickFromIndex(index(*entries), emptyList(), facts = facts(served)).getOrNull()?.sequence

  @Test
  fun anEntryIsTakenOnlyByAHostServingWhatItNeeds() {
    val needs2 = entry(2, """{"ShopActions":{"min":2,"shape":"$shape"}}""")
    val base = entry(1, null)
    assertEquals(1L, pick(emptyMap(), base, needs2), "a host serving no bridge")
    assertEquals(1L, pick(mapOf("ShopActions" to HostBridge(1, null)), base, needs2), "an older version")
    assertEquals(1L, pick(mapOf("ShopActions" to HostBridge(2, other)), base, needs2), "the same version, another shape")
    assertEquals(2L, pick(mapOf("ShopActions" to HostBridge(2, shape)), base, needs2), "the same version and shape")
    assertEquals(2L, pick(mapOf("ShopActions" to HostBridge(2, null)), base, needs2), "a host that records no shape")
    assertEquals(2L, pick(mapOf("ShopActions" to HostBridge(3, other)), base, needs2), "a later version: shapes not compared")
    assertEquals(1L, pick(mapOf("Other" to HostBridge(9, null)), base, needs2), "another bridge")
  }

  @Test
  fun aMalformedBridgesConstraintAdmitsNobody() {
    val served = mapOf("ShopActions" to HostBridge(5, shape))
    for (
      bad in listOf(
        "\"x\"", "[]", """{"ShopActions":2}""", """{"ShopActions":{"min":"2"}}""", """{"ShopActions":{"min":0}}""",
        """{"ShopActions":{"min":2,"shape":"short"}}""", """{"ShopActions":{"min":2,"extra":1}}""", """{"Shop Actions":{"min":1}}""",
      )
    ) {
      assertEquals(1L, pick(served, entry(1, null), entry(2, bad)), bad)
    }
  }

  @Test
  fun theSignedValueIsParsedStrictly() {
    assertEquals(mapOf("ShopActions" to NeededBridge(2, shape), "Other" to NeededBridge(1, null)), parseBridges("ShopActions@2#$shape,Other@1"))
    assertEquals(emptyMap(), parseBridges(""))
    for (bad in listOf("ShopActions", "ShopActions@0", "ShopActions@2#SHORT", "A@1,A@1", "A@1,", " A@1", "A@1;B@2")) {
      assertNull(parseBridges(bad), bad)
    }
  }

  @Test
  fun aManifestIsRefusedWithAReasonForEachCase() {
    val served = mapOf("ShopActions" to HostBridge(2, shape))
    assertNull(manifestBridgeProblem(null, emptyMap()), "a bundle that names no bridge")
    assertNull(manifestBridgeProblem("ShopActions@2#$shape", served))
    assertTrue("does not serve" in manifestBridgeProblem("Other@1", served)!!)
    assertTrue("serves version 2" in manifestBridgeProblem("ShopActions@3", served)!!)
    assertTrue("another ShopActions@2 contract" in manifestBridgeProblem("ShopActions@2#$other", served)!!)
    assertEquals("bridge refused: malformed keliver.bridges", manifestBridgeProblem("ShopActions", served))
    assertEquals(null, bridgeProblem(emptyMap(), emptyMap()))
  }

  @Test
  fun theValueIsReadFromTheManifestBeforeVerification() {
    assertEquals("A@1", manifestBridges("""{"modules":{},"metadata":{"keliver.bridges":"A@1"}}"""))
    assertNull(manifestBridges("""{"modules":{},"metadata":{"keliver.sequence":"4"}}"""))
    assertNull(manifestBridges("""{"modules":{}}"""))
    // Present but not a string: refused as malformed, never read as "needs none".
    val notString = manifestBridges("""{"modules":{},"metadata":{"keliver.bridges":7}}""")
    assertEquals("bridge refused: malformed keliver.bridges", manifestBridgeProblem(notString, emptyMap()))
  }

  private class Fake(private val bodies: Map<String, ByteString>) : app.cash.zipline.loader.ZiplineHttpClient() {
    override suspend fun download(url: String, requestHeaders: List<Pair<String, String>>) = bodies.getValue(url)
    override suspend fun openDevelopmentServerWebSocket(url: String, requestHeaders: List<Pair<String, String>>): Flow<String> = error("unused")
  }

  @Test
  fun theManifestDownloadIsHeldToTheBridgesTheHostServes() = runBlocking {
    val url = "https://cdn.example/bundles/v4/manifest.zipline.json"
    val m = """{"modules":{},"metadata":{"keliver.sequence":"4","keliver.bridges":"ShopActions@2"}}""".encodeUtf8()
    val fake = Fake(mapOf(url to m))
    assertEquals(m, ManifestPinningHttpClient(fake, url, m.sha256().hex(), bridges = { mapOf("ShopActions" to HostBridge(2, null)) }).download(url, emptyList()))
    // The index can lie (its hash matches): the signed requirement still refuses.
    val e = assertFailsWith<IOException> { ManifestPinningHttpClient(fake, url, m.sha256().hex(), bridges = { emptyMap() }).download(url, emptyList()) }
    assertTrue(e.message!!.startsWith("bridge refused:"), e.message)
  }

  @Test
  fun refusalsAreReportedUnderTheirOwnReasons() {
    assertEquals("bridge-missing", reportReason("not-loaded", "bridge refused: the bundle needs Other@1, which this host does not serve"))
    assertEquals("bridge-missing", reportReason("not-loaded", "bridge refused: malformed keliver.bridges"))
    assertEquals(
      "bridge-shape",
      reportReason("fell-back", "bridge refused: the bundle was built against another ShopActions@2 contract (shape $other; this host's is $shape)"),
    )
  }

  @Test
  fun thePublisherReadsTheValueAsTheHostsDo() {
    for (value in listOf("ShopActions@2#$shape,Other@1", "A@1", "", "ShopActions", "A@1,A@1", "A@0", "A@1#SHORT", "Shop Actions@1")) {
      val manifest = """{"modules":{},"metadata":{"keliver.bridges":"$value"}}"""
      val publisher = signedBridges(manifest)
      val host = parseBridges(value)
      assertEquals(host == null, publisher.problem != null, value)
      if (host != null) {
        // The constraint the publisher writes admits exactly a host serving what the manifest says.
        val served = host.mapValues { (_, n) -> HostBridge(n.min, n.shape) }
        val e = """{"sequence":1,"version":1,"widgetVersion":1,"capabilities":[],"manifest":"v1/manifest.zipline.json",
          |"manifestSha256":"$sha"${publisher.constraint?.let { ",\"constraints\":$it" } ?: ""}}""".trimMargin()
        assertEquals(1L, pickFromIndex(index(e), emptyList(), facts = facts(served)).getOrThrow().sequence, value)
      }
    }
  }

  @Test
  fun aLaterVersionIsNotComparedByShapeAndEveryBridgeMustBeServed() {
    val served = mapOf("A" to HostBridge(3, other), "B" to HostBridge(1, null))
    assertNull(manifestBridgeProblem("A@2#$shape", served), "a later version: shapes not compared")
    assertNull(manifestBridgeProblem("A@3#$other,B@1", served))
    assertTrue("C@1" in manifestBridgeProblem("A@3,C@1", served)!!, "one of several not served")
    assertEquals(1L, pick(served, entry(1, null), entry(2, """{"A":{"min":3},"C":{"min":1}}""")))
    assertEquals(2L, pick(served, entry(1, null), entry(2, """{"A":{"min":3},"B":{"min":1}}""")))
    assertEquals(2L, pick(emptyMap(), entry(1, null), entry(2, "{}")), "an empty bridges constraint needs nothing")
  }

  @Test
  fun aBridgesNameNeverChangesTheReason() {
    assertEquals("bridge-missing", reportReason("not-loaded", "bridge refused: the bundle needs AnotherContract@1, which this host does not serve"))
    assertEquals("bridge-missing", reportReason("not-loaded", "bridge refused: the bundle needs SignatureVerifier@1, which this host does not serve"))
    assertEquals("bridge-missing", reportReason("not-loaded", "bridge refused: the bundle needs Sha256Mismatch@1; this host serves version 0"))
  }
}
