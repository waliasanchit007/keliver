/*
 * How a production host starts (docs/DEVICE_HOST.md, "Start policy"). The SAME file is in the
 * Android and the iOS host; keep them identical. Pure Kotlin: the decision is here, the hosts only
 * act on it.
 *
 * - NETWORK_FIRST: the lookup, then the manifest, from the network, before anything shows; the
 *   cache only when the lookup fails. The default.
 * - PARALLEL: the cached release starts loading at once, in a TreehouseApp of its own, while the
 *   lookup runs, and shows only once the lookup names that same manifest, or fails (when
 *   NETWORK_FIRST would show the cache too). When it names another, that app is closed and the named
 *   release loads from the network in a new one. So at start-up it never shows older code than
 *   NETWORK_FIRST would. Its guest code does start during the lookup (its side effects, a HostHttp
 *   call say, can happen even when it is then dropped); nothing of it shows, and nothing it does
 *   counts toward crash rollback ([LoadFence]).
 * - CACHE_FIRST: the cached release shows at once; the lookup runs in the background, and a newer
 *   release it finds makes the NEXT start NETWORK_FIRST.
 *
 * After a start from the cache under either, an update (on resume, live, checkForUpdate) loads in a
 * new TreehouseApp and is swapped in once it has loaded (Zipline takes no further manifest into an
 * app whose first load came from its cache); the running one stays when it fails.
 *
 * None of them changes what may run: every cached start goes through the host's cached-bundle
 * checker (the key, the rollback floor, set-aside sequences) and every network load through its
 * pinning client. A start that crash rollback has a say in is always NETWORK_FIRST.
 */
package @@PACKAGE@@

import kotlin.concurrent.Volatile

/** How a production host starts; [wireName] is how gradle.properties, HostConfig.kt and the index spell it. */
enum class KeliverStartPolicy(val wireName: String) {
  NETWORK_FIRST("network-first"),
  PARALLEL("parallel"),
  CACHE_FIRST("cache-first"),
  ;

  companion object {
    /** The policy [value] names, or null (empty, unknown, or not a string the hosts know). */
    fun parse(value: String?): KeliverStartPolicy? = entries.firstOrNull { it.wireName == value?.trim() }
  }
}

/** The policy when the app sets none: switch the default here, in one line. */
val DEFAULT_START_POLICY: KeliverStartPolicy = KeliverStartPolicy.NETWORK_FIRST

/** The index's key for the per-channel override: `"startPolicy": {"<channel>": "<policy>"}`. */
internal const val START_POLICY_INDEX_KEY = "startPolicy"

/**
 * Whether the app lets the index's override change its start policy: keliver.startPolicyOverride
 * (Android: gradle.properties; iOS: HostConfig.kt's START_POLICY_OVERRIDE), `any` (the default) or
 * `none`. True for `any`, false for `none`, null for anything else (the builds refuse it).
 */
internal fun parseStartPolicyOverride(value: String?): Boolean? = when (value?.trim()) {
  null, "", "any" -> true
  "none" -> false
  else -> null
}

/** The policy in force at a start, and where it came from. */
internal data class EffectiveStartPolicy(val policy: KeliverStartPolicy, val source: String)

/**
 * The app's [setting] (null: [DEFAULT_START_POLICY]), unless the index's override for this
 * channel, kept from the last index read ([storedOverride]), names a policy and the app allows
 * overrides ([overrideAllowed]: keliver.startPolicyOverride). An unknown override means the app's
 * own setting.
 */
internal fun effectiveStartPolicy(setting: KeliverStartPolicy?, storedOverride: String?, overrideAllowed: Boolean = true): EffectiveStartPolicy {
  val override = KeliverStartPolicy.parse(storedOverride)
  if (override != null && overrideAllowed) return EffectiveStartPolicy(override, "the release owner's override in the index")
  val ignored = if (override != null) "; the index's override ${override.wireName} is not allowed (startPolicyOverride none)" else ""
  if (setting != null) return EffectiveStartPolicy(setting, "the app's setting$ignored")
  return EffectiveStartPolicy(DEFAULT_START_POLICY, "the default$ignored")
}

/**
 * What a host knows before its lookup.
 * - [cached]: a release loaded from the network before, for this key and bundle server (its last
 *   good manifest URL).
 * - [crashRollback]: crash rollback has a say in this start (a bundle set aside at this start, or
 *   the floor's own bundle set aside: CrashGuard); [crashRollbackWhy] says which, for the log.
 * - [channel], [checkedChannel]: this host's channel, and the one its last recorded lookup was for
 *   (null: none recorded).
 * - [newestSeen]: the sequence that lookup named (null: none recorded); [floor]: the rollback
 *   floor, the newest sequence this install has run.
 */
internal data class StartFacts(
  val policy: EffectiveStartPolicy,
  val cached: Boolean,
  val crashRollback: Boolean,
  val channel: String,
  val checkedChannel: String?,
  val newestSeen: Long?,
  val floor: Long,
  val crashRollbackWhy: String? = null,
)

/**
 * Whether crash rollback (CrashRollback.kt) has a say in this start, so that it must decide as
 * NETWORK_FIRST does: a bundle was set aside at this start ([startStep]), or the floor's own
 * bundle is set aside (the host goes back to the kept healthy one, or to the app's own view).
 */
internal fun crashRollbackDecides(guard: CrashGuard, startStep: CrashStep, floor: Long): Boolean =
  crashRollbackReason(guard, startStep, floor) != null

/** Why crash rollback has a say in this start ([crashRollbackDecides]), for the start's log line; null: it has none. */
internal fun crashRollbackReason(guard: CrashGuard, startStep: CrashStep, floor: Long): String? = when {
  startStep is CrashStep.SetAside -> "crash rollback set sequence ${startStep.sequence} aside at this start"
  guard.floorSetAside(floor) -> "the floor's own release (sequence $floor) is set aside by crash rollback"
  guard.rollbackTo(floor) != null -> "crash rollback has a healthy release to go back to (sequence ${guard.setAside()} is set aside)"
  else -> null
}

/** What this start does ([mode]), and why, for the start's log line. */
internal data class StartChoice(val mode: KeliverStartPolicy, val reason: String) {
  /** The `start policy:` log line. */
  fun logLine(policy: EffectiveStartPolicy): String =
    "start policy: ${policy.policy.wireName} (${policy.source}); this start: ${mode.wireName}, $reason"
}

/** What this start does, from [facts]. */
internal fun chooseStart(facts: StartFacts): StartChoice {
  val policy = facts.policy.policy
  val nf = KeliverStartPolicy.NETWORK_FIRST
  if (policy == nf) return StartChoice(nf, "the lookup first")
  if (!facts.cached) return StartChoice(nf, "nothing is cached for this key and bundle server")
  if (facts.crashRollback) return StartChoice(nf, facts.crashRollbackWhy ?: "crash rollback has a say in this start")
  val sameChannel = facts.checkedChannel == facts.channel
  if (sameChannel && facts.newestSeen != null && facts.newestSeen > facts.floor) {
    return StartChoice(nf, "the last lookup found sequence ${facts.newestSeen}, newer than ${facts.floor}, the newest run here")
  }
  return when (policy) {
    KeliverStartPolicy.PARALLEL -> StartChoice(policy, "the cached release loads while the lookup runs; it shows once the lookup confirms it or fails")
    else -> if (!sameChannel) {
      StartChoice(nf, "no lookup recorded for channel ${facts.channel} yet")
    } else {
      StartChoice(policy, "the cached release shows now; the lookup runs in the background")
    }
  }
}

/** A PARALLEL start's next step, once its lookup is done. */
internal enum class ParallelStep {
  /** Show the release already loading from the cache. */
  SHOW_CACHED,

  /** Close that load's app (nothing of it shows) and load the release the lookup named from the network, in a new one. */
  LOAD_NAMED,
}

/**
 * A PARALLEL start, after the lookup: [namedUrl] and [namedSha256] are the manifest it named
 * (null: it failed, timed out or found nothing compatible), [cachedUrl] the cached release's.
 * Every published sequence has its own manifest URL (keliver-publish writes each to `v<N>/`), so
 * the same URL from the index (with its sha256) is the same release. The relay's legacy lookup
 * gives no sha256, so it confirms nothing.
 */
internal fun afterParallelLookup(namedUrl: String?, namedSha256: String?, cachedUrl: String): Pair<ParallelStep, String> = when {
  namedUrl == null -> ParallelStep.SHOW_CACHED to "the lookup failed: the cached release, as network-first would show"
  namedSha256 == null -> ParallelStep.LOAD_NAMED to "the relay's legacy lookup cannot confirm the cached release: loading $namedUrl"
  namedUrl == cachedUrl -> ParallelStep.SHOW_CACHED to "the lookup confirms the cached release"
  else -> ParallelStep.LOAD_NAMED to "the lookup names $namedUrl: loading it from the network; the cached load is dropped (not shown)"
}

/**
 * One TreehouseApp's code loads, as the host sees them (a PARALLEL start's cached load, and an
 * update after a start from the cache, each load in an app of their own):
 * - LIVE: the host's app; its events act at once.
 * - HELD: not shown yet. Its events that touch the host's records (crash rollback's ledger, the
 *   load's outcome) are kept, in order, and act when it [goLive]s, or never, when it is [drop]ped.
 * - DROPPED: closed by the host; its events, late ones from Zipline's thread too, change nothing.
 *
 * [post] runs a block on the main thread, after what is already queued there. [run] is called on
 * Zipline's thread or the main thread; [goLive] and [drop] on the main thread only.
 */
internal class LoadFence(held: Boolean, private val post: (() -> Unit) -> Unit) {
  enum class Mode { LIVE, HELD, DROPPED }

  @Volatile var mode: Mode = if (held) Mode.HELD else Mode.LIVE
    private set
  private val kept = ArrayList<() -> Unit>() // main thread only

  /** Not dropped: the host's app, or one that may become it. */
  val live: Boolean get() = mode != Mode.DROPPED

  /** [block] now (LIVE), kept until [goLive] (HELD), or never (DROPPED). */
  fun run(block: () -> Unit) {
    when (mode) {
      Mode.LIVE -> block()
      Mode.DROPPED -> Unit
      // Kept on the main thread, in order; the mode may have changed by the time it gets there.
      Mode.HELD -> post {
        when (mode) {
          Mode.LIVE -> block()
          Mode.HELD -> kept += block
          Mode.DROPPED -> Unit
        }
      }
    }
  }

  /** The app is the host's now: what it kept acts, in the order it happened. */
  fun goLive() {
    if (mode != Mode.HELD) return
    mode = Mode.LIVE
    val blocks = kept.toList()
    kept.clear()
    blocks.forEach { it() }
  }

  /** The app is closed: nothing it kept, and nothing it does from now on, acts. */
  fun drop() {
    mode = Mode.DROPPED
    kept.clear()
  }
}

/**
 * A held first code load: a PARALLEL start's, until its lookup is done, or an update's loading in a
 * new app, until it has loaded. While [active], the load's outcome (its success or its failure, the
 * last one) is kept instead of acted on; [release] then acts on it, [drop] forgets it. A hold made
 * with [onKept] is told of each outcome it keeps ([onOutcome]'s `loaded`). Main thread only.
 */
internal class StartHold(active: Boolean, private val onKept: ((loaded: Boolean) -> Unit)? = null) {
  var active: Boolean = active
    private set
  private var outcome: (() -> Unit)? = null

  /** [action] now, or, while held, when [release]d (in place of an earlier outcome); [loaded]: a success. */
  fun onOutcome(loaded: Boolean, action: () -> Unit) {
    if (!active) return action()
    outcome = action
    onKept?.invoke(loaded)
  }

  /** Stops holding and acts on the kept outcome, if any. Returns whether there was one. */
  fun release(): Boolean {
    active = false
    val o = outcome ?: return false
    outcome = null
    o()
    return true
  }

  /** Stops holding and forgets the kept outcome. */
  fun drop() {
    active = false
    outcome = null
  }
}
