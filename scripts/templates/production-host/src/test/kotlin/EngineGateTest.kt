package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.IOException

/**
 * NF2 (BundleIndex.kt, "Engines"): a host takes, at lookup, only the index entries for the engine
 * its Zipline links, and refuses, at load, a manifest for another. The publisher's side, and the
 * network load's check, are tested with the tools (portal-relay's EngineGateTest).
 */
class EngineGateTest {
  private val q2026 = "quickjs-2026"
  private val sha = "ab".repeat(32)

  private fun entry(sequence: Long, version: Int = sequence.toInt(), engine: String? = null, constraints: String? = null): String {
    val c = listOfNotNull(engine?.let { "\"engine\":$it" }, constraints).joinToString(",")
    return """{"sequence":$sequence,"version":$version,"widgetVersion":1,"capabilities":[],
      |"manifest":"v$version/manifest.zipline.json","manifestSha256":"$sha"
      |${if (c.isNotEmpty()) ",\"constraints\":{$c}" else ""}}""".trimMargin()
  }

  private fun index(vararg entries: String) = """{"format":1,"entries":[${entries.joinToString(",")}]}"""

  private fun facts(engine: String, hostVersion: Long? = null) = HostFacts(installId = "", hostVersion = hostVersion, floor = 0, engine = engine)

  private fun pick(engine: String, vararg entries: String) =
    pickFromIndex(index(*entries), emptyList(), facts = facts(engine)).getOrNull()?.let { it.sequence to it.manifestPath }

  @Test
  fun eachHostTakesItsOwnEnginesBundleOfARelease() {
    // Release 2 for both engines (v2: QuickJS 2021, which names no engine; v3: QuickJS 2026).
    val entries = arrayOf(entry(1), entry(2, 2), entry(2, 3, "\"$q2026\""))
    assertEquals(2L to "v2/manifest.zipline.json", pick(DEFAULT_ENGINE, *entries))
    assertEquals(2L to "v3/manifest.zipline.json", pick(q2026, *entries))
    // A host on an engine no entry names takes nothing.
    assertNull(pick("quickjs-2031-01-01", *entries))
  }

  @Test
  fun aReleaseWithoutAVariantForTheHostsEngineIsSkipped() {
    // Release 3 only for 2021, release 4 only for 2026: each host keeps the newest it can load.
    val entries = arrayOf(entry(2, 2), entry(2, 3, "\"$q2026\""), entry(3, 4), entry(4, 5, "\"$q2026\""))
    assertEquals(3L to "v4/manifest.zipline.json", pick(DEFAULT_ENGINE, *entries))
    assertEquals(4L to "v5/manifest.zipline.json", pick(q2026, *entries))
    // A 2026 host with nothing for its engine: no compatible entry, as for an unmet constraint.
    val e = pickFromIndex(index(entry(1)), emptyList(), facts = facts(q2026)).exceptionOrNull()
    assertTrue(e is IOException && "engine $q2026" in e.message!!, e?.message)
  }

  @Test
  fun anEngineEntryStillMeetsEveryOtherConstraint() {
    val gated = entry(2, 3, "\"$q2026\"", "\"minHostVersion\":10")
    assertEquals(1L, pick(q2026, entry(1, 1, "\"$q2026\""), gated)?.first, "an older host build")
    assertEquals(2L, pickFromIndex(index(entry(1, 1, "\"$q2026\""), gated), emptyList(), facts = facts(q2026, 10)).getOrThrow().sequence)
  }

  @Test
  fun aMalformedEngineConstraintIsSkippedByEveryHost() {
    for (bad in listOf("5", "null", "\"\"", "\"QuickJS 2026\"", "[\"$q2026\"]", "{}")) {
      assertEquals(1L, pick(DEFAULT_ENGINE, entry(1), entry(2, 2, bad))?.first, bad)
      assertEquals(1L, pick(q2026, entry(1, 1, "\"$q2026\""), entry(2, 2, bad))?.first, bad)
    }
  }

  @Test
  fun theLoadCheckReadsTheSignedValue() {
    assertNull(engineProblem(null, DEFAULT_ENGINE), "no keliver.engine: the default engine")
    assertNull(engineProblem(q2026, q2026))
    assertEquals("engine refused: the bundle is compiled for $q2026; this host runs quickjs-2021", engineProblem(q2026, DEFAULT_ENGINE))
    assertEquals("engine refused: the bundle is compiled for quickjs-2021; this host runs $q2026", engineProblem(null, q2026))
    assertTrue(engineProblem("Quick JS", q2026)!!.startsWith("engine refused: malformed"))
    assertNull(manifestEngine("""{"modules":{}}"""))
    assertNull(manifestEngine("""{"modules":{},"metadata":{"keliver.sequence":"3"}}"""))
    assertEquals(q2026, manifestEngine("""{"modules":{},"metadata":{"$ENGINE_METADATA_KEY":"$q2026"}}"""))
    // Present but not a string: malformed, so refused by every host, never read as "the default".
    val notString = manifestEngine("""{"modules":{},"metadata":{"$ENGINE_METADATA_KEY":5}}""")
    assertTrue(engineProblem(notString, DEFAULT_ENGINE)!!.startsWith("engine refused: malformed"))
    assertEquals("engine", reportReason("not-loaded", engineProblem(q2026, DEFAULT_ENGINE)!!))
  }

  /**
   * M1 (review of PR #275): the app ran sequence 10's QuickJS 2021 bundle (floor 10, last good v19/,
   * recorded by a host from before NF2, so with no engine); release 10 also has a QuickJS 2026 entry
   * (v20/). An app update links the fork, with CACHE_FIRST. Counting v19/ as cached would start
   * cache-first from a bundle this host refuses, and its background lookup finds "nothing newer"
   * (10), at every start. It is nothing cached, so the start is network-first, and loads v20/.
   */
  @Test
  fun aHostUpdatedToAnotherEngineCountsItsOldCacheAsNothingCached() {
    val v19 = "https://bundles.example/bundles/v19/manifest.zipline.json"
    fun start(lastGood: String?, policy: KeliverStartPolicy) = chooseStart(
      StartFacts(
        EffectiveStartPolicy(policy, "the app's setting"), cached = lastGood != null, crashRollback = false,
        channel = "production", checkedChannel = "production", newestSeen = 10, floor = 10,
      ),
    ).mode
    val logged = mutableListOf<String>()
    val forFork = lastGoodForEngine(v19, recordedEngine = null, hostEngine = q2026) { logged += it }
    assertNull(forFork)
    assertTrue(logged.single().contains("compiled for quickjs-2021; this host runs $q2026"), logged.toString())
    for (policy in KeliverStartPolicy.entries) assertEquals(KeliverStartPolicy.NETWORK_FIRST, start(forFork, policy), policy.wireName)
    // The same host's own cache, recorded with its engine, is cached as before.
    assertEquals(v19, lastGoodForEngine(v19, q2026, q2026))
    assertEquals(KeliverStartPolicy.CACHE_FIRST, start(lastGoodForEngine(v19, q2026, q2026), KeliverStartPolicy.CACHE_FIRST))
    // A host that stays on QuickJS 2021 keeps a cache recorded with no engine (every host before NF2).
    assertEquals(v19, lastGoodForEngine(v19, null, DEFAULT_ENGINE))
    assertEquals(v19, lastGoodForEngine(v19, DEFAULT_ENGINE, DEFAULT_ENGINE))
    // And one that goes back from the fork to QuickJS 2021 counts the fork's cache as nothing.
    assertNull(lastGoodForEngine(v19, q2026, DEFAULT_ENGINE))
    // A recorded engine that is not an engine id: nothing cached, never "the default".
    assertNull(lastGoodForEngine(v19, "Quick JS", DEFAULT_ENGINE))
    assertNull(lastGoodForEngine(null, q2026, q2026))
  }

  @Test
  fun crashRollbackGoesBackOnlyToAKeptBundleForTheHostsEngine() {
    val url = "https://bundles.example/bundles/v19/manifest.zipline.json"
    val kept2021 = KeptBundle(9, url, """{"modules":{},"metadata":{"keliver.sequence":"9"}}""")
    val kept2026 = KeptBundle(9, url, """{"modules":{},"metadata":{"keliver.sequence":"9","$ENGINE_METADATA_KEY":"$q2026"}}""")
    val logged = mutableListOf<String>()
    assertNull(keptForEngine(kept2021, q2026) { logged += it })
    assertTrue(logged.single().startsWith("crash rollback: the kept bundle of sequence 9 is not used: engine refused"), logged.toString())
    assertNull(keptForEngine(kept2026, DEFAULT_ENGINE))
    assertEquals(kept2021, keptForEngine(kept2021, DEFAULT_ENGINE))
    assertEquals(kept2026, keptForEngine(kept2026, q2026))
    assertNull(keptForEngine(null, q2026))
  }

  @Test
  fun aHostNamesItsEngineByTheQuickJsItLinks() {
    assertEquals(DEFAULT_ENGINE, engineOfQuickJsVersion("2021-03-27"))
    assertEquals(q2026, engineOfQuickJsVersion("2026-06-04"))
    assertEquals("quickjs-2031-01-01", engineOfQuickJsVersion("2031-01-01"))
  }
}
