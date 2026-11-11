/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.http.replay.debug

import dev.drewhamilton.poko.Poko
import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import dev.keliver.http.fixtures.HttpFixtureMatcher
import dev.keliver.http.fixtures.HttpFixtureSelection
import dev.keliver.http.fixtures.HttpFixtureSet
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DEBUG BUILDS ONLY: reviewed HTTP fixture sets, embedded in a debug host and replayed
 * in-process, so a debug device answers a guest's `HostHttp` exactly as the relay's preview
 * replay does, with no relay, no token and no tunnel.
 *
 * A host adds this artifact with `debugImplementation` (Android) or a Debug-only dependency
 * (iOS), so a release build cannot compile a reference to it; docs/DEBUG_HTTP_REPLAY.md has
 * the wiring and the release-build self-test that proves it absent.
 *
 * [fixtureFiles] maps each fixture set's id (its file name in the app's `httpFixturesDir`,
 * without `.json`) to the file's text. Each set is loaded and validated once, here, by the
 * relay's rules: the shared ones in [HttpFixtureMatcher], plus the name, the 4 MiB file limit
 * and the dates. A set that fails is kept as invalid, and every request against it is
 * answered 422 with the reason, as the relay answers it; [catalog] lists them.
 *
 * Nothing here can reach the network: there is no HTTP client, no base URL and no fallback.
 * A request that no fixture answers gets a 424-style miss.
 *
 * [now] decides expiry (`expiresAt`), as the relay's clock does.
 */
public class DebugHttpReplay(
  fixtureFiles: Map<String, String>,
  private val now: () -> Instant = { Clock.System.now() },
) {
  private val sets: Map<String, Result<LoadedSet>> = fixtureFiles.entries
    // The relay lists sets by file name, so `a-b.json` comes before `a.json`.
    .sortedBy { "${it.key}.json" }
    .associate { (id, text) -> id to runCatching { load(id, text) } }

  /** Every fixture set given, by id: whether it loaded, and if not, why. */
  public fun catalog(): List<DebugHttpReplaySetInfo> = sets.map { (id, loaded) ->
    loaded.fold(
      onSuccess = { set ->
        DebugHttpReplaySetInfo(
          id = id,
          valid = true,
          entries = set.entries,
          expiresAt = set.expiresAtText,
          expired = set.isExpired(),
        )
      },
      onFailure = { error ->
        DebugHttpReplaySetInfo(
          id = id,
          valid = false,
          entries = 0,
          expiresAt = null,
          expired = false,
          error = error.message ?: "invalid fixture",
        )
      },
    )
  }

  /**
   * A [HostHttpProvider] that replays [fixtureSet] in a session of its own: each provider keeps
   * its own sequence cursors, as each relay replay session does. Bind it where the host binds
   * its `HostHttp`. [onMiss] is told about every answer that is not a recorded response (the
   * 424 miss and the 4xx failures), in the order they are answered and never two at once. It
   * should only record (a log line, a list); an exception it throws is dropped.
   *
   * [onMiss] runs while the provider holds its session lock, which is not reentrant: it must
   * not call back into the same provider ([DebugHttpReplayProvider.startSession] or
   * [DebugHttpReplayProvider.execute], for example through `runBlocking`), or it deadlocks.
   */
  public fun provider(
    fixtureSet: String,
    onMiss: (DebugHttpReplayMiss) -> Unit = {},
  ): DebugHttpReplayProvider = DebugHttpReplayProvider(this, fixtureSet, onMiss)

  /** One request in one session; the caller holds the session's lock around it. */
  internal fun replay(
    fixtureSet: String,
    cursors: MutableMap<String, Int>,
    request: HttpRequest,
  ): ReplayOutcome {
    // The relay's order: the request, the set's name, the body's size, the set, its expiry.
    val hostRequest = runCatching {
      HostHttpRequest(
        method = request.method,
        path = request.path,
        query = request.query,
        headers = request.headers,
        body = request.body,
      )
    }.getOrElse { return failure(fixtureSet, request, 400, "invalid request") }
    if (!SAFE_NAME.matches(fixtureSet)) {
      return failure(fixtureSet, request, 400, "invalid fixture-set name")
    }
    if ((request.body?.encodeToByteArray()?.size ?: 0) > HttpFixtureMatcher.MAX_BODY_BYTES) {
      return failure(fixtureSet, request, 413, "request body exceeds 1 MiB")
    }
    val loaded = sets[fixtureSet]
      ?: return failure(fixtureSet, request, 404, "fixture set not found")
    val set = loaded.getOrElse { error ->
      return failure(fixtureSet, request, 422, error.message ?: "invalid fixture set")
    }
    if (set.isExpired()) {
      return failure(fixtureSet, request, 410, "fixture set expired")
    }

    val requestKey = set.matcher.requestKey(hostRequest)
    return when (val selection = set.matcher.select(hostRequest, cursors[requestKey] ?: 0)) {
      is HttpFixtureSelection.Miss ->
        failure(fixtureSet, request, HttpFixtureMatcher.MISS_STATUS, selection.reason)
      is HttpFixtureSelection.Match -> {
        cursors[requestKey] = selection.nextIndex
        val response = selection.response
        ReplayOutcome.Match(HttpResponse(response.status, response.body, response.headers))
      }
    }
  }

  private fun LoadedSet.isExpired(): Boolean = expiresAt != null && now() >= expiresAt

  private fun failure(
    fixtureSet: String,
    request: HttpRequest,
    status: Int,
    reason: String,
  ): ReplayOutcome.Failure {
    val miss = DebugHttpReplayMiss(
      status = status,
      fixtureSet = fixtureSet,
      method = request.method.uppercase(),
      path = request.path,
      reason = reason,
    )
    val body = JSON.encodeToString(
      ReplayErrorBody.serializer(),
      ReplayErrorBody(
        error = "replay_miss",
        fixtureSet = miss.fixtureSet,
        method = miss.method,
        path = miss.path,
        reason = miss.reason,
      ),
    )
    return ReplayOutcome.Failure(miss, HttpResponse(status, body, mapOf("content-type" to "application/json")))
  }
}

/**
 * DEBUG BUILDS ONLY: the `HostHttp` a debug host binds when it replays fixtures (see
 * [DebugHttpReplay.provider]). Every request is answered from the session's fixture set: the
 * recorded response, unchanged, or a 424-style miss. It never makes an outbound call.
 *
 * Sequences follow the relay: requests with the same normalised key replay their recorded
 * responses in file order, then miss with "matching response sequence exhausted", unless the
 * last of them is `reuse`, which then repeats. Cursors belong to this provider's session;
 * [startSession] begins a new one. Requests are answered one at a time, so two identical
 * requests in flight never get the same sequence position.
 */
public class DebugHttpReplayProvider internal constructor(
  private val replay: DebugHttpReplay,
  fixtureSet: String,
  private val onMiss: (DebugHttpReplayMiss) -> Unit,
) : HostHttpProvider {
  private val lock = Mutex()

  /** Each matched request key's next sequence position. Guarded by [lock]. */
  private val cursors = mutableMapOf<String, Int>()

  /** The fixture set this session replays. */
  @Volatile
  public var fixtureSet: String = fixtureSet
    private set

  /**
   * Begins a new session on [fixtureSet] (null: the current one): every sequence starts again
   * from its first entry. For a host that shows one named state after another through the
   * same binding.
   */
  public suspend fun startSession(fixtureSet: String? = null) {
    lock.withLock {
      if (fixtureSet != null) this.fixtureSet = fixtureSet
      cursors.clear()
    }
  }

  override suspend fun execute(request: HttpRequest): HttpResponse = lock.withLock {
    when (val outcome = replay.replay(fixtureSet, cursors, request)) {
      is ReplayOutcome.Match -> outcome.response
      is ReplayOutcome.Failure -> {
        // In the order answered, one at a time; a callback's failure never changes the answer.
        runCatching { onMiss(outcome.miss) }
        outcome.response
      }
    }
  }
}

/**
 * An answer that is not a recorded response. [status] is 424 for a miss (no matching request,
 * or its sequence exhausted), 400 for an invalid request or set name, 404 for an unknown set,
 * 410 for an expired one, 413 for a request body over 1 MiB and 422 for an invalid set: the
 * relay's statuses. The response's body is the relay's error JSON with these fields.
 */
@Poko
public class DebugHttpReplayMiss(
  public val status: Int,
  public val fixtureSet: String,
  public val method: String,
  public val path: String,
  public val reason: String,
)

/** One fixture set in [DebugHttpReplay.catalog]. [expired] is as of the call. */
@Poko
public class DebugHttpReplaySetInfo(
  public val id: String,
  public val valid: Boolean,
  public val entries: Int,
  public val expiresAt: String?,
  public val expired: Boolean,
  public val error: String? = null,
)

internal sealed interface ReplayOutcome {
  class Match(val response: HttpResponse) : ReplayOutcome
  class Failure(val miss: DebugHttpReplayMiss, val response: HttpResponse) : ReplayOutcome
}

/** The relay's `HttpReplayError`, field for field, so a miss reads the same on a device. */
@Serializable
private class ReplayErrorBody(
  val error: String,
  val fixtureSet: String,
  val method: String,
  val path: String,
  val reason: String,
)

private class LoadedSet(
  val entries: Int,
  val expiresAtText: String?,
  val expiresAt: Instant?,
  val matcher: HttpFixtureMatcher,
)

/** The relay's fixture-set and session names. */
private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,128}")

/** The relay's file limit: four bodies' worth. */
private const val MAX_FILE_BYTES = HttpFixtureMatcher.MAX_BODY_BYTES * 4

/** The relay's fixture JSON settings (unknown keys are an error). */
private val JSON = Json {
  encodeDefaults = true
  explicitNulls = true
}

/** The relay's `load` and `validateFixture`, for text instead of a file. */
private fun load(id: String, text: String): LoadedSet {
  require(SAFE_NAME.matches(id)) { "invalid fixture-set filename: $id.json" }
  require(text.encodeToByteArray().size <= MAX_FILE_BYTES) { "fixture file exceeds 4 MiB" }
  val set = JSON.decodeFromString(HttpFixtureSet.serializer(), text)
  require(set.formatVersion == 1) { "unsupported formatVersion ${set.formatVersion}" }
  Instant.parse(set.recordedAt)
  val expiresAt = set.expiresAt?.let(Instant::parse)
  return LoadedSet(
    entries = set.entries.size,
    expiresAtText = set.expiresAt,
    expiresAt = expiresAt,
    matcher = HttpFixtureMatcher(set),
  )
}
