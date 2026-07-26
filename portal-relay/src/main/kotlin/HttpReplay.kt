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
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.LinkedHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_HTTP_BODY_BYTES = 1024 * 1024
private const val MAX_REPLAY_CURSORS = 4096
private const val REPLAY_CURSOR_TTL_MILLIS = 30 * 60 * 1000L
private val SAFE_REPLAY_NAME = Regex("[A-Za-z0-9._-]{1,128}")
internal val SENSITIVE_HTTP_HEADERS = setOf(
  "authorization",
  "proxy-authorization",
  "cookie",
  "set-cookie",
  "x-api-key",
  "x-auth-token",
)
private val SENSITIVE_KEYS = Regex(
  "(?:^|[_-])(token|secret|password|session|authorization|api[_-]?key|email|phone|account|card|ssn|dob)(?:$|[_-])",
  RegexOption.IGNORE_CASE,
)

internal fun isSensitiveHttpKey(key: String, additionalKeys: Set<String> = emptySet()): Boolean =
  key.lowercase() in additionalKeys || SENSITIVE_KEYS.containsMatchIn(key)

internal fun redactSensitiveJson(
  body: String,
  additionalKeys: Set<String> = emptySet(),
): String {
  val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return body

  fun redact(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(
      value.mapValues { (key, child) ->
        if (isSensitiveHttpKey(key, additionalKeys)) JsonPrimitive("<redacted>") else redact(child)
      },
    )
    is JsonArray -> JsonArray(value.map(::redact))
    is JsonPrimitive -> value
  }

  return Json.encodeToString(JsonElement.serializer(), redact(element))
}

@Serializable
internal data class HttpFixtureSetFile(
  val formatVersion: Int,
  val recordedAt: String,
  val expiresAt: String? = null,
  val matchHeaders: List<String> = listOf("accept", "content-type"),
  val entries: List<HttpFixtureEntry>,
)

@Serializable
internal data class HttpFixtureEntry(
  val request: HostHttpRequest,
  val response: HostHttpResponse,
  val reuse: Boolean = false,
)

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
  val entriesByKey: Map<String, List<HttpFixtureEntry>>,
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

    val requestKey = canonicalRequest(request, loaded.file.matchHeaders)
    val candidates = loaded.entriesByKey[requestKey]
      ?: return failure(fixtureSet, request, 424, "no matching request")
    val cursorKey = ReplayCursorKey(fixtureSet, loaded.revision, session, requestKey)
    val now = clock.millis()
    val index = synchronized(cursors) {
      evictOldCursors(now)
      cursors[cursorKey]?.index ?: 0
    }
    val entry = candidates.getOrNull(index)
      ?: candidates.lastOrNull()?.takeIf { it.reuse }
      ?: return failure(fixtureSet, request, 424, "matching response sequence exhausted")

    synchronized(cursors) {
      if (!entry.reuse) {
        cursors[cursorKey] = ReplayCursor(index + 1, now)
      } else {
        cursors[cursorKey] = ReplayCursor(index, now)
      }
      trimCursors()
    }
    return HttpReplayResult.Match(entry.response)
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
    val expiresAt = validateFixture(fixture)
    val matchHeaders = fixture.matchHeaders.map { it.lowercase() }
    val entriesByKey = fixture.entries.groupBy { canonicalRequest(it.request, matchHeaders) }
    return LoadedFixtureSet(
      id = id,
      revision = revision(bytes),
      file = fixture.copy(matchHeaders = matchHeaders),
      expiresAt = expiresAt,
      entriesByKey = entriesByKey,
    )
  }

  private fun validateFixture(fixture: HttpFixtureSetFile): Instant? {
    require(fixture.formatVersion == 1) { "unsupported formatVersion ${fixture.formatVersion}" }
    Instant.parse(fixture.recordedAt)
    val expiresAt = fixture.expiresAt?.let(Instant::parse)
    require(fixture.entries.isNotEmpty()) { "fixture set has no entries" }

    val matchHeaders = fixture.matchHeaders.map { it.lowercase() }
    require(matchHeaders.distinct().size == matchHeaders.size) { "matchHeaders must be unique" }
    require(matchHeaders.none { it in SENSITIVE_HTTP_HEADERS }) {
      "sensitive headers cannot participate in matching"
    }
    fixture.entries.forEach(::validateEntry)
    fixture.entries.groupBy { canonicalRequest(it.request, matchHeaders) }
      .forEach { (_, entries) ->
        require(entries.dropLast(1).none { it.reuse }) {
          "reuse=true is allowed only on the final duplicate request"
        }
      }
    return expiresAt
  }

  private fun validateEntry(entry: HttpFixtureEntry) {
    val request = entry.request
    require(request.path.startsWith('/') && "://" !in request.path) {
      "request path must be relative: ${request.path}"
    }
    validateHeaders(request.headers, "request")
    validateHeaders(entry.response.headers, "response")
    request.query.forEach { (key, value) ->
      require(!isSensitiveHttpKey(key) || value == "<redacted>") {
        "sensitive query key '$key' must be redacted"
      }
    }
    request.body?.let { validateBody(it, "request") }
    validateBody(entry.response.body, "response")
  }

  private fun validateHeaders(headers: Map<String, String>, scope: String) {
    val sensitive = headers.keys.firstOrNull { it.lowercase() in SENSITIVE_HTTP_HEADERS }
    require(sensitive == null) { "$scope contains forbidden header '$sensitive'" }
  }

  private fun validateBody(body: String, scope: String) {
    require(body.encodeToByteArray().size <= MAX_HTTP_BODY_BYTES) {
      "$scope body exceeds 1 MiB"
    }
    require(body.none { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' }) {
      "$scope body contains unsupported control characters"
    }
    val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return
    requireNoSensitiveJson(element)
  }

  private fun requireNoSensitiveJson(element: JsonElement) {
    when (element) {
      is JsonObject -> element.forEach { (key, value) ->
        if (isSensitiveHttpKey(key)) {
          require(value is JsonPrimitive && value.jsonPrimitive.content == "<redacted>") {
            "sensitive JSON key '$key' must be redacted"
          }
        }
        requireNoSensitiveJson(value)
      }
      is JsonArray -> element.forEach(::requireNoSensitiveJson)
      is JsonPrimitive -> Unit
    }
  }

  private fun canonicalRequest(request: HostHttpRequest, matchHeaders: List<String>): String {
    val headers = request.headers.entries.associate { it.key.lowercase() to it.value }
    val query = request.query.mapValues { (key, value) ->
      if (isSensitiveHttpKey(key)) "<redacted>" else value
    }
    val body = request.body?.let(::redactSensitiveJson).orEmpty()
    return buildString {
      append(request.method.uppercase())
      append('\n')
      append(request.path)
      append('\n')
      append(query.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" })
      append('\n')
      append(matchHeaders.sorted().joinToString("&") { "$it=${headers[it].orEmpty()}" })
      append('\n')
      append(body)
    }
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
