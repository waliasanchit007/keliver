/*
 * How a production host starts (docs/DEVICE_HOST.md, "Start policy"). The SAME file is in the
 * Android and the iOS host; keep them identical. Pure Kotlin: the decision is here, the hosts only
 * act on it.
 *
 * - NETWORK_FIRST: the lookup, then the manifest, from the network, before anything shows; the
 *   cache only when the lookup fails. The default.
 * - PARALLEL: the cached release starts loading at once, while the lookup runs, and shows only once
 *   the lookup names that same manifest, or fails (when NETWORK_FIRST would show the cache too).
 *   When it names another, the cached load is dropped unseen and that one loads from the network.
 *   So it never shows older code than NETWORK_FIRST would.
 * - CACHE_FIRST: the cached release shows at once; the lookup runs in the background, and a newer
 *   release it finds makes the NEXT start NETWORK_FIRST.
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

/** The policy in force at a start, and where it came from. */
internal data class EffectiveStartPolicy(val policy: KeliverStartPolicy, val source: String)

/**
 * The app's [setting] (null: [DEFAULT_START_POLICY]), unless the index's override for this
 * channel, kept from the last index read ([storedOverride]), names a policy. An unknown override
 * means the app's own setting.
 */
internal fun effectiveStartPolicy(setting: KeliverStartPolicy?, storedOverride: String?): EffectiveStartPolicy {
  KeliverStartPolicy.parse(storedOverride)?.let { return EffectiveStartPolicy(it, "the release owner's override in the index") }
  if (setting != null) return EffectiveStartPolicy(setting, "the app's setting")
  return EffectiveStartPolicy(DEFAULT_START_POLICY, "the default")
}

/**
 * What a host knows before its lookup.
 * - [cached]: a release loaded from the network before, for this key and bundle server (its last
 *   good manifest URL).
 * - [crashRollback]: crash rollback has a say in this start (a bundle set aside at this start, or
 *   the floor's own bundle set aside: CrashGuard).
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
)

/**
 * Whether crash rollback (CrashRollback.kt) has a say in this start, so that it must decide as
 * NETWORK_FIRST does: a bundle was set aside at this start ([startStep]), or the floor's own
 * bundle is set aside (the host goes back to the kept healthy one, or to the app's own view).
 */
internal fun crashRollbackDecides(guard: CrashGuard, startStep: CrashStep, floor: Long): Boolean =
  startStep is CrashStep.SetAside || guard.floorSetAside(floor) || guard.rollbackTo(floor) != null

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
  if (facts.crashRollback) return StartChoice(nf, "crash rollback decides this start")
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

  /** Drop that load, unseen, and load the release the lookup named from the network. */
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
  else -> ParallelStep.LOAD_NAMED to "the lookup names $namedUrl: loading it from the network; the cached load is dropped, unseen"
}

/**
 * Which code load is the host's: each load's events carry the generation it began in, and a
 * PARALLEL start that replaces its cached load moves to the [next] one, so that load's late events
 * change nothing. Written on the main thread, read from Zipline's.
 */
internal class LoadGeneration {
  @Volatile var current: Int = 0
    private set

  fun next() {
    current += 1
  }
}

/**
 * A PARALLEL start's first code load, held until its lookup is done. While [active], the load's
 * outcome (its success or its failure, the last one) is kept instead of acted on; [release] then
 * acts on it, [drop] forgets it. Main thread only.
 */
internal class StartHold(active: Boolean) {
  var active: Boolean = active
    private set
  private var outcome: (() -> Unit)? = null

  /** [action] now, or, while held, when [release]d (in place of an earlier outcome). */
  fun onOutcome(action: () -> Unit) {
    if (active) outcome = action else action()
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
