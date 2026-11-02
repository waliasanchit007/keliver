import hosttemplate.BridgeBindRecorder
import hosttemplate.DeclaredBridge
import hosttemplate.HostBridge
import hosttemplate.HostFacts
import hosttemplate.KeliverBridgeBinding
import hosttemplate.bindAppBridges
import hosttemplate.ManifestPinningHttpClient
import hosttemplate.NeededBridge
import hosttemplate.bridgeProblem
import hosttemplate.manifestBridgeProblem
import hosttemplate.manifestBridges
import hosttemplate.parseBridges
import hosttemplate.pickFromIndex
import hosttemplate.reportReason
import hosttemplate.servedBridgesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
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

  @Test
  fun anAppsDeclaredBridgesBecomeWhatTheGateReads() {
    assertEquals(emptyMap(), servedBridgesOf(emptyList()), "no bridge declared: none served")
    val served = servedBridgesOf(listOf(DeclaredBridge("ShopActions", 2, shape), DeclaredBridge("com.example.Other_1", 1, null)))
    assertEquals(mapOf("ShopActions" to HostBridge(2, shape), "com.example.Other_1" to HostBridge(1, null)), served)
    // What the gate admits with them: the declared version and shape, as a bundle's signed value names them.
    assertNull(manifestBridgeProblem("ShopActions@2#$shape,com.example.Other_1@1", served))
    assertTrue("another" in manifestBridgeProblem("ShopActions@2#$other", served)!!)
    assertEquals(2L, pick(served, entry(1, null), entry(2, """{"ShopActions":{"min":2,"shape":"$shape"}}""")))
    // The largest version a bundle's value can carry is accepted.
    assertEquals(HostBridge(999_999_999, null), servedBridgesOf(listOf(DeclaredBridge("A", 999_999_999, null)))["A"])
  }

  @Test
  fun aBridgeListTheHostMustNotServeIsRefused() {
    fun refused(vararg declared: DeclaredBridge): String =
      assertFailsWith<IllegalArgumentException> { servedBridgesOf(declared.toList()) }.message!!
    assertTrue("not a bridge name" in refused(DeclaredBridge("", 1, null)))
    assertTrue("not a bridge name" in refused(DeclaredBridge("1Shop", 1, null)))
    assertTrue("not a bridge name" in refused(DeclaredBridge("Shop@2", 1, null)), "a name that would break keliver.bridges")
    assertTrue("not a bridge name" in refused(DeclaredBridge("Shop,Other", 1, null)))
    assertTrue("not a bridge name" in refused(DeclaredBridge("zipline/host", 1, null)))
    for (name in listOf("HostSqlDriver", "HostHttp")) {
      assertTrue("binds a service of that name itself" in refused(DeclaredBridge(name, 1, null)), name)
    }
    for (name in listOf("KeliverTrace", "Keliver", "KeliverAnything")) {
      assertTrue("names starting with Keliver are the host's own" in refused(DeclaredBridge(name, 1, null)), name)
    }
    assertEquals(HostBridge(1, null), servedBridgesOf(listOf(DeclaredBridge("MyKeliverActions", 1, null)))["MyKeliverActions"], "only a prefix")
    assertTrue("version 0" in refused(DeclaredBridge("Shop", 0, null)))
    assertTrue("version -1" in refused(DeclaredBridge("Shop", -1, null)))
    assertTrue("version 1000000000" in refused(DeclaredBridge("Shop", 1_000_000_000, null)))
    assertTrue("not a generated SHAPE" in refused(DeclaredBridge("Shop", 1, "")))
    assertTrue("not a generated SHAPE" in refused(DeclaredBridge("Shop", 1, shape.uppercase())))
    assertTrue("not a generated SHAPE" in refused(DeclaredBridge("Shop", 1, shape + "0")))
    assertTrue("declared twice" in refused(DeclaredBridge("Shop", 1, null), DeclaredBridge("Shop", 2, null)))
  }

  /** A binding whose bind does [action] with the recorder (standing in for Zipline's listener). */
  private fun binding(name: String, action: (BridgeBindRecorder) -> Unit) = name to action

  private fun bindAll(vararg bindings: Pair<String, (BridgeBindRecorder) -> Unit>): Pair<Throwable?, List<String>> {
    val recorder = BridgeBindRecorder()
    val log = mutableListOf<String>()
    val actions = bindings.toMap()
    val list = bindings.map { (name, _) -> KeliverBridgeBinding(name, 1, null) { error("not called: bindOne is") } }
    val thrown = runCatching {
      bindAppBridges(list, recorder, { log += it }, { false }) { b -> actions.getValue(b.name)(recorder) }
    }.exceptionOrNull()
    return thrown to log
  }

  @Test
  fun eachBindingMustBindExactlyItsDeclaredNameOnce() {
    assertNull(bindAll(binding("A") { it.bound("A") }, binding("B") { it.bound("B") }).first)
    for ((what, action) in listOf<Pair<String, (BridgeBindRecorder) -> Unit>>(
      "nothing" to {},
      "another name" to { it.bound("B") },
      "a host service" to { it.bound("HostHttp") },
      "its name twice" to { it.bound("A"); it.bound("A") },
      "its name and another" to { it.bound("A"); it.bound("HostSqlDriver") },
    )) {
      val e = bindAll(binding("A", action)).first
      assertTrue(e is IllegalStateException && e.message!!.startsWith("bridge bind failed: A bound ["), "$what: $e")
      assertEquals("bridge-bind", reportReason("not-loaded", e.message!!), what)
    }
    // Names bound outside a binding's own call are not its: the recorder is off between them.
    val recorder = BridgeBindRecorder()
    recorder.bound("HostHttp")
    assertEquals(listOf("A"), recorder.record { recorder.bound("A") })
    recorder.bound("KeliverTrace")
    assertEquals(emptyList(), recorder.record { })
  }

  @Test
  fun aBindingThatThrowsFailsTheLoadWithItsClassOnly() {
    for (t in listOf<Throwable>(IllegalStateException("token=secret"), NotImplementedError("secret"), StackOverflowError())) {
      val (e, log) = bindAll(binding("A") { throw t }, binding("B") { error("B must not run after A failed") })
      assertEquals("bridge bind failed: A: ${t::class.simpleName}", e!!.message)
      assertTrue("secret" !in e.message!!, "the throwable's text stays out of the failure (it is reported)")
      assertEquals(1, log.size)
      assertTrue(log.single().startsWith("A: bind threw: "), log.single())
      assertEquals("bridge-bind", reportReason("not-loaded", e.message!!))
    }
    // A cancellation passes through as itself only when the load was cancelled, and the recorder
    // is off afterwards; one the binding made up fails the load, which Zipline then reports.
    val recorder = BridgeBindRecorder()
    val cancelled = kotlinx.coroutines.CancellationException("stop")
    val e = runCatching {
      bindAppBridges(listOf(KeliverBridgeBinding("A", 1, null) {}), recorder, {}, { true }) { recorder.bound("A"); throw cancelled }
    }.exceptionOrNull()
    assertTrue(e === cancelled, "$e")
    recorder.bound("late")
    assertEquals(emptyList(), recorder.record { })
    val (madeUp, _) = bindAll(binding("A") { throw kotlinx.coroutines.CancellationException("a timeout inside the binding") })
    assertTrue(madeUp is IllegalStateException && madeUp.message!!.startsWith("bridge bind failed: A: "), "$madeUp")
  }

  @Test
  fun ziplineReportsABindToItsListenerInsideTheBindCall() {
    // What the hosts' check rests on (Zipline 1.22): EventListener.bindService runs inside bind,
    // on the binding thread, so the recorder sees it. Zipline.create binds its own zipline/host.
    val recorder = BridgeBindRecorder()
    val listener = object : app.cash.zipline.EventListener() {
      override fun bindService(zipline: app.cash.zipline.Zipline, name: String, service: app.cash.zipline.ZiplineService) = recorder.bound(name)
    }
    val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    try {
      runBlocking(dispatcher) {
        lateinit var zipline: app.cash.zipline.Zipline
        val bound = recorder.record { zipline = app.cash.zipline.Zipline.create(dispatcher, eventListener = listener) }
        try {
          assertTrue("zipline/host" in bound, "bound while recording: $bound")
        } finally {
          zipline.close()
        }
      }
    } finally {
      dispatcher.close()
    }
  }

  @Test
  fun aBridgeBindFailureIsNeverReadAsAnotherReason() {
    for (d in listOf(
      "bridge bind failed: Verifier: java.net.ConnectException",
      "bridge bind failed: SignatureCheck bound [] (it must bind SignatureCheck, once)",
      "bridge bind failed: A: SocketTimeoutException",
    )) assertEquals("bridge-bind", reportReason("not-loaded", d), d)
  }
}
