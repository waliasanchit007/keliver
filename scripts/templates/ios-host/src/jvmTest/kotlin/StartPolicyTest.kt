package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The start policy (StartPolicy.kt; docs/DEVICE_HOST.md, "Start policy"): where the policy comes
 * from, what each start does, a PARALLEL start's step after its lookup, and the held first load.
 * The SAME file in the Android host (src/test) and the iOS host (src/jvmTest); keep them identical.
 */
class StartPolicyTest {
  private val nf = KeliverStartPolicy.NETWORK_FIRST
  private val parallel = KeliverStartPolicy.PARALLEL
  private val cacheFirst = KeliverStartPolicy.CACHE_FIRST

  private fun facts(
    policy: KeliverStartPolicy,
    cached: Boolean = true,
    crashRollback: Boolean = false,
    checkedChannel: String? = "stable",
    newestSeen: Long? = 5,
    floor: Long = 5,
  ) = StartFacts(EffectiveStartPolicy(policy, "test"), cached, crashRollback, "stable", checkedChannel, newestSeen, floor)

  @Test fun names() {
    assertEquals(listOf("network-first", "parallel", "cache-first"), KeliverStartPolicy.entries.map { it.wireName })
    KeliverStartPolicy.entries.forEach { assertEquals(it, KeliverStartPolicy.parse(it.wireName)) }
    assertEquals(parallel, KeliverStartPolicy.parse(" parallel "))
    listOf(null, "", "PARALLEL", "network_first", "cache", "default").forEach { assertNull(KeliverStartPolicy.parse(it), "$it") }
    assertEquals(nf, DEFAULT_START_POLICY, "today's behaviour stays the default until the owner decides")
  }

  @Test fun theOverrideWinsOnlyWhenItNamesAPolicy() {
    assertEquals(EffectiveStartPolicy(DEFAULT_START_POLICY, "the default"), effectiveStartPolicy(null, null))
    assertEquals(EffectiveStartPolicy(cacheFirst, "the app's setting"), effectiveStartPolicy(cacheFirst, null))
    assertEquals(parallel, effectiveStartPolicy(cacheFirst, "parallel").policy)
    assertEquals("the release owner's override in the index", effectiveStartPolicy(cacheFirst, "parallel").source)
    assertEquals(nf, effectiveStartPolicy(parallel, "network-first").policy, "an override can also turn a policy back")
    // Unknown or empty: the app's own setting (or the default).
    assertEquals(EffectiveStartPolicy(parallel, "the app's setting"), effectiveStartPolicy(parallel, "warp-speed"))
    assertEquals(EffectiveStartPolicy(parallel, "the app's setting"), effectiveStartPolicy(parallel, ""))
    assertEquals(EffectiveStartPolicy(DEFAULT_START_POLICY, "the default"), effectiveStartPolicy(null, "warp-speed"))
  }

  @Test fun networkFirstIsAlwaysNetworkFirst() {
    for (f in allFacts().filter { it.policy.policy == nf }) assertEquals(nf, chooseStart(f).mode, "$f")
  }

  @Test fun everyStartIsNetworkFirstOrThePolicyAndNeverLessCareful() {
    for (f in allFacts()) {
      val mode = chooseStart(f).mode
      assertTrue(mode == nf || mode == f.policy.policy, "$f -> $mode")
      // Nothing cached (a first install, a new key, a moved server): network-first.
      if (!f.cached) assertEquals(nf, mode, "$f")
      // Crash rollback decides as network-first does.
      if (f.crashRollback) assertEquals(nf, mode, "$f")
      // A newer release seen on this channel and not run yet: network-first, so it loads now.
      if (f.checkedChannel == f.channel && f.newestSeen != null && f.newestSeen > f.floor) assertEquals(nf, mode, "$f")
      // Cache-first never shows code without a lookup on this channel recorded.
      if (mode == cacheFirst) assertEquals(f.channel, f.checkedChannel, "$f")
    }
  }

  @Test fun eachPolicyOnItsOwnCases() {
    // A plain warm start, the last lookup having named the release run here.
    assertEquals(parallel, chooseStart(facts(parallel)).mode)
    assertEquals(cacheFirst, chooseStart(facts(cacheFirst)).mode)
    // First install.
    assertEquals(nf, chooseStart(facts(parallel, cached = false)).mode)
    assertEquals(nf, chooseStart(facts(cacheFirst, cached = false)).mode)
    // A newer release seen at the last check (a cache-first background lookup): this start loads it.
    val seen = chooseStart(facts(cacheFirst, newestSeen = 6, floor = 5))
    assertEquals(nf, seen.mode)
    assertTrue("sequence 6" in seen.reason, seen.reason)
    assertEquals(nf, chooseStart(facts(parallel, newestSeen = 6, floor = 5)).mode)
    // ...and once it ran (the floor rose), the cache is current again.
    assertEquals(cacheFirst, chooseStart(facts(cacheFirst, newestSeen = 6, floor = 6)).mode)
    // A rollback republish (a NEW, higher sequence) is a newer release like any other.
    assertEquals(nf, chooseStart(facts(cacheFirst, newestSeen = 9, floor = 8)).mode)
    // The last lookup named something below the floor (a halted rollout the host already ran): the cache is current.
    assertEquals(cacheFirst, chooseStart(facts(cacheFirst, newestSeen = 4, floor = 5)).mode)
    // No lookup recorded, or one for another channel: cache-first waits for one; parallel's own lookup decides.
    assertEquals(nf, chooseStart(facts(cacheFirst, checkedChannel = null, newestSeen = null)).mode)
    assertEquals(nf, chooseStart(facts(cacheFirst, checkedChannel = "beta")).mode)
    assertEquals(parallel, chooseStart(facts(parallel, checkedChannel = null, newestSeen = null)).mode)
    assertEquals(parallel, chooseStart(facts(parallel, checkedChannel = "beta", newestSeen = 99)).mode)
    // Crash rollback.
    assertEquals(nf, chooseStart(facts(parallel, crashRollback = true)).mode)
    assertEquals(nf, chooseStart(facts(cacheFirst, crashRollback = true)).mode)
  }

  @Test fun theLogLineSaysThePolicyItsSourceAndThisStart() {
    val p = EffectiveStartPolicy(cacheFirst, "the release owner's override in the index")
    val line = chooseStart(StartFacts(p, cached = false, crashRollback = false, "stable", "stable", 5, 5)).logLine(p)
    assertEquals(
      "start policy: cache-first (the release owner's override in the index); this start: network-first, nothing is cached for this key and bundle server",
      line,
    )
  }

  @Test fun aParallelStartShowsTheCacheOnlyWhenNetworkFirstWouldShowTheSameRelease() {
    val cached = "https://b.example/bundles/v5/manifest.zipline.json"
    val sha = "ab".repeat(32)
    // Confirmed: the index names the cached manifest.
    assertEquals(ParallelStep.SHOW_CACHED, afterParallelLookup(cached, sha, cached).first)
    // Failed, timed out, nothing compatible: network-first shows the cache too.
    assertEquals(ParallelStep.SHOW_CACHED, afterParallelLookup(null, null, cached).first)
    // A newer one (or any other): load it, as network-first does.
    assertEquals(ParallelStep.LOAD_NAMED, afterParallelLookup("https://b.example/bundles/v6/manifest.zipline.json", sha, cached).first)
    // The relay's legacy lookup has no sha256: it confirms nothing.
    assertEquals(ParallelStep.LOAD_NAMED, afterParallelLookup(cached, null, cached).first)
  }

  @Test fun aHeldOutcomeWaitsForTheLookup() {
    val done = mutableListOf<String>()
    // Released: the last outcome runs, once.
    val hold = StartHold(active = true)
    hold.onOutcome { done += "failed" }
    hold.onOutcome { done += "loaded" }
    assertEquals(emptyList(), done)
    assertTrue(hold.release())
    assertEquals(listOf("loaded"), done)
    assertFalse(hold.active)
    assertFalse(hold.release())
    // After release, outcomes run at once (an update, a fall-back).
    hold.onOutcome { done += "update" }
    assertEquals(listOf("loaded", "update"), done)
    // Dropped: the held outcome never runs, and later ones do.
    val dropped = StartHold(active = true)
    dropped.onOutcome { done += "cached" }
    dropped.drop()
    assertFalse(dropped.release())
    dropped.onOutcome { done += "network" }
    assertEquals(listOf("loaded", "update", "network"), done)
    // Not held: at once.
    StartHold(active = false).onOutcome { done += "now" }
    assertEquals("now", done.last())
    // Released with nothing yet: the outcome, when it comes, runs at once.
    val early = StartHold(active = true)
    assertFalse(early.release())
    early.onOutcome { done += "later" }
    assertEquals("later", done.last())
  }

  @Test fun aReplacedLoadIsBehindTheGeneration() {
    val g = LoadGeneration()
    val cachedLoad = g.current
    g.next()
    assertTrue(cachedLoad != g.current, "the cached load's late events are not the host's")
    assertEquals(1, g.current)
  }

  @Test fun crashRollbackDecidesWhenABundleIsSetAside() {
    val store = object : CrashStore {
      val values = mutableMapOf<String, String>()
      override fun read(key: String): String? = values[key]
      override fun write(values: Map<String, String?>): Boolean {
        values.forEach { (k, v) -> if (v == null) this.values.remove(k) else this.values[k] = v }
        return true
      }
      override fun <T> locked(block: () -> T): T = block()
    }
    val g = CrashGuard(store, "keliver-production-abcd", DEFAULT_CRASH_THRESHOLD).apply { screens(true) }
    fun manifest(s: Long) = """{"metadata":{"keliver.sequence":"$s"},"modules":{}}"""
    g.loadStarting(4)
    g.loaded(4, "https://b.example/bundles/v4/manifest.zipline.json", manifest(4))
    g.windowOpened(4)
    assertTrue(g.healthy(4))
    assertFalse(crashRollbackDecides(g, CrashStep.None, 4))
    // 5 (the floor) fails twice: set aside.
    g.loadStarting(5)
    g.failed(5, CrashKind.EXCEPTION)
    assertFalse(crashRollbackDecides(g, CrashStep.None, 5), "one failure is not the threshold")
    val step = g.failed(5, CrashKind.EXCEPTION)
    assertTrue(step is CrashStep.SetAside)
    assertTrue(crashRollbackDecides(g, CrashStep.None, 5))
    assertTrue(crashRollbackDecides(g, step, 5))
    val f = StartFacts(EffectiveStartPolicy(cacheFirst, "test"), true, crashRollbackDecides(g, CrashStep.None, 5), "stable", "stable", 5, 5)
    assertEquals(nf, chooseStart(f).mode)
    // A newer sequence ran (the floor rose past it): the policy applies again.
    assertFalse(crashRollbackDecides(g, CrashStep.None, 6))
    // Crash rollback off (threshold 0): it never decides.
    assertFalse(crashRollbackDecides(CrashGuard(store, "keliver-production-off", 0), CrashStep.None, 5))
  }

  /** Every combination of the facts, for each policy. */
  private fun allFacts(): List<StartFacts> = buildList {
    for (policy in KeliverStartPolicy.entries) for (cached in listOf(true, false)) for (crash in listOf(true, false))
      for (checked in listOf("stable", "beta", null)) for (seen in listOf(null, 3L, 5L, 7L)) for (floor in listOf(0L, 5L)) {
        add(StartFacts(EffectiveStartPolicy(policy, "test"), cached, crash, "stable", checked, seen, floor))
      }
  }
}
