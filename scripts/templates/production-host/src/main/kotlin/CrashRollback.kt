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
 * - the process ended while a screen showed: while the bundle's code evaluated, or once it had
 *   loaded and before it proved healthy. The "started, not yet healthy" marker is still set at
 *   the next start. The marker is set only while a screen shows (cleared when none does, even
 *   while code is still loading), so an app sent to the background and ended there is not a
 *   failure, nor is a load that runs in the background (an update check, a start the user left). Where the system says how the process ended (Android 11 and later),
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
 * [failures]; [running]: the "started, not yet healthy" marker, with the [pid] of the process that
 * set it and when ([markedAt], epoch ms; both 0 where the host gives none), so the system's exit
 * record for exactly that process is the one asked (Android); [setAside]: the highest sequence
 * set aside (0: none); [healthy]: the newest sequence that proved healthy here (0: none).
 */
internal data class CrashLedger(
  val trial: Long = 0,
  val failures: Int = 0,
  val running: Boolean = false,
  val setAside: Long = 0,
  val healthy: Long = 0,
  val pid: Int = 0,
  val markedAt: Long = 0,
) {
  fun encode(): String = "2;$trial;$failures;${if (running) 1 else 0};$setAside;$healthy;$pid;$markedAt"

  companion object {
    /**
     * A stored ledger (this format, or the first one, without the pid and time), or an empty one
     * when there is none or it is unreadable. Empty forgets what was set aside (a crashing bundle
     * gets its tries again) and leaves nothing to go back to below the floor.
     */
    fun decode(text: String?): CrashLedger {
      val p = text?.split(';') ?: return CrashLedger()
      if (!((p.size == 6 && p[0] == "1") || (p.size == 8 && p[0] == "2"))) return CrashLedger()
      val trial = p[1].toLongOrNull()
      val failures = p[2].toIntOrNull()
      val setAside = p[4].toLongOrNull()
      val healthy = p[5].toLongOrNull()
      val pid = if (p.size == 8) p[6].toIntOrNull() else 0
      val markedAt = if (p.size == 8) p[7].toLongOrNull() else 0L
      if (trial == null || failures == null || setAside == null || healthy == null || pid == null || markedAt == null) return CrashLedger()
      if (trial < 0 || failures < 0 || setAside < 0 || healthy < 0 || pid < 0 || markedAt < 0 || p[3] !in setOf("0", "1")) return CrashLedger()
      return CrashLedger(trial, failures, p[3] == "1", setAside, healthy, pid, markedAt)
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
 * aside, and nothing runs below the floor. [pid] and [now] (epoch ms) are stored with the marker
 * ([CrashLedger.pid]); a host that cannot ask how a process ended leaves them 0. From any thread.
 */
internal class CrashGuard(
  private val store: CrashStore,
  cacheName: String,
  val threshold: Int = DEFAULT_CRASH_THRESHOLD,
  private val pid: Int = 0,
  private val now: () -> Long = { 0L },
) {
  val enabled: Boolean = threshold > 0
  private val ledgerKey = "crashLedger-$cacheName"
  private val candidateKey = "crashCandidate-$cacheName"
  private val healthyKey = "crashHealthy-$cacheName"

  /** Whether a screen shows now ([screens]); under the store's lock. */
  private var shown = false

  /** The sequence whose code is evaluating now (between [loadStarting] and its end); under the store's lock. */
  private var evaluating: Long? = null

  fun ledger(): CrashLedger = CrashLedger.decode(store.read(ledgerKey))

  /**
   * Whether a screen shows now: the host says so on every change (main thread). Code that is
   * still evaluating has its marker set only while a screen shows, so a load that goes on after
   * the user left the app (or an update check in the background) and is ended there by the
   * system never counts.
   */
  fun screens(shown: Boolean) {
    if (!enabled) return
    store.locked {
      this.shown = shown
      val s = evaluating ?: return@locked
      val l = ledger()
      if (l.trial == s && l.running != shown) save(l.marked(shown))
    }
  }

  /** [this] with the "started, not yet healthy" marker set ([on]) or cleared. */
  private fun CrashLedger.marked(on: Boolean): CrashLedger =
    copy(running = on, pid = if (on) this@CrashGuard.pid else 0, markedAt = if (on) now() else 0)

  private fun save(ledger: CrashLedger, extra: Map<String, String?> = emptyMap()) {
    store.write(mapOf(ledgerKey to ledger.encode()) + extra)
  }

  /**
   * At a host start, before the lookup: a marker the last process left set means it ended while
   * the bundle on trial loaded, or while a screen showed it before it proved healthy. When the
   * system says how that process ended ([lastExitCrashed], asked for the pid that set the marker
   * and the time it did: Android 11+'s ApplicationExitInfo), only a crash counts: a process
   * stopped on purpose (a force-stop, say) does not. Null: it cannot say (iOS, older Android, no
   * record for that process), and the marker alone counts.
   */
  fun atStart(lastExitCrashed: (pid: Int, markedAtMillis: Long) -> Boolean? = { _, _ -> null }): CrashStep = store.locked {
    val l = ledger()
    when {
      !l.running -> CrashStep.None
      !enabled || l.trial <= 0 || lastExitCrashed(l.pid, l.markedAt) == false -> CrashStep.None.also { save(l.marked(false)) }
      else -> fail(l, l.trial, CrashKind.PROCESS)
    }
  }

  /**
   * The code of a verified manifest at [sequence] begins to evaluate (Zipline's first
   * moduleLoadStart, [CodeLoadProgress.moduleLoadStart]): a bundle newer than the healthy one
   * goes on trial, and, while a screen shows ([screens]), the marker is set (and written) before
   * its code runs. Not before: a load that never got that far (its downloads failed) is not the
   * bundle's.
   */
  fun loadStarting(sequence: Long?) {
    if (!enabled || sequence == null) return
    store.locked {
      val l = ledger()
      if (!mayGoOnTrial(l, sequence)) return@locked
      evaluating = sequence
      save((if (l.trial == sequence) l else l.copy(trial = sequence, failures = 0)).marked(shown))
    }
  }

  /**
   * A bundle newer than the healthy one, unless it is set aside; and the healthy one itself while
   * the host has gone back to it (something above it is set aside): it gets a trial of its own,
   * since what the set-aside bundle left behind (its data, migrated) or modules gone from the
   * server can make it fail too ([fail] then drops it).
   */
  private fun mayGoOnTrial(l: CrashLedger, sequence: Long): Boolean =
    (sequence > l.healthy && sequence != l.setAside) || (sequence == l.healthy && l.setAside > l.healthy)

  /** [sequence]'s code is no longer evaluating (it loaded, or the load failed). Under the store's lock. */
  private fun evaluated(sequence: Long) {
    if (evaluating == sequence) evaluating = null
  }

  /**
   * [sequence]'s code loaded. The marker is cleared until a screen shows it. [manifest], what
   * loaded from [manifestUrl] on the network (null from the cache), is kept as the bundle to go
   * back to once it proves healthy.
   */
  fun loaded(sequence: Long?, manifestUrl: String?, manifest: String?) {
    if (!enabled || sequence == null) return
    store.locked {
      evaluated(sequence)
      val l = ledger()
      if (l.trial != sequence) return@locked
      val kept = if (manifestUrl != null && manifest != null && manifest.length <= MAX_KEPT_MANIFEST_CHARS && '\n' !in manifestUrl) {
        mapOf(candidateKey to KeptBundle(sequence, manifestUrl, manifest).encode())
      } else {
        emptyMap()
      }
      save(l.marked(false), kept)
    }
  }

  /** Whether [sequence] is on trial: screens showing it start its crash window. */
  fun onTrial(sequence: Long?): Boolean = enabled && sequence != null && ledger().trial == sequence

  /** A screen shows [sequence] (on trial): its window is open, and the marker is set. */
  fun windowOpened(sequence: Long?) {
    if (!enabled || sequence == null) return
    store.locked {
      val l = ledger()
      if (l.trial == sequence && !l.running) save(l.marked(true))
    }
  }

  /** No screen shows [sequence] now: the marker is cleared (an app ended in the background is no failure). */
  fun windowClosed(sequence: Long?) {
    if (!enabled || sequence == null) return
    store.locked {
      val l = ledger()
      if (l.trial == sequence && l.running) save(l.marked(false))
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
      evaluated(sequence)
      val l = ledger()
      if (l.trial == sequence && l.running) save(l.marked(false))
    }
    return CrashStep.None
  }

  /** [sequence] failed ([kind]): counted when it is on trial, and set aside at the threshold. */
  fun failed(sequence: Long?, kind: CrashKind): CrashStep {
    if (!enabled || sequence == null) return CrashStep.None
    return store.locked {
      evaluated(sequence)
      val l = ledger()
      if (l.trial != sequence) CrashStep.None else fail(l, sequence, kind)
    }
  }

  private fun fail(l: CrashLedger, sequence: Long, kind: CrashKind): CrashStep {
    val failures = l.failures + 1
    if (failures < threshold) {
      save(l.copy(failures = failures).marked(false))
      return CrashStep.Counted(sequence, failures, kind)
    }
    if (sequence == l.healthy) {
      // The bundle the host went back to failed its own trial: there is nothing left to go back
      // to. Forgotten, with its kept manifest: the floor's own bundle stays set aside, so the host
      // shows the app's own view, and stops retrying, until a newer sequence is published.
      save(l.copy(trial = 0, failures = 0, healthy = 0).marked(false), mapOf(candidateKey to null, healthyKey to null))
      return CrashStep.SetAside(sequence, failures, kind)
    }
    save(
      l.copy(trial = 0, failures = 0, setAside = maxOf(l.setAside, sequence)).marked(false),
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
      // The healthy one passing its own trial again (after going back to it) keeps what was kept for it.
      val keep = if (kept == null && sequence == l.healthy) emptyMap() else mapOf(healthyKey to kept?.encode())
      save(
        l.copy(trial = 0, failures = 0, healthy = sequence).marked(false),
        mapOf(candidateKey to null) + keep,
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
