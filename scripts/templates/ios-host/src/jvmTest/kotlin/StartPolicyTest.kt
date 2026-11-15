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
    assertEquals(parallel, DEFAULT_START_POLICY, "the owner's decision, after the phone run (docs/START_POLICY_PHONE_RUN.md)")
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

  @Test fun theAppCanRefuseTheOverride() {
    // keliver.startPolicyOverride / START_POLICY_OVERRIDE: any (the default) or none.
    assertEquals(true, parseStartPolicyOverride(null))
    assertEquals(true, parseStartPolicyOverride(""))
    assertEquals(true, parseStartPolicyOverride("any"))
    assertEquals(false, parseStartPolicyOverride(" none "))
    assertNull(parseStartPolicyOverride("some"))
    val refused = effectiveStartPolicy(nf, "cache-first", overrideAllowed = false)
    assertEquals(nf, refused.policy)
    assertTrue("cache-first is not allowed" in refused.source, refused.source)
    assertEquals(DEFAULT_START_POLICY, effectiveStartPolicy(null, "parallel", overrideAllowed = false).policy)
    assertEquals(EffectiveStartPolicy(parallel, "the app's setting"), effectiveStartPolicy(parallel, null, overrideAllowed = false))
    assertEquals(cacheFirst, effectiveStartPolicy(nf, "cache-first", overrideAllowed = true).policy)
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
    // Confirmed: the index names the cached manifest, at the sha256 it was loaded at.
    assertEquals(ParallelStep.SHOW_CACHED, afterParallelLookup(cached, sha, cached, sha).first)
    assertEquals(ParallelStep.SHOW_CACHED, afterParallelLookup(cached, sha, cached, sha.uppercase()).first)
    // Failed, timed out, nothing compatible: network-first shows the cache too.
    assertEquals(ParallelStep.SHOW_CACHED, afterParallelLookup(null, null, cached, sha).first)
    assertEquals(ParallelStep.SHOW_CACHED, afterParallelLookup(null, null, cached, null).first)
    // A newer one (or any other): load it, as network-first does.
    assertEquals(ParallelStep.LOAD_NAMED, afterParallelLookup("https://b.example/bundles/v6/manifest.zipline.json", sha, cached, sha).first)
    // The relay's legacy lookup has no sha256: it confirms nothing.
    assertEquals(ParallelStep.LOAD_NAMED, afterParallelLookup(cached, null, cached, sha).first)
  }

  @Test fun aParallelStartConfirmsTheCacheOnlyAtTheIndexsSha256() {
    // S6 (reference app): the index holds the cached URL to a sha256 that is not the cached
    // manifest's. Network-first fetches it, refuses it on its sha256 and falls back to the cache;
    // a parallel start must not skip that by matching the URL alone.
    val cached = "https://b.example/bundles/v2/manifest.zipline.json"
    val (step, why) = afterParallelLookup(cached, "0".repeat(64), cached, "ab".repeat(32))
    assertEquals(ParallelStep.LOAD_NAMED, step)
    assertTrue("not the cached manifest's" in why, why)
    // No sha256 recorded for the cached manifest (a host from before it was): nothing to confirm with.
    val (unknown, unknownWhy) = afterParallelLookup(cached, "ab".repeat(32), cached, null)
    assertEquals(ParallelStep.LOAD_NAMED, unknown)
    assertTrue("no sha256 is recorded" in unknownWhy, unknownWhy)
  }

  @Test fun aConfirmedCachedLoadThatFailsLoadsTheNamedReleaseFromTheNetworkOnce() {
    val cached = "https://b.example/bundles/v5/manifest.zipline.json"
    val sha = "ab".repeat(32)
    // Confirmed, then the shown load failed (say its refetch was refused on the recorded sha256):
    // the release the index named, from the network, held to the index's sha256, as network-first.
    val (step, _) = afterParallelLookup(cached, sha, cached, sha)
    assertEquals(cached to sha, retryAfterShownCache(step, cached, sha))
    // The lookup failed: network-first's start from the cache has no fall-back, so neither has this.
    val (failed, _) = afterParallelLookup(null, null, cached, sha)
    assertEquals(null, retryAfterShownCache(failed, null, null))
    // The named release already loads from the network, in a new app: nothing to retry.
    val other = "https://b.example/bundles/v6/manifest.zipline.json"
    val (named, _) = afterParallelLookup(other, sha, cached, sha)
    assertEquals(null, retryAfterShownCache(named, other, sha))
    assertEquals(null, retryAfterShownCache(ParallelStep.LOAD_NAMED, cached, sha))
  }

  @Test fun aHeldOutcomeWaitsForTheLookup() {
    val done = mutableListOf<String>()
    // Released: the last outcome runs, once.
    val hold = StartHold(active = true)
    hold.onOutcome(loaded = false) { done += "failed" }
    hold.onOutcome(loaded = true) { done += "loaded" }
    assertEquals(emptyList(), done)
    assertTrue(hold.release())
    assertEquals(listOf("loaded"), done)
    assertFalse(hold.active)
    assertFalse(hold.release())
    // After release, outcomes run at once (an update, a fall-back).
    hold.onOutcome(loaded = true) { done += "update" }
    assertEquals(listOf("loaded", "update"), done)
    // Dropped: the held outcome never runs.
    val dropped = StartHold(active = true)
    dropped.onOutcome(loaded = true) { done += "cached" }
    dropped.drop()
    assertFalse(dropped.release())
    assertEquals(listOf("loaded", "update"), done)
    // Not held: at once.
    StartHold(active = false).onOutcome(loaded = true) { done += "now" }
    assertEquals("now", done.last())
    // Released with nothing yet: the outcome, when it comes, runs at once.
    val early = StartHold(active = true)
    assertFalse(early.release())
    early.onOutcome(loaded = true) { done += "later" }
    assertEquals("later", done.last())
  }

  @Test fun anUpdateInANewAppIsToldOfItsOutcome() {
    // checkForUpdate's update after a start from the cache: the hold says what it kept, so the host
    // shows the new app on a success and reports the failure (the running app stays) on a failure.
    val told = mutableListOf<Boolean>()
    val done = mutableListOf<String>()
    val update = StartHold(active = true) { told += it }
    update.onOutcome(loaded = true) { done += "applied" }
    assertEquals(listOf(true), told)
    assertEquals(emptyList(), done, "nothing acts until the host shows it")
    assertTrue(update.release())
    assertEquals(listOf("applied"), done)
    val failed = StartHold(active = true) { told += it }
    failed.onOutcome(loaded = false) { done += "update-failed" }
    assertEquals(listOf(true, false), told)
    assertTrue(failed.release())
    assertEquals(listOf("applied", "update-failed"), done)
  }

  /** A main thread for [LoadFence]: posted blocks run when [drain]ed, in order. */
  private class Main {
    val queue = ArrayDeque<() -> Unit>()
    val post: (() -> Unit) -> Unit = { queue.addLast(it) }
    fun drain() {
      while (queue.isNotEmpty()) queue.removeFirst()()
    }
  }

  @Test fun aDroppedLoadCountsTowardNothing() {
    // M2: the cached load's code started, threw and loaded while the lookup ran; the lookup named
    // another release, so the host dropped it: none of that reaches crash rollback's ledger.
    val main = Main()
    val ledger = mutableListOf<String>()
    val fence = LoadFence(held = true, main.post)
    fence.run { ledger += "loadStarting 5" } // from Zipline's thread
    fence.run { ledger += "uncaught 5" }
    main.drain()
    assertEquals(emptyList(), ledger, "held: kept, not acted on")
    fence.drop()
    assertFalse(fence.live)
    fence.run { ledger += "loaded 5" } // a late event, after the drop
    main.drain()
    fence.goLive() // too late: a dropped app never becomes the host's
    main.drain()
    assertEquals(emptyList(), ledger)
    // Posted while held, arriving after the drop: nothing either.
    val late = LoadFence(held = true, main.post)
    late.run { ledger += "late" }
    late.drop()
    main.drain()
    assertEquals(emptyList(), ledger)
  }

  @Test fun aShownLoadActsOnWhatItKeptInOrder() {
    val main = Main()
    val ledger = mutableListOf<String>()
    val fence = LoadFence(held = true, main.post)
    fence.run { ledger += "loadStarting 5" }
    main.drain()
    fence.run { ledger += "loaded 5" }
    // Shown before the second event reached the main thread: both act, in order.
    fence.goLive()
    assertEquals(listOf("loadStarting 5"), ledger)
    main.drain()
    assertEquals(listOf("loadStarting 5", "loaded 5"), ledger)
    // The host's app now: at once, on the caller's thread.
    fence.run { ledger += "uncaught 5" }
    assertEquals("uncaught 5", ledger.last())
    assertTrue(main.queue.isEmpty())
    // An app that was never held acts at once; dropped (an update replaced it), it stops.
    val running = LoadFence(held = false, main.post)
    running.run { ledger += "running" }
    assertEquals("running", ledger.last())
    running.drop()
    running.run { ledger += "after the swap" }
    main.drain()
    assertEquals("running", ledger.last())
  }

  /** What [gate] decides for a call made now: null while it waits. */
  private fun call(gate: CallGate): () -> Boolean? {
    var decided: Boolean? = null
    gate.whenDecided { decided = it }
    return { decided }
  }

  @Test fun aHeldAppsCallsWaitUntilItShows() {
    val main = Main()
    val fence = LoadFence(held = true, main.post)
    assertFalse(fence.calls.isOpen)
    val first = call(fence.calls)
    val second = call(fence.calls)
    assertNull(first(), "held: the call waits")
    fence.goLive()
    assertEquals(true, first(), "shown: the call goes")
    assertEquals(true, second())
    assertTrue(fence.calls.isOpen)
    assertEquals(true, call(fence.calls)(), "the host's app: at once")
    // An app that was never held: open from the start.
    assertTrue(LoadFence(held = false, main.post).calls.isOpen)
  }

  @Test fun aDroppedAppsCallsFailUnsent() {
    val main = Main()
    val fence = LoadFence(held = true, main.post)
    val waiting = call(fence.calls)
    fence.drop()
    assertEquals(false, waiting(), "dropped while held: the waiting call fails")
    assertEquals(false, call(fence.calls)(), "and so does a later one")
    fence.goLive()
    assertFalse(fence.calls.isOpen, "a dropped app never becomes the host's")
    // An app an update replaced (it was live): its calls go on until it is closed.
    val replaced = LoadFence(held = false, main.post)
    replaced.drop()
    assertTrue(replaced.calls.isOpen)
    var decided: Boolean? = null
    CallGate(held = true).apply { shut(); open() }.whenDecided { decided = it }
    assertEquals(false, decided, "a gate is decided once")
  }

  @Test fun aReplacedAppsLateCloseIsNotACrash() {
    // The phone run (docs/START_POLICY_PHONE_RUN.md, Findings): every swap reported the replaced
    // app's AppLifecycle close, sent after its Zipline closed, as uncaught.
    val main = Main()
    val late = IllegalStateException(ZIPLINE_CLOSED)
    val replaced = LoadFence(held = false, main.post)
    assertFalse(replaced.isLateClose(late), "the host's app: anything uncaught is reported")
    replaced.drop()
    assertTrue(replaced.isLateClose(late), "replaced (dropped): its late close is noise")
    assertTrue(LoadFence(held = true, main.post).apply { drop() }.isLateClose(late), "and a dropped held load's")
    assertFalse(LoadFence(held = true, main.post).isLateClose(late), "held, not dropped: reported (kept until it shows)")
    // Only that exception: a real failure of a replaced app is still reported.
    assertFalse(replaced.isLateClose(IllegalStateException("boom")))
    assertFalse(replaced.isLateClose(RuntimeException(ZIPLINE_CLOSED)))
    assertFalse(replaced.isLateClose(IllegalStateException(null as String?)))
  }

  @Test fun aHeldAppsMarkerIsWrittenAtOnceAndClearedWhenDropped() {
    // L3: a PARALLEL start's cached load evaluates while held (no other app runs): it is the release
    // the host would show, so its "not yet healthy" marker is written at once, as the host's app's is.
    val store = memoryStore()
    val g = CrashGuard(store, "keliver-production-abcd", DEFAULT_CRASH_THRESHOLD).apply { screens(true) }
    val main = Main()
    val fence = LoadFence(held = true, main.post)
    fun evaluate(f: LoadFence, s: Long) = // as the listener's moduleLoadStart does
      f.codeStarts(mark = { g.loadStarting(s) }, unmark = { g.loadFailed(s, theBundles = false) })
    evaluate(fence, 6)
    assertEquals(6, g.ledger().trial)
    assertTrue(g.ledger().running, "a process that dies now counts against 6")
    assertEquals(CrashStep.Counted(6, 1, CrashKind.PROCESS), CrashGuard(store, "keliver-production-abcd").atStart())
    g.loadStarting(6)
    fence.drop()
    assertFalse(g.ledger().running, "dropped: the marker is cleared, as a download's failure clears it")
    assertEquals(CrashStep.None, CrashGuard(store, "keliver-production-abcd").atStart())
    // Dropped before its marker was registered: the listener clears it itself.
    val late = LoadFence(held = true, main.post).apply { drop() }
    evaluate(late, 6)
    assertFalse(g.ledger().running)
    // Shown: the marker stays (it is the host's app), and a later drop (an update replaced it) leaves it.
    val shown = LoadFence(held = true, main.post)
    evaluate(shown, 6)
    shown.goLive()
    shown.drop()
    assertTrue(g.ledger().running)
  }

  @Test fun anUpdateBesideARunningAppLeavesItsTrialAlone() {
    // Review round 3, M1: sequence 5 runs, on trial, and has failed once; a hotfix (6) loads in a
    // new app beside it and is dropped (the 120 s limit) or fails. 5 keeps its trial and its count,
    // so its next failure still counts toward setting it aside.
    val store = memoryStore()
    val g = CrashGuard(store, "keliver-production-abcd", DEFAULT_CRASH_THRESHOLD).apply { screens(true) }
    val main = Main()
    fun evaluate(f: LoadFence, s: Long) = // as the listener's moduleLoadStart does
      f.codeStarts(mark = { g.loadStarting(s) }, unmark = { g.loadFailed(s, theBundles = false) })
    val running = LoadFence(held = false, main.post)
    evaluate(running, 5)
    assertEquals(CrashStep.Counted(5, 1, CrashKind.EXCEPTION), g.failed(5, CrashKind.EXCEPTION))
    g.loadStarting(5) // restarted in place after the failure, as the host does
    val before = g.ledger()
    assertEquals(5, before.trial)
    assertEquals(1, before.failures)

    // Dropped while it loads: nothing it did reached the ledger.
    val dropped = LoadFence(held = true, main.post, besideALiveApp = true)
    evaluate(dropped, 6)
    main.drain()
    assertEquals(before, g.ledger(), "the update writes nothing while it is held beside a running app")
    dropped.drop()
    main.drain()
    assertEquals(before, g.ledger(), "dropped: 5 is still on trial with its failure count")

    // Failed: its outcome settles, then it is dropped (goLive never ran, so neither did its marker).
    val failed = LoadFence(held = true, main.post, besideALiveApp = true)
    evaluate(failed, 6)
    main.drain()
    failed.drop()
    main.drain()
    assertEquals(before, g.ledger())
    // 5 fails again: it is still the one on trial, so this is its second failure (the threshold).
    assertTrue(g.failed(5, CrashKind.EXCEPTION) is CrashStep.SetAside, "5's second failure sets it aside")

    // Swapped in: the update's marker is written then, and 6 goes on trial.
    val g2 = CrashGuard(memoryStore(), "keliver-production-abcd", DEFAULT_CRASH_THRESHOLD).apply { screens(true) }
    val live = LoadFence(held = false, main.post)
    live.codeStarts(mark = { g2.loadStarting(5) }, unmark = {})
    val shown = LoadFence(held = true, main.post, besideALiveApp = true)
    shown.codeStarts(mark = { g2.loadStarting(6) }, unmark = { g2.loadFailed(6, theBundles = false) })
    main.drain()
    assertEquals(5, g2.ledger().trial, "held: not yet")
    shown.goLive()
    assertEquals(6, g2.ledger().trial)
    assertTrue(g2.ledger().running)
  }

  private fun memoryStore() = object : CrashStore {
    val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(values: Map<String, String?>): Boolean {
      values.forEach { (k, v) -> if (v == null) this.values.remove(k) else this.values[k] = v }
      return true
    }
    override fun <T> locked(block: () -> T): T = block()
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
    val why = crashRollbackReason(g, CrashStep.None, 5)
    val f = StartFacts(EffectiveStartPolicy(cacheFirst, "test"), true, crashRollbackDecides(g, CrashStep.None, 5), "stable", "stable", 5, 5, why)
    assertEquals(nf, chooseStart(f).mode)
    // The log line names the cause (L5): here the floor's own release is set aside.
    assertEquals("the floor's own release (sequence 5) is set aside by crash rollback", chooseStart(f).reason)
    assertEquals("crash rollback set sequence 5 aside at this start", crashRollbackReason(g, step, 5))
    // Floor 0 (no floor recorded): only the rollback target speaks.
    assertEquals("crash rollback has a healthy release to go back to (sequence 5 is set aside)", crashRollbackReason(g, CrashStep.None, 0))
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
