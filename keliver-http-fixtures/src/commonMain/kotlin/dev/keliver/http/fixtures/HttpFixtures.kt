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

import dev.keliver.capabilities.HostHttpRequest
import dev.keliver.capabilities.HostHttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The on-disk HTTP fixture set (`formatVersion` 1), as reviewed into an app repository. */
@Serializable
public data class HttpFixtureSet(
  val formatVersion: Int,
  /** ISO-8601 instant. Date rules are the caller's: this module has no clock. */
  val recordedAt: String,
  val expiresAt: String? = null,
  val matchHeaders: List<String> = listOf("accept", "content-type"),
  val entries: List<HttpFixtureEntry>,
)

/**
 * One recorded exchange. Entries with the same normalised request replay in file order;
 * `reuse = true` (only allowed on the last of them) repeats that response once the
 * sequence reaches it.
 */
@Serializable
public data class HttpFixtureEntry(
  val request: HostHttpRequest,
  val response: HostHttpResponse,
  val reuse: Boolean = false,
)

/** The outcome of [HttpFixtureMatcher.select]. */
public sealed interface HttpFixtureSelection {
  /** Serve [response]; the caller stores [nextIndex] as the cursor for this request key. */
  public data class Match(val response: HostHttpResponse, val nextIndex: Int) : HttpFixtureSelection

  /** No outbound call is ever made: answer with [HttpFixtureMatcher.MISS_STATUS] and [reason]. */
  public data class Miss(val reason: String) : HttpFixtureSelection
}

/**
 * The exact-match rules, identical wherever fixtures replay (relay preview or a debug host).
 *
 * A request matches on its normalised key: upper-cased method, path, query sorted by key,
 * the lower-cased [HttpFixtureSet.matchHeaders] only, and the body — with sensitive query
 * values and JSON keys redacted exactly as recording redacts them. Construction validates
 * the set (everything except its dates) and throws [IllegalArgumentException] on a bad one.
 *
 * Stateless: the caller owns cursors (per session, per [requestKey]) and passes the index.
 */
public class HttpFixtureMatcher(set: HttpFixtureSet) {
  private val matchHeaders: List<String> = set.matchHeaders.map { it.lowercase() }
  private val entriesByKey: Map<String, List<HttpFixtureEntry>>

  init {
    validate(set, matchHeaders)
    entriesByKey = set.entries.groupBy { requestKey(it.request) }
  }

  /** The normalised key two requests must share to match. */
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

  public companion object {
    /** The status a replay miss answers with ("424-style"). */
    public const val MISS_STATUS: Int = 424

    /** Largest request or response body a fixture may hold (1 MiB). */
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

  public fun isSensitiveKey(key: String, additionalKeys: Set<String> = emptySet()): Boolean =
    key.lowercase() in additionalKeys || sensitiveKeys.containsMatchIn(key)

  /** [body] with every sensitive JSON key's value replaced; non-JSON is returned unchanged. */
  public fun redactJson(body: String, additionalKeys: Set<String> = emptySet()): String {
    val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return body

    fun redact(value: JsonElement): JsonElement = when (value) {
      is JsonObject -> JsonObject(
        value.mapValues { (key, child) ->
          if (isSensitiveKey(key, additionalKeys)) JsonPrimitive(REDACTED) else redact(child)
        },
      )
      is JsonArray -> JsonArray(value.map(::redact))
      is JsonPrimitive -> value
    }

    return Json.encodeToString(JsonElement.serializer(), redact(element))
  }
}

private const val REDACTED = "<redacted>"

private fun HttpFixtureMatcher.validate(set: HttpFixtureSet, matchHeaders: List<String>) {
  require(set.formatVersion == 1) { "unsupported formatVersion ${set.formatVersion}" }
  require(set.entries.isNotEmpty()) { "fixture set has no entries" }
  require(matchHeaders.distinct().size == matchHeaders.size) { "matchHeaders must be unique" }
  require(matchHeaders.none { it in HttpFixtureRedaction.sensitiveHeaders }) {
    "sensitive headers cannot participate in matching"
  }
  set.entries.forEach(::validateEntry)
  set.entries.groupBy { requestKey(it.request) }.forEach { (_, entries) ->
    require(entries.dropLast(1).none { it.reuse }) {
      "reuse=true is allowed only on the final duplicate request"
    }
  }
}

private fun validateEntry(entry: HttpFixtureEntry) {
  val request = entry.request
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
  require(body.encodeToByteArray().size <= HttpFixtureMatcher.MAX_BODY_BYTES) {
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
