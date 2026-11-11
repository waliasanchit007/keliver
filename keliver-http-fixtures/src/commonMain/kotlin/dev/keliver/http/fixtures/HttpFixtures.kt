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
package dev.keliver.http.fixtures

import dev.drewhamilton.poko.Poko
import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.capabilities.HostHttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The public value types below are Poko classes, not data classes: they have value
// equality, hashCode and toString, but no `copy` or `componentN`. A later field can then
// be added with a default value, and the old constructor kept as a secondary one,
// without breaking binary compatibility (a data class's `copy` signature can't be kept).

/** The on-disk HTTP fixture set (`formatVersion` 1), as reviewed into an app repository. */
@Serializable
@Poko
public class HttpFixtureSet(
  public val formatVersion: Int,
  /**
   * ISO-8601 instant. Date rules are the caller's: this module has no clock, and
   * [HttpFixtureMatcher] neither parses [recordedAt] nor [expiresAt] nor refuses an
   * expired set.
   */
  public val recordedAt: String,
  /** ISO-8601 instant after which the caller should refuse the set; see [recordedAt]. */
  public val expiresAt: String? = null,
  /** Header names that take part in matching, compared lower-cased. */
  public val matchHeaders: List<String> = listOf("accept", "content-type"),
  public val entries: List<HttpFixtureEntry>,
)

/**
 * One recorded exchange. Entries with the same normalised request replay in file order;
 * `reuse = true` (only allowed on the last of them) repeats that response once the
 * sequence reaches it.
 */
@Serializable
@Poko
public class HttpFixtureEntry(
  public val request: HostHttpRequest,
  public val response: HostHttpResponse,
  public val reuse: Boolean = false,
)

/** The outcome of [HttpFixtureMatcher.select]. */
public sealed interface HttpFixtureSelection {
  /** Serve [response]; the caller stores [nextIndex] as the cursor for this request key. */
  @Poko
  public class Match(
    public val response: HostHttpResponse,
    public val nextIndex: Int,
  ) : HttpFixtureSelection

  /** No outbound call is ever made: answer with [HttpFixtureMatcher.MISS_STATUS] and [reason]. */
  @Poko
  public class Miss(public val reason: String) : HttpFixtureSelection
}

/**
 * The exact-match rules, identical wherever fixtures replay (relay preview or a debug host).
 *
 * A request matches on its normalised key: upper-cased method, path, query sorted by key,
 * the lower-cased [HttpFixtureSet.matchHeaders] only, and the body. Sensitive query
 * values and JSON keys are redacted exactly as recording redacts them, and a JSON body is
 * compared re-encoded (whitespace ignored, key order kept); any other body is compared
 * byte for byte and is never redacted.
 *
 * Construction validates the set and throws [IllegalArgumentException] on a bad one:
 * `formatVersion` 1, at least one entry, unique and non-sensitive match headers, relative
 * request paths, no [HttpFixtureRedaction.sensitiveHeaders] in a request or a response,
 * sensitive query values and JSON keys (at any depth) already redacted, bodies of at most
 * [MAX_BODY_BYTES] UTF-8 bytes without control characters other than tab, LF and CR, and
 * `reuse = true` only on the last of a request's duplicates. It does not check the dates.
 *
 * Stateless: the caller owns cursors (per session, per [requestKey]) and passes the index.
 */
public class HttpFixtureMatcher(set: HttpFixtureSet) {
  private val matchHeaders: List<String> = set.matchHeaders.map { it.lowercase() }
  private val entriesByKey: Map<String, List<HttpFixtureEntry>>

  init {
    validate(set)
    // Each entry's key is computed once, after its entry has passed validation.
    entriesByKey = set.entries.groupBy { requestKey(it.request) }
    entriesByKey.values.forEach { entries ->
      require(entries.dropLast(1).none { it.reuse }) {
        "reuse=true is allowed only on the final duplicate request"
      }
    }
  }

  /**
   * The key two requests must share to match, for keying the caller's cursors.
   *
   * Opaque: compare keys for equality and nothing else. Its content (today the normalised
   * request, redacted) is not part of the API and may change in any release, for example
   * to a digest, so don't parse it, show it or store it across versions.
   */
  public fun requestKey(request: HostHttpRequest): String {
    val headers = request.headers.entries.associate { it.key.lowercase() to it.value }
    val query = request.query.mapValues { (key, value) ->
      if (HttpFixtureRedaction.isSensitiveKey(key)) REDACTED else value
    }
    val body = request.body?.let { HttpFixtureRedaction.redactJson(it) }.orEmpty()
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

  /** The response at cursor [index] for [request]'s key, or a miss. */
  public fun select(request: HostHttpRequest, index: Int): HttpFixtureSelection {
    val candidates = entriesByKey[requestKey(request)]
      ?: return HttpFixtureSelection.Miss("no matching request")
    val entry = candidates.getOrNull(index)
      ?: candidates.lastOrNull()?.takeIf { it.reuse }
      ?: return HttpFixtureSelection.Miss("matching response sequence exhausted")
    return HttpFixtureSelection.Match(entry.response, if (entry.reuse) index else index + 1)
  }

  private fun validate(set: HttpFixtureSet) {
    require(set.formatVersion == 1) { "unsupported formatVersion ${set.formatVersion}" }
    require(set.entries.isNotEmpty()) { "fixture set has no entries" }
    require(matchHeaders.distinct().size == matchHeaders.size) { "matchHeaders must be unique" }
    require(matchHeaders.none { it in HttpFixtureRedaction.sensitiveHeaders }) {
      "sensitive headers cannot participate in matching"
    }
    set.entries.forEach(::validateEntry)
  }

  public companion object {
    /** The status a replay miss answers with ("424-style"). */
    public const val MISS_STATUS: Int = 424

    /** Largest request or response body a fixture may hold, in UTF-8 bytes (1 MiB). */
    public const val MAX_BODY_BYTES: Int = 1024 * 1024
  }
}

/** Redaction rules shared by recording and matching: a key matches if either side redacts it. */
public object HttpFixtureRedaction {
  /** Headers that never appear in a fixture and never participate in matching. */
  public val sensitiveHeaders: Set<String> = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "x-auth-token",
  )

  private val sensitiveKeys = Regex(
    "(?:^|[_-])(token|secret|password|session|authorization|api[_-]?key|email|phone|account|card|ssn|dob)(?:$|[_-])",
    RegexOption.IGNORE_CASE,
  )

  /**
   * True when [key] (a query key or a JSON object key) names a sensitive value: it
   * matches the built-in pattern, or it is one of [additionalKeys]. Both sides are
   * compared lower-cased, so `"Nickname"` matches `setOf("nickname")` and the reverse.
   */
  public fun isSensitiveKey(key: String, additionalKeys: Set<String> = emptySet()): Boolean =
    isSensitive(key, additionalKeys.lowercased())

  /**
   * [body] with every sensitive JSON key's value replaced, at any depth (inside nested
   * objects and arrays), and re-encoded without insignificant whitespace. Any other body
   * — one that isn't a JSON object or array, a bare JSON literal such as `42` or `OK`
   * included — is returned unchanged: it has no keys, so it is neither redacted nor
   * normalised, and it replays and matches byte for byte.
   */
  public fun redactJson(body: String, additionalKeys: Set<String> = emptySet()): String {
    val element = jsonContainer(body) ?: return body
    val additional = additionalKeys.lowercased()

    fun redact(value: JsonElement): JsonElement = when (value) {
      is JsonObject -> JsonObject(
        value.mapValues { (key, child) ->
          if (isSensitive(key, additional)) JsonPrimitive(REDACTED) else redact(child)
        },
      )
      is JsonArray -> JsonArray(value.map(::redact))
      is JsonPrimitive -> value
    }

    return Json.encodeToString(JsonElement.serializer(), redact(element))
  }

  private fun isSensitive(key: String, lowercasedAdditional: Set<String>): Boolean =
    key.lowercase() in lowercasedAdditional || sensitiveKeys.containsMatchIn(key)

  private fun Set<String>.lowercased(): Set<String> =
    if (all { it == it.lowercase() }) this else mapTo(HashSet()) { it.lowercase() }
}

private const val REDACTED = "<redacted>"

/**
 * [body] parsed, when it is a JSON object or array. Only a body whose first
 * non-whitespace character is `{` or `[` is parsed at all: kotlinx's tree parser also
 * reads any bare token without whitespace or JSON punctuation (`a=1&b=2`, `OK`) as a
 * literal, and a primitive is not-JSON here (re-encoding it would only drop its
 * surrounding whitespace, a recorded `OK\n` replaying as `OK`). Skipping the parse also
 * keeps large text and HTML bodies cheap.
 */
private fun jsonContainer(body: String): JsonElement? {
  val first = body.firstOrNull { !it.isWhitespace() }
  if (first != '{' && first != '[') return null
  return runCatching { Json.parseToJsonElement(body) }.getOrNull()
    ?.takeIf { it is JsonObject || it is JsonArray }
}

/**
 * [body]'s UTF-8 length, without allocating. A lone surrogate counts as U+FFFD's three
 * bytes, the most any platform's [String.encodeToByteArray] writes for it.
 */
private fun utf8Length(body: String): Int {
  var bytes = 0
  var i = 0
  while (i < body.length) {
    val c = body[i]
    bytes += when {
      c.code < 0x80 -> 1
      c.code < 0x800 -> 2
      c.isHighSurrogate() && i + 1 < body.length && body[i + 1].isLowSurrogate() -> {
        i++
        4
      }
      else -> 3
    }
    i++
  }
  return bytes
}

private fun validateEntry(entry: HttpFixtureEntry) {
  val request = entry.request
  // HostHttpRequest's own init refuses these paths first (also when a fixture file is
  // decoded); the fixture rule is kept here so it doesn't rest on that class alone.
  require(request.path.startsWith('/') && "://" !in request.path) {
    "request path must be relative: ${request.path}"
  }
  validateHeaders(request.headers, "request")
  validateHeaders(entry.response.headers, "response")
  request.query.forEach { (key, value) ->
    require(!HttpFixtureRedaction.isSensitiveKey(key) || value == REDACTED) {
      "sensitive query key '$key' must be redacted"
    }
  }
  request.body?.let { validateBody(it, "request") }
  validateBody(entry.response.body, "response")
}

private fun validateHeaders(headers: Map<String, String>, scope: String) {
  val sensitive = headers.keys.firstOrNull { it.lowercase() in HttpFixtureRedaction.sensitiveHeaders }
  require(sensitive == null) { "$scope contains forbidden header '$sensitive'" }
}

private fun validateBody(body: String, scope: String) {
  require(utf8Length(body) <=HttpFixtureMatcher.MAX_BODY_BYTES) {
    "$scope body exceeds 1 MiB"
  }
  require(body.none { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' }) {
    "$scope body contains unsupported control characters"
  }
  val element = jsonContainer(body) ?: return
  requireNoSensitiveJson(element)
}

private fun requireNoSensitiveJson(element: JsonElement) {
  when (element) {
    is JsonObject -> element.forEach { (key, value) ->
      if (HttpFixtureRedaction.isSensitiveKey(key)) {
        require(value is JsonPrimitive && value.content == REDACTED) {
          "sensitive JSON key '$key' must be redacted"
        }
      }
      requireNoSensitiveJson(value)
    }
    is JsonArray -> element.forEach(::requireNoSensitiveJson)
    is JsonPrimitive -> Unit
  }
}
