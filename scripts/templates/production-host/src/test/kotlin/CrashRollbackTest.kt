package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WS6, crash rollback (CrashRollback.kt): the counting, the threshold, setting aside, the one
 * exception to the floor and the report's detail. The SAME file in the Android host (src/test)
 * and the iOS host (src/jvmTest); keep them identical.
 */
class CrashRollbackTest {
  private class MemoryStore : CrashStore {
    val values = mutableMapOf<String, String>()
    var writes = 0

    override fun read(key: String): String? = values[key]

    override fun write(values: Map<String, String?>): Boolean {
      writes++
      values.forEach { (k, v) -> if (v == null) this.values.remove(k) else this.values[k] = v }
      return true
    }

    override fun <T> locked(block: () -> T): T = block()
  }

  private val store = MemoryStore()
  /** A guard for the test's store; [shown]: whether a screen shows as code loads (most tests: one does). */
  private fun guard(threshold: Int = DEFAULT_CRASH_THRESHOLD, shown: Boolean = true) =
    CrashGuard(store, "keliver-production-abcd", threshold).apply { screens(shown) }
  private fun manifest(sequence: Long) = """{"metadata":{"keliver.sequence":"$sequence"},"modules":{}}"""

  /** [sequence] loads from the network, a screen shows it, and it proves healthy. */
  private fun CrashGuard.runsHealthy(sequence: Long) {
    loadStarting(sequence)
    loaded(sequence, "https://b.example/bundles/v$sequence/manifest.zipline.json", manifest(sequence))
    windowOpened(sequence)
    assertTrue(healthy(sequence))
  }

  @Test fun defaults() {
    assertEquals(2, DEFAULT_CRASH_THRESHOLD)
    assertEquals(10_000L, DEFAULT_CRASH_WINDOW_MILLIS)
    assertEquals(CrashLedger(), guard().ledger())
  }

  @Test fun theLedgerRoundTripsAndAnUnreadableOneIsEmpty() {
    val l = CrashLedger(trial = 9, failures = 1, running = true, setAside = 7, healthy = 6)
    assertEquals(l, CrashLedger.decode(l.encode()))
    for (bad in listOf(null, "", "garbage", "2;9;1;1;7;6", "1;9;1;2;7;6", "1;-9;1;1;7;6", "1;9;x;1;7;6", "1;9;1;1;7")) {
      assertEquals(CrashLedger(), CrashLedger.decode(bad), "decode($bad)")
    }
  }

  @Test fun aKeptBundleRoundTrips() {
    val kept = KeptBundle(6, "https://b.example/bundles/v6/manifest.zipline.json", "{\n  \"a\": 1\n}")
    assertEquals(kept, KeptBundle.decode(kept.encode()))
    assertNull(KeptBundle.decode("6\nhttps://x"))
    assertNull(KeptBundle.decode("0\nhttps://x\n{}"))
    assertNull(KeptBundle.decode("6\n\n{}"))
  }

  @Test fun aNewBundleGoesOnTrialWithTheMarkerSetBeforeItsCodeRuns() {
    val g = guard()
    g.loadStarting(5)
    assertEquals(CrashLedger(trial = 5, running = true), g.ledger())
    assertTrue(g.onTrial(5))
    g.loaded(5, "https://b.example/v5", manifest(5))
    assertFalse(g.ledger().running, "loaded: the marker waits for a screen")
    g.windowOpened(5)
    assertTrue(g.ledger().running)
    g.windowClosed(5)
    assertFalse(g.ledger().running, "no screen: an app ended in the background is no failure")
  }

  @Test fun aLoadWithNoScreenShownNeverSetsTheMarker() {
    // A start the user left at once, or an update check in the background: the system ends the
    // process while the code still loads. That is no failure.
    val g = guard(shown = false)
    g.loadStarting(5)
    assertEquals(CrashLedger(trial = 5), g.ledger(), "on trial, but no marker")
    assertEquals(CrashStep.None, g.atStart())
    assertEquals(0, g.ledger().failures)
  }

  @Test fun theMarkerFollowsTheScreensWhileCodeLoads() {
    val g = guard()
    g.loadStarting(5)
    assertTrue(g.ledger().running, "a screen waits for this code")
    g.screens(false)
    assertFalse(g.ledger().running, "the user left while it loaded: cleared, even though it is still loading")
    assertEquals(CrashStep.None, g.atStart(), "ended in the background: not counted")
    g.loadStarting(5)
    g.screens(false)
    g.screens(true)
    assertTrue(g.ledger().running, "back on show while it still loads")
    g.loaded(5, null, null)
    assertFalse(g.ledger().running)
    g.screens(false)
    g.screens(true)
    assertFalse(g.ledger().running, "once loaded, the crash window (windowOpened) sets it, not the screens")
    // A load that failed is no longer loading: the screens leave the ledger alone.
    g.loadStarting(5)
    g.loadFailed(5, theBundles = false)
    g.screens(false)
    g.screens(true)
    assertFalse(g.ledger().running)
  }

  @Test fun aProcessThatEndedDuringTheWindowCountsAtTheNextStart() {
    val g = guard()
    g.loadStarting(5)
    g.loaded(5, null, null)
    g.windowOpened(5)
    // The process dies here. The next start:
    val step = g.atStart()
    assertEquals(CrashStep.Counted(5, 1, CrashKind.PROCESS), step)
    assertEquals(CrashLedger(trial = 5, failures = 1), g.ledger())
    assertEquals(CrashStep.None, g.atStart(), "counted once")
  }

  @Test fun aProcessTheSystemSaysWasStoppedNotCrashedDoesNotCount() {
    val g = guard()
    g.loadStarting(5)
    g.windowOpened(5)
    assertEquals(CrashStep.None, g.atStart(lastExitCrashed = false), "a force-stop (ApplicationExitInfo: user requested)")
    assertEquals(CrashLedger(trial = 5), g.ledger(), "the marker is cleared, nothing counted")
    g.windowOpened(5)
    assertEquals(CrashStep.Counted(5, 1, CrashKind.PROCESS), g.atStart(lastExitCrashed = true))
  }

  @Test fun aStartWithoutTheMarkerCountsNothing() {
    val g = guard()
    g.loadStarting(5)
    g.loaded(5, null, null)
    assertEquals(CrashStep.None, g.atStart())
    assertEquals(0, g.ledger().failures)
  }

  @Test fun failuresAreCountedPerBundleAndSetItAsideAtTheThreshold() {
    val g = guard()
    g.runsHealthy(4)
    g.loadStarting(5)
    assertEquals(CrashStep.Counted(5, 1, CrashKind.LOAD), g.failed(5, CrashKind.LOAD))
    g.loadStarting(5) // the next start tries it again: the count stays
    assertEquals(1, g.ledger().failures)
    assertEquals(CrashStep.SetAside(5, 2, CrashKind.EXCEPTION), g.failed(5, CrashKind.EXCEPTION))
    assertEquals(CrashLedger(setAside = 5, healthy = 4), g.ledger())
    assertEquals(5, g.setAside())
  }

  @Test fun theThresholdIsConfigurable() {
    val g = guard(threshold = 3)
    g.loadStarting(5)
    assertTrue(g.failed(5, CrashKind.LOAD) is CrashStep.Counted)
    assertTrue(g.failed(5, CrashKind.LOAD) is CrashStep.Counted)
    assertEquals(CrashStep.SetAside(5, 3, CrashKind.LOAD), g.failed(5, CrashKind.LOAD))
  }

  @Test fun thresholdZeroTurnsItOff() {
    val g = guard(threshold = 0)
    g.loadStarting(5)
    assertEquals(CrashLedger(), g.ledger())
    repeat(5) { assertEquals(CrashStep.None, g.failed(5, CrashKind.EXCEPTION)) }
    assertEquals(0, g.setAside())
    assertNull(g.rollbackTo(5))
    assertNull(g.setAsideProblem(5))
    assertEquals(0, store.writes)
  }

  @Test fun onlyTheBundleOnTrialCounts() {
    val g = guard()
    g.runsHealthy(4)
    assertEquals(CrashStep.None, g.failed(4, CrashKind.EXCEPTION), "proved healthy: its later failures are not counted")
    assertEquals(CrashStep.None, g.failed(null, CrashKind.LOAD), "no verified sequence (the network, the signature): not the bundle's")
    g.loadStarting(5)
    assertEquals(CrashStep.None, g.failed(6, CrashKind.LOAD))
    assertEquals(0, g.ledger().failures)
  }

  @Test fun aNewerBundleTakesTheTrialOverWithAFreshCount() {
    val g = guard()
    g.loadStarting(5)
    g.failed(5, CrashKind.LOAD)
    g.loadStarting(6)
    assertEquals(CrashLedger(trial = 6, running = true), g.ledger())
  }

  @Test fun provingHealthyKeepsTheBundleToGoBackTo() {
    val g = guard()
    g.runsHealthy(4)
    assertEquals(CrashLedger(healthy = 4), g.ledger())
    assertFalse(g.onTrial(4))
    assertFalse(g.healthy(4), "once")
    // A cache start of a bundle on trial keeps the manifest kept at its network load.
    g.loadStarting(5)
    g.loaded(5, "https://b.example/v5", manifest(5))
    g.loadStarting(5)
    g.loaded(5, null, null)
    assertTrue(g.healthy(5))
    store.values["crashLedger-keliver-production-abcd"] = CrashLedger(setAside = 6, healthy = 5).encode()
    assertEquals(KeptBundle(5, "https://b.example/v5", manifest(5)), g.rollbackTo(6))
  }

  @Test fun aSetAsideBundleIsRefusedUntilANewerArrives() {
    val g = guard()
    g.runsHealthy(4)
    g.loadStarting(5)
    g.failed(5, CrashKind.EXCEPTION)
    g.failed(5, CrashKind.EXCEPTION)
    assertNotNull(g.setAsideProblem(5)).let { assertTrue(it.startsWith(SET_ASIDE_REFUSED), it) }
    assertNull(g.setAsideProblem(4), "the healthy one")
    assertNull(g.setAsideProblem(6), "a newer one")
    assertNull(g.setAsideProblem(null), "the floor refuses an unsequenced manifest; this does not")
    // The set-aside one never goes on trial again; a newer one does.
    g.loadStarting(5)
    assertEquals(0, g.ledger().trial)
    g.loadStarting(6)
    assertEquals(6, g.ledger().trial)
    assertEquals(5, g.setAside())
  }

  @Test fun rollbackGoesOnlyToTheHealthyBundleAndOnlyWhenTheFloorsOwnIsSetAside() {
    val g = guard()
    g.runsHealthy(4)
    // 5 loads (the floor rises to 5) and fails twice.
    g.loadStarting(5)
    assertNull(g.rollbackTo(5), "nothing set aside: the floor holds")
    g.failed(5, CrashKind.EXCEPTION)
    assertNull(g.rollbackTo(5), "one failure is not the threshold")
    g.failed(5, CrashKind.EXCEPTION)
    assertTrue(g.floorSetAside(5))
    val back = assertNotNull(g.rollbackTo(5))
    assertEquals(4, back.sequence)
    assertEquals(manifest(4), back.manifest)
    // A newer bundle raised the floor past the set-aside one: no going back below it.
    assertNull(g.rollbackTo(6))
    assertFalse(g.floorSetAside(6))
  }

  @Test fun withNothingHealthyThereIsNothingToGoBackTo() {
    val g = guard()
    g.loadStarting(5)
    g.failed(5, CrashKind.LOAD)
    g.failed(5, CrashKind.LOAD)
    assertTrue(g.floorSetAside(5))
    assertNull(g.rollbackTo(5), "the app's own view")
  }

  @Test fun theKeptManifestMustBeTheHealthyOnes() {
    val g = guard()
    g.loadStarting(4)
    g.loaded(4, null, null) // from the cache: nothing kept
    assertTrue(g.healthy(4))
    g.loadStarting(5)
    g.failed(5, CrashKind.LOAD)
    g.failed(5, CrashKind.LOAD)
    assertNull(g.rollbackTo(5), "4 proved healthy, but this install keeps no manifest for it")
    // An unreadable record is no record.
    store.values["crashHealthy-keliver-production-abcd"] = "garbage"
    assertNull(g.rollbackTo(5))
    store.values["crashHealthy-keliver-production-abcd"] = KeptBundle(3, "https://b.example/v3", manifest(3)).encode()
    assertNull(g.rollbackTo(5), "a record for another sequence than the healthy one")
  }

  @Test fun anOversizedManifestIsNotKept() {
    val g = guard()
    g.loadStarting(4)
    g.loaded(4, "https://b.example/v4", "x".repeat(MAX_KEPT_MANIFEST_CHARS + 1))
    g.healthy(4)
    g.loadStarting(5)
    g.failed(5, CrashKind.LOAD)
    g.failed(5, CrashKind.LOAD)
    assertNull(g.rollbackTo(5))
  }

  /** Stands in for a host's IOException (java.io's on Android, okio's on iOS). */
  private class FakeIoException(message: String) : Exception(message)

  /** Named as Zipline's is: a guest's JavaScript error. */
  private class QuickJsException(message: String) : Exception(message)

  private val io: (Throwable) -> Boolean = { it is FakeIoException }

  private fun progress(modules: Int, block: CodeLoadProgress.() -> Unit = {}) = CodeLoadProgress().apply { manifestReady(modules); block() }

  @Test fun aLoadThatFailsBeforeItsCodeEvaluatesIsNotTheBundles() {
    // Offline, a 404 or a timeout on the first module: nothing evaluated.
    assertFalse(progress(3) { downloadFailed() }.failureIsTheBundles(FakeIoException("HTTP 404"), io))
    assertFalse(progress(3).failureIsTheBundles(IllegalStateException("signature"), io), "nothing evaluated: the signature, the floor, a bridge binding")
    assertFalse(progress(3).evaluationBegan)
  }

  @Test fun aModuleFetchThatFailsAfterAnotherEvaluatedIsNotTheBundles() {
    // Modules download concurrently: the first evaluated, then the third's download failed.
    val p = progress(3) { assertTrue(moduleLoadStart()); moduleLoadEnd(); downloadFailed() }
    assertTrue(p.evaluationBegan)
    assertFalse(p.failureIsTheBundles(FakeIoException("timeout"), io))
    // A module whose bytes do not match the sha256 its manifest signs: between modules, no download event.
    val mismatch = progress(3) { moduleLoadStart(); moduleLoadEnd() }
    assertFalse(mismatch.failureIsTheBundles(IllegalStateException("checksum mismatch for app"), io))
    // An I/O failure anywhere in the cause chain is not the bundle's, even while a module evaluates.
    val wrapped = progress(3) { moduleLoadStart() }
    assertFalse(wrapped.failureIsTheBundles(RuntimeException("load", FakeIoException("reset")), io))
  }

  @Test fun aFailureFromTheBundlesCodeIsTheBundles() {
    assertTrue(progress(3) { moduleLoadStart() }.failureIsTheBundles(IllegalStateException("threw"), io), "a module threw while it evaluated")
    assertTrue(progress(2) { repeat(2) { moduleLoadStart(); moduleLoadEnd() } }.failureIsTheBundles(IllegalStateException("take"), io), "after every module: the presenter")
    assertTrue(progress(3) { moduleLoadStart(); moduleLoadEnd(); mainFunctionStart() }.failureIsTheBundles(IllegalStateException("main"), io))
    assertTrue(progress(3) { moduleLoadStart(); moduleLoadEnd() }.failureIsTheBundles(RuntimeException("x", QuickJsException("TypeError")), io), "a QuickJS error")
    val second = progress(3)
    assertTrue(second.moduleLoadStart())
    assertFalse(second.moduleLoadStart(), "only the first module starts the trial")
  }

  @Test fun aModuleFetchThatFailsNeverSetsAGoodBundleAside() {
    val g = guard()
    g.runsHealthy(4)
    // Two starts whose module downloads fail before any code evaluated: never on trial.
    repeat(2) {
      val p = progress(3) { downloadFailed() }
      assertEquals(CrashStep.None, g.loadFailed(5, p.failureIsTheBundles(FakeIoException("offline"), io)))
    }
    assertEquals(CrashLedger(healthy = 4), g.ledger())
    // A start whose code began to evaluate (marker set) and whose later module fetch failed: the marker clears, nothing counts.
    g.loadStarting(5)
    val p = progress(3) { moduleLoadStart(); moduleLoadEnd(); downloadFailed() }
    assertEquals(CrashStep.None, g.loadFailed(5, p.failureIsTheBundles(FakeIoException("offline"), io)))
    assertEquals(CrashLedger(trial = 5, healthy = 4), g.ledger())
    assertEquals(CrashStep.None, g.atStart(), "no marker left: the next start counts nothing")
    assertNull(g.rollbackTo(5))
    // Its code failing does count.
    assertEquals(CrashStep.Counted(5, 1, CrashKind.LOAD), g.loadFailed(5, theBundles = true))
  }

  @Test fun theReportDetailSaysWhatRunsInstead() {
    val step = CrashStep.SetAside(5, 2, CrashKind.EXCEPTION)
    val back = crashRollbackDetail(step, 2, crashGoingBackTo(4))
    assertEquals(
      "crash rollback: sequence 5 set aside after 2 of 2 failures on this install (the last: its code threw); " +
        "going back to sequence 4, which ran on this install before it",
      back,
    )
    assertTrue(CRASH_TO_APP_VIEW in crashRollbackDetail(step, 2, CRASH_TO_APP_VIEW))
    assertTrue(CRASH_TO_NEWER in crashRollbackDetail(step, 2, CRASH_TO_NEWER))
    assertFalse(CRASH_TO_APP_VIEW in back || CRASH_TO_NEWER in back)
  }
}
