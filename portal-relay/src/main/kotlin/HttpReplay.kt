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
import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.capabilities.HostHttpResponse
import dev.keliver.http.fixtures.HttpFixtureMatcher
import dev.keliver.http.fixtures.HttpFixtureRedaction
import dev.keliver.http.fixtures.HttpFixtureSelection
import dev.keliver.http.fixtures.HttpFixtureSet
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.LinkedHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_HTTP_BODY_BYTES = HttpFixtureMatcher.MAX_BODY_BYTES
private const val MAX_REPLAY_CURSORS = 4096
private const val REPLAY_CURSOR_TTL_MILLIS = 30 * 60 * 1000L
private val SAFE_REPLAY_NAME = Regex("[A-Za-z0-9._-]{1,128}")

// The fixture model, redaction and matching rules live in keliver-http-fixtures so the
// hosts' debug replay can't drift from preview. The relay keeps files, dates and cursors.
internal typealias HttpFixtureSetFile = HttpFixtureSet
internal typealias HttpFixtureEntry = dev.keliver.http.fixtures.HttpFixtureEntry

internal val SENSITIVE_HTTP_HEADERS: Set<String> = HttpFixtureRedaction.sensitiveHeaders

internal fun isSensitiveHttpKey(key: String, additionalKeys: Set<String> = emptySet()): Boolean =
  HttpFixtureRedaction.isSensitiveKey(key, additionalKeys)

internal fun redactSensitiveJson(
  body: String,
  additionalKeys: Set<String> = emptySet(),
): String = HttpFixtureRedaction.redactJson(body, additionalKeys)

@Serializable
internal data class HttpFixtureDescriptor(
  val id: String,
  val revision: String?,
  val entries: Int,
  val expiresAt: String?,
  val expired: Boolean,
  val valid: Boolean,
  val error: String? = null,
)

@Serializable
internal data class HttpReplayError(
  val error: String,
  val fixtureSet: String,
  val method: String,
  val path: String,
  val reason: String,
)

internal sealed interface HttpReplayResult {
  data class Match(val response: HostHttpResponse) : HttpReplayResult
  data class Failure(val status: Int, val error: HttpReplayError) : HttpReplayResult
}

private data class LoadedFixtureSet(
  val id: String,
  val revision: String,
  val file: HttpFixtureSetFile,
  val expiresAt: Instant?,
  val matcher: HttpFixtureMatcher,
)

private data class ReplayCursorKey(
  val fixtureSet: String,
  val revision: String,
  val session: String,
  val requestKey: String,
)

private data class ReplayCursor(
  var index: Int,
  var lastAccessMillis: Long,
)

/**
 * Read-only deterministic HTTP replay owned by the app repository.
 *
 * Loading on each request intentionally makes fixture edits visible without a
 * second watcher. Revision is part of the cursor key, so a changed file starts
 * a fresh sequence.
 */
internal class HttpReplayService(
  repoDir: File,
  config: PortalConfig,
  private val clock: Clock = Clock.systemUTC(),
) {
  private val fixturesDir = config.resolvedHttpFixturesDir(repoDir)
  private val json = Json {
    encodeDefaults = true
    explicitNulls = true
  }
  private val cursors = LinkedHashMap<ReplayCursorKey, ReplayCursor>(16, 0.75f, true)

  fun catalog(): List<HttpFixtureDescriptor> {
    val files = fixturesDir.listFiles { file -> file.isFile && file.extension == "json" }
      ?.sortedBy { it.name }
      .orEmpty()
    return files.map { file ->
      val id = file.nameWithoutExtension
      runCatching {
        val loaded = load(id, file)
        HttpFixtureDescriptor(
          id = id,
          revision = loaded.revision,
          entries = loaded.file.entries.size,
          expiresAt = loaded.file.expiresAt,
          expired = loaded.expiresAt?.let { !clock.instant().isBefore(it) } == true,
          valid = true,
        )
      }.getOrElse { error ->
        HttpFixtureDescriptor(
          id = id,
          revision = runCatching { revision(file.readBytes()) }.getOrNull(),
          entries = 0,
          expiresAt = null,
          expired = false,
          valid = false,
          error = error.message ?: "invalid fixture",
        )
      }
    }
  }

  fun catalogJson(): String = json.encodeToString(catalog())

  fun replay(
    fixtureSet: String,
    session: String,
    request: HostHttpRequest,
  ): HttpReplayResult {
    if (!SAFE_REPLAY_NAME.matches(fixtureSet)) {
      return failure(fixtureSet, request, 400, "invalid fixture-set name")
    }
    if (!SAFE_REPLAY_NAME.matches(session)) {
      return failure(fixtureSet, request, 400, "invalid replay session")
    }
    if ((request.body?.encodeToByteArray()?.size ?: 0) > MAX_HTTP_BODY_BYTES) {
      return failure(fixtureSet, request, 413, "request body exceeds 1 MiB")
    }

    val file = File(fixturesDir, "$fixtureSet.json")
    if (!file.isFile) {
      return failure(fixtureSet, request, 404, "fixture set not found")
    }
    val loaded = runCatching { load(fixtureSet, file) }.getOrElse { error ->
      return failure(fixtureSet, request, 422, error.message ?: "invalid fixture set")
    }
    if (loaded.expiresAt?.let { !clock.instant().isBefore(it) } == true) {
      return failure(fixtureSet, request, 410, "fixture set expired")
    }

    val requestKey = loaded.matcher.requestKey(request)
    val cursorKey = ReplayCursorKey(fixtureSet, loaded.revision, session, requestKey)
    val now = clock.millis()
    val index = synchronized(cursors) {
      evictOldCursors(now)
      cursors[cursorKey]?.index ?: 0
    }
    val match = when (val selection = loaded.matcher.select(request, index)) {
      is HttpFixtureSelection.Miss ->
        return failure(fixtureSet, request, HttpFixtureMatcher.MISS_STATUS, selection.reason)
      is HttpFixtureSelection.Match -> selection
    }

    synchronized(cursors) {
      cursors[cursorKey] = ReplayCursor(match.nextIndex, now)
      trimCursors()
    }
    return HttpReplayResult.Match(match.response)
  }

  fun responseJson(response: HostHttpResponse): String = json.encodeToString(response)

  fun errorJson(error: HttpReplayError): String = json.encodeToString(error)

  fun decodeRequest(body: String): HostHttpRequest = json.decodeFromString(body)

  fun validateCandidate(fixture: HttpFixtureSetFile) {
    validateFixture(fixture)
  }

  private fun load(id: String, file: File): LoadedFixtureSet {
    require(SAFE_REPLAY_NAME.matches(id)) { "invalid fixture-set filename: ${file.name}" }
    val bytes = file.readBytes()
    require(bytes.size <= MAX_HTTP_BODY_BYTES * 4) { "fixture file exceeds 4 MiB" }
    val fixture = json.decodeFromString<HttpFixtureSetFile>(bytes.decodeToString())
    val (expiresAt, matcher) = validateFixture(fixture)
    return LoadedFixtureSet(
      id = id,
      revision = revision(bytes),
      file = fixture,
      expiresAt = expiresAt,
      matcher = matcher,
    )
  }

  /**
   * Dates are the relay's (the shared module has no clock); every other rule, the
   * `formatVersion` check included, is the shared matcher's. It runs first, so a set
   * of an unsupported version is reported as that before its dates are read.
   */
  private fun validateFixture(fixture: HttpFixtureSetFile): Pair<Instant?, HttpFixtureMatcher> {
    val matcher = HttpFixtureMatcher(fixture)
    Instant.parse(fixture.recordedAt)
    val expiresAt = fixture.expiresAt?.let(Instant::parse)
    return expiresAt to matcher
  }

  private fun failure(
    fixtureSet: String,
    request: HostHttpRequest,
    status: Int,
    reason: String,
  ): HttpReplayResult.Failure = HttpReplayResult.Failure(
    status,
    HttpReplayError(
      error = "replay_miss",
      fixtureSet = fixtureSet,
      method = request.method.uppercase(),
      path = request.path,
      reason = reason,
    ),
  )

  private fun evictOldCursors(now: Long) {
    cursors.entries.removeIf { now - it.value.lastAccessMillis > REPLAY_CURSOR_TTL_MILLIS }
  }

  private fun trimCursors() {
    while (cursors.size > MAX_REPLAY_CURSORS) {
      val oldest = cursors.entries.iterator()
      if (!oldest.hasNext()) return
      oldest.next()
      oldest.remove()
    }
  }

  private fun revision(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .take(8)
      .joinToString("") { "%02x".format(it) }
}
