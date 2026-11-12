/*
 * Crash rollback on the device (WS6 in Keliver's docs/PLAN_2026-11.md; docs/DEVICE_HOST.md,
 * "Crash rollback"). The SAME file is in the Android host (src/main/kotlin) and the iOS host
 * (src/commonMain/kotlin); keep them identical. Plain Kotlin, so each host's JVM tests run it:
 * a host gives it a [CrashStore] over its own preferences.
 *
 * A bundle newer than the newest one that proved healthy on this install is ON TRIAL. Each of
 * these is one failure of the bundle on trial, counted per install and per signed sequence:
 * - its code did not load, and the failure came from its code ([CodeLoadProgress]): a module
 *   threw while it evaluated, or the guest's main function or its presenter did. Nothing that
 *   fails before its code begins to evaluate, or that is a download, is the bundle's, and none
 *   of it is counted: the network (offline, a 404, a timeout), the signature, the floor, a
 *   module whose bytes do not match the sha256 its manifest signs, or an app's bridge binding
 *   (the host's own code, which would fail for any bundle);
 * - its code threw (an uncaught guest exception) before it proved healthy;
 * - the process ended while its code loaded, or while a screen showed it before it proved
 *   healthy: the "started, not yet healthy" marker is still set at the next start. The marker
 *   is cleared while no screen shows the bundle, so an app sent to the background and ended
 *   there is not a failure. Where the system says how the process ended (Android 11 and later),
 *   only a crash or an ANR counts; elsewhere (iOS) a process stopped in the foreground during
 *   that window (a debugger's stop, say) counts too.
 * It proves healthy once screens have shown it for the crash window (10 s by default) with no
 * failure. At the threshold (2 by default; 0 turns all of this off) it is SET ASIDE: persisted,
 * and skipped on the network and on a cache start until a NEWER sequence arrives.
 *
 * The rollback rule. W4's floor is unchanged: it still rises as soon as a load succeeded, and
 * nothing below it runs, from the network or the cache, with ONE exception: the newest bundle
 * that proved healthy on this install ([CrashLedger.healthy]), and only
 * - once every sequence above it that this install loaded has been set aside (the highest set
 *   aside is at or above the floor), and
 * - from this install's own copy of its signed manifest, kept when it loaded from the network
 *   and promoted when it proved healthy (or from Zipline's cache, when that holds it), never
 *   from the network. Zipline verifies the manifest's signature again before it runs.
 * So someone who can make a new bundle fail (bad API data, say) can bring back exactly the
 * bundle this install last ran healthily, and nothing older; the network can still serve
 * nothing below the floor. With no such bundle (a first install, or a host from before this),
 * the host shows the app's own view instead (`onUnavailable`, outcome `crash-rollback`).
 * Either way the host reports `crash-rollback` (W6).
 */
package @@PACKAGE@@

/** Failures of one bundle, on one install, that set it aside (KeliverConfig.crashThreshold / HostConfig CRASH_THRESHOLD). */
internal const val DEFAULT_CRASH_THRESHOLD = 2

/** How long screens show a bundle on trial, with no failure, before it proves healthy. */
internal const val DEFAULT_CRASH_WINDOW_MILLIS = 10_000L

/** The report outcome (W6) when a bundle is set aside, and the host state's when nothing is left to show. */
internal const val CRASH_ROLLBACK_OUTCOME = "crash-rollback"

/** A crash-rollback report's detail when the host goes back to the app's own view: [reportReason] maps it to `app-view`. */
internal const val CRASH_TO_APP_VIEW = "nothing that ran on this install before it is left: the app's own view"

/** A crash-rollback report's detail when a newer bundle than the one set aside runs: `newer-bundle`. */
internal const val CRASH_TO_NEWER = "a newer bundle runs instead"

/** A crash-rollback report's detail when the host goes back to a bundle that ran here before (`previous-good`). */
internal fun crashGoingBackTo(sequence: Long?): String =
  if (sequence != null) "going back to sequence $sequence, which ran on this install before it" else "going back to the last bundle that loaded on this install"

/** The beginning of a manifest's refusal when its sequence is set aside: [reportReason] maps it to `set-aside`. */
internal const val SET_ASIDE_REFUSED = "set aside:"

/** The largest manifest kept to go back to; a larger one is not kept (and there is no going back to it). */
internal const val MAX_KEPT_MANIFEST_CHARS = 256 * 1024

/**
 * One code load's progress, from Zipline's events, to tell whether its failure is the bundle's
 * (its code's) or a download's. Zipline downloads a bundle's modules concurrently and evaluates
 * each in QuickJS once it and the modules it needs have arrived, so a module can still be
 * downloading, or fail its sha256, after another began to evaluate. A failure is the bundle's
 * only when its code began to evaluate, no download of this load failed, the failure is not an
 * I/O one, and it came from code: a QuickJS error, a module that was evaluating (it threw), or
 * after every module evaluated (the main function, the presenter). A failure between modules
 * is a fetch's (a sha256 mismatch, say). One per code load; its events come from Zipline's
 * threads.
 */
internal class CodeLoadProgress {
  @kotlin.concurrent.Volatile private var modules = -1
  @kotlin.concurrent.Volatile private var started = 0
  @kotlin.concurrent.Volatile private var ended = 0
  @kotlin.concurrent.Volatile private var mainStarted = false
  @kotlin.concurrent.Volatile private var downloadFailed = false

  /** The verified manifest names [modules] modules (manifestReady). */
  fun manifestReady(modules: Int) {
    this.modules = modules
  }

  /** A download (the manifest's or a module's) failed: nothing about this load's failure is the bundle's. */
  fun downloadFailed() {
    downloadFailed = true
  }

  /** A module begins to evaluate. True for the first: the bundle's code begins to run (CrashGuard.loadStarting). */
  fun moduleLoadStart(): Boolean = ++started == 1

  fun moduleLoadEnd() {
    ended++
  }

  fun mainFunctionStart() {
    mainStarted = true
  }

  /** Whether the bundle's code began to evaluate in this load. */
  val evaluationBegan: Boolean get() = started > 0

  /** Whether [exception], this load's failure, is the bundle's; [io] says whether one throwable is an I/O failure (the host's IOException). */
  fun failureIsTheBundles(exception: Throwable, io: (Throwable) -> Boolean): Boolean {
    if (started == 0 || downloadFailed) return false
    val chain = generateSequence(exception) { it.cause }.take(16).toList()
    if (chain.any(io)) return false
    if (chain.any { it::class.simpleName == "QuickJsException" }) return true
    return started > ended || mainStarted || (modules in 0..ended)
  }
}

/** What failed (for the app's detail and the debug log; reports carry only fixed reasons). */
internal enum class CrashKind(val text: String) {
  LOAD("its code did not load"),
  EXCEPTION("its code threw"),
  PROCESS("the app ended while it loaded or showed, before it proved healthy"),
}

/**
 * The install's crash state for one key. [trial]: the sequence on trial (0: none), with its
 * [failures]; [running]: the "started, not yet healthy" marker; [setAside]: the highest sequence
 * set aside (0: none); [healthy]: the newest sequence that proved healthy here (0: none).
 */
internal data class CrashLedger(
  val trial: Long = 0,
  val failures: Int = 0,
  val running: Boolean = false,
  val setAside: Long = 0,
  val healthy: Long = 0,
) {
  fun encode(): String = "1;$trial;$failures;${if (running) 1 else 0};$setAside;$healthy"

  companion object {
    /**
     * A stored ledger, or an empty one when there is none or it is unreadable. Empty is the safe
     * side: nothing is set aside (a crashing bundle gets its tries again) and nothing may run
     * below the floor (there is no bundle to go back to).
     */
    fun decode(text: String?): CrashLedger {
      val p = text?.split(';') ?: return CrashLedger()
      if (p.size != 6 || p[0] != "1") return CrashLedger()
      val trial = p[1].toLongOrNull()
      val failures = p[2].toIntOrNull()
      val setAside = p[4].toLongOrNull()
      val healthy = p[5].toLongOrNull()
      if (trial == null || failures == null || setAside == null || healthy == null) return CrashLedger()
      if (trial < 0 || failures < 0 || setAside < 0 || healthy < 0 || p[3] !in setOf("0", "1")) return CrashLedger()
      return CrashLedger(trial, failures, p[3] == "1", setAside, healthy)
    }
  }
}

/** A bundle this install can go back to: its signed sequence, manifest URL and manifest (as it loaded). */
internal data class KeptBundle(val sequence: Long, val manifestUrl: String, val manifest: String) {
  fun encode(): String = "$sequence\n$manifestUrl\n$manifest"

  companion object {
    fun decode(text: String?): KeptBundle? {
      if (text == null) return null
      val first = text.indexOf('\n')
      val second = if (first < 0) -1 else text.indexOf('\n', first + 1)
      if (second < 0) return null
      val sequence = text.substring(0, first).toLongOrNull()?.takeIf { it > 0 } ?: return null
      val url = text.substring(first + 1, second).takeIf { it.isNotEmpty() } ?: return null
      val manifest = text.substring(second + 1).takeIf { it.isNotEmpty() } ?: return null
      return KeptBundle(sequence, url, manifest)
    }
  }
}

/** What a failure did. */
internal sealed interface CrashStep {
  /** Nothing counted: no bundle on trial, or not this one. */
  data object None : CrashStep

  /** One more failure of [sequence]; it runs again at the next start. */
  data class Counted(val sequence: Long, val failures: Int, val kind: CrashKind) : CrashStep

  /** [sequence] reached the threshold and is set aside. */
  data class SetAside(val sequence: Long, val failures: Int, val kind: CrashKind) : CrashStep
}

/** Where a host keeps the ledger: its preferences (Android SharedPreferences, iOS NSUserDefaults). */
internal interface CrashStore {
  fun read(key: String): String?

  /** Writes every value (null removes it), durably before it returns; false when it could not. */
  fun write(values: Map<String, String?>): Boolean

  /** Runs [block] under this store's lock: the code load's thread and the main thread both write. */
  fun <T> locked(block: () -> T): T
}

/**
 * The counting, the threshold, setting aside and the one exception to the floor, for the key
 * whose cache is [cacheName]. [threshold] 0 turns crash rollback off: nothing is counted or set
 * aside, and nothing runs below the floor. From any thread.
 */
internal class CrashGuard(
  private val store: CrashStore,
  cacheName: String,
  val threshold: Int = DEFAULT_CRASH_THRESHOLD,
) {
  val enabled: Boolean = threshold > 0
  private val ledgerKey = "crashLedger-$cacheName"
  private val candidateKey = "crashCandidate-$cacheName"
  private val healthyKey = "crashHealthy-$cacheName"

  fun ledger(): CrashLedger = CrashLedger.decode(store.read(ledgerKey))

  private fun save(ledger: CrashLedger, extra: Map<String, String?> = emptyMap()) {
    store.write(mapOf(ledgerKey to ledger.encode()) + extra)
  }

  /**
   * At a host start, before the lookup: a marker the last process left set means it ended while
   * the bundle on trial loaded, or while a screen showed it before it proved healthy. When the
   * system says how that process ended ([lastExitCrashed]: Android 11+'s ApplicationExitInfo),
   * only a crash counts: a process stopped on purpose (a force-stop, say) does not. Null: it
   * cannot say (iOS, older Android), and the marker alone counts.
   */
  fun atStart(lastExitCrashed: Boolean? = null): CrashStep = store.locked {
    val l = ledger()
    when {
      !l.running -> CrashStep.None
      !enabled || l.trial <= 0 || lastExitCrashed == false -> CrashStep.None.also { save(l.copy(running = false)) }
      else -> fail(l, l.trial, CrashKind.PROCESS)
    }
  }

  /**
   * The code of a verified manifest at [sequence] begins to evaluate (Zipline's first
   * moduleLoadStart, [CodeLoadProgress.moduleLoadStart]): a bundle newer than the healthy one
   * goes on trial, and the marker is set (and written) before its code runs. Not before: a load
   * that never got that far (its downloads failed) is not the bundle's.
   */
  fun loadStarting(sequence: Long?) {
    if (!enabled || sequence == null) return
    store.locked {
      val l = ledger()
      if (sequence <= l.healthy || sequence == l.setAside) return@locked
      save(if (l.trial == sequence) l.copy(running = true) else l.copy(trial = sequence, failures = 0, running = true))
    }
  }

  /**
   * [sequence]'s code loaded. The marker is cleared until a screen shows it. [manifest], what
   * loaded from [manifestUrl] on the network (null from the cache), is kept as the bundle to go
   * back to once it proves healthy.
   */
  fun loaded(sequence: Long?, manifestUrl: String?, manifest: String?) {
    if (!enabled || sequence == null) return
    store.locked {
      val l = ledger()
      if (l.trial != sequence) return@locked
      val kept = if (manifestUrl != null && manifest != null && manifest.length <= MAX_KEPT_MANIFEST_CHARS && '\n' !in manifestUrl) {
        mapOf(candidateKey to KeptBundle(sequence, manifestUrl, manifest).encode())
      } else {
        emptyMap()
      }
      save(l.copy(running = false), kept)
    }
  }

  /** Whether [sequence] is on trial: screens showing it start its crash window. */
  fun onTrial(sequence: Long?): Boolean = enabled && sequence != null && ledger().trial == sequence

  /** A screen shows [sequence] (on trial): its window is open, and the marker is set. */
  fun windowOpened(sequence: Long?) {
    if (!enabled || sequence == null) return
    store.locked {
      val l = ledger()
      if (l.trial == sequence && !l.running) save(l.copy(running = true))
    }
  }

  /** No screen shows [sequence] now: the marker is cleared (an app ended in the background is no failure). */
  fun windowClosed(sequence: Long?) {
    if (!enabled || sequence == null) return
    store.locked {
      val l = ledger()
      if (l.trial == sequence && l.running) save(l.copy(running = false))
    }
  }

  /**
   * [sequence]'s code load failed: counted ([CrashKind.LOAD]) only when [theBundles]
   * ([CodeLoadProgress.failureIsTheBundles]); otherwise (a download, I/O) its marker is cleared
   * and nothing is counted.
   */
  fun loadFailed(sequence: Long?, theBundles: Boolean): CrashStep {
    if (theBundles) return failed(sequence, CrashKind.LOAD)
    if (!enabled || sequence == null) return CrashStep.None
    store.locked {
      val l = ledger()
      if (l.trial == sequence && l.running) save(l.copy(running = false))
    }
    return CrashStep.None
  }

  /** [sequence] failed ([kind]): counted when it is on trial, and set aside at the threshold. */
  fun failed(sequence: Long?, kind: CrashKind): CrashStep {
    if (!enabled || sequence == null) return CrashStep.None
    return store.locked {
      val l = ledger()
      if (l.trial != sequence) CrashStep.None else fail(l, sequence, kind)
    }
  }

  private fun fail(l: CrashLedger, sequence: Long, kind: CrashKind): CrashStep {
    val failures = l.failures + 1
    if (failures < threshold) {
      save(l.copy(failures = failures, running = false))
      return CrashStep.Counted(sequence, failures, kind)
    }
    save(
      l.copy(trial = 0, failures = 0, running = false, setAside = maxOf(l.setAside, sequence)),
      mapOf(candidateKey to null),
    )
    return CrashStep.SetAside(sequence, failures, kind)
  }

  /**
   * [sequence] proved healthy: no longer on trial, and the newest healthy one. Its kept manifest
   * (when it loaded from the network on this install) becomes the bundle to go back to. True
   * when it was on trial.
   */
  fun healthy(sequence: Long?): Boolean {
    if (!enabled || sequence == null) return false
    return store.locked {
      val l = ledger()
      if (l.trial != sequence) return@locked false
      val kept = KeptBundle.decode(store.read(candidateKey))?.takeIf { it.sequence == sequence }
      save(
        l.copy(trial = 0, failures = 0, running = false, healthy = sequence),
        mapOf(candidateKey to null, healthyKey to kept?.encode()),
      )
      true
    }
  }

  /** The highest sequence set aside (0: none): index entries at or below it are skipped (HostFacts.setAside). */
  fun setAside(): Long = if (enabled) ledger().setAside else 0

  /** Whether the floor's own bundle is set aside: nothing at or above the floor may run until a newer one arrives. */
  fun floorSetAside(floor: Long): Boolean = enabled && floor > 0 && ledger().setAside >= floor

  /** Why a manifest at [sequence] must not run although the floor allows it: it is set aside. */
  fun setAsideProblem(sequence: Long?): String? {
    if (!enabled || sequence == null) return null
    val l = ledger()
    return if (sequence == l.setAside && sequence > l.healthy) {
      "$SET_ASIDE_REFUSED sequence $sequence failed $threshold times on this install (crash rollback); only a newer one runs"
    } else {
      null
    }
  }

  /**
   * The bundle to go back to when the floor's own bundle is set aside: the newest that proved
   * healthy on this install, below the set-aside one, with its kept manifest. Null otherwise.
   */
  fun rollbackTo(floor: Long): KeptBundle? {
    if (!enabled) return null
    val l = ledger()
    if (l.healthy <= 0 || l.setAside <= l.healthy || l.setAside < floor) return null
    return KeptBundle.decode(store.read(healthyKey))?.takeIf { it.sequence == l.healthy }
  }
}

/**
 * A crash-rollback report's detail (the app's only): what was set aside, why, and [instead]: what
 * runs in its place ([crashGoingBackTo], [CRASH_TO_NEWER] or [CRASH_TO_APP_VIEW]).
 */
internal fun crashRollbackDetail(step: CrashStep.SetAside, threshold: Int, instead: String): String =
  "crash rollback: sequence ${step.sequence} set aside after ${step.failures} of $threshold failures on this install " +
    "(the last: ${step.kind.text}); $instead"
