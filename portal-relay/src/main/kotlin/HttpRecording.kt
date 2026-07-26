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
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private const val MAX_RECORD_BODY_BYTES = 1024 * 1024
private const val MAX_RECORD_HEADER_BYTES = 32 * 1024
private const val MAX_RECORD_SESSIONS = 32
private const val RECORD_SESSION_TTL_MILLIS = 15 * 60 * 1000L
private val SAFE_RECORD_NAME = Regex("[A-Za-z0-9._-]{1,128}")
private val ENCODED_PATH_SEPARATOR = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)
private val HOP_BY_HOP_HEADERS = setOf(
  "connection",
  "content-length",
  "host",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
)
private val DEFAULT_RECORD_SENSITIVE_KEYS = setOf(
  "email",
  "phone",
  "account",
  "card",
  "ssn",
  "dob",
)

@Serializable
internal data class HttpRecordSessionCommand(
  val upstream: String,
  val fixtureSet: String,
)

@Serializable
internal data class HttpRecordSessionCreated(
  val session: String,
  val candidate: String,
)

@Serializable
internal data class HttpRecordClosed(
  val candidate: String,
  val entries: Int,
  val revision: String,
  val expiresAt: String,
  val reviewedFixtureExists: Boolean,
)

@Serializable
internal data class HttpRecordError(
  val error: String,
  val reason: String,
)

internal sealed interface HttpRecordingResult {
  data class Success(val status: Int, val body: String) : HttpRecordingResult
  data class Failure(val status: Int, val error: HttpRecordError) : HttpRecordingResult
}

internal fun interface RecordingDnsResolver {
  fun resolve(hostname: String): List<InetAddress>
}

internal fun interface RecordingTransport {
  fun execute(
    upstream: HttpRecordingUpstream,
    request: HostHttpRequest,
    addresses: List<InetAddress>,
    authValue: String?,
  ): HostHttpResponse
}

private data class RecordingSession(
  val id: String,
  val upstreamId: String,
  val fixtureSet: String,
  val candidate: File,
  val createdAt: Instant,
  val entries: MutableList<HttpFixtureEntry> = mutableListOf(),
  var lastAccessMillis: Long,
  var closed: Boolean = false,
)

/**
 * Explicit, loopback-only HTTP recording. Disabled mode has no token and cannot
 * construct the outbound transport.
 */
internal class HttpRecordingService(
  private val repoDir: File,
  private val config: PortalConfig,
  private val storeDir: File,
  enabledByEnvironment: Boolean,
  private val environment: (String) -> String? = System::getenv,
  private val clock: Clock = Clock.systemUTC(),
  private val random: SecureRandom = SecureRandom(),
  private val dnsResolver: RecordingDnsResolver = RecordingDnsResolver {
    InetAddress.getAllByName(it).toList()
  },
  transport: RecordingTransport? = null,
) {
  private val recordingConfig = config.httpRecording
  val enabled: Boolean = enabledByEnvironment && recordingConfig?.upstreams?.isNotEmpty() == true
  private val json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
  }
  private val replayService = HttpReplayService(repoDir, config, clock)
  private val fixturesDir = config.resolvedHttpFixturesDir(repoDir)
  private val candidatesDir = File(fixturesDir, ".candidates")
  private val auditFile = File(storeDir, "http-record-audit.jsonl")
  private val token: String?
  private val outbound: RecordingTransport?
  private val sessions = LinkedHashMap<String, RecordingSession>(16, 0.75f, true)

  init {
    if (enabled) {
      recordingConfig!!.upstreams.forEach { (_, upstream) ->
        upstream.authEnv?.let { name ->
          val value = environment(name)
          require(
            !value.isNullOrBlank() &&
              value.length <= 8192 &&
              '\r' !in value &&
              '\n' !in value,
          ) {
            "httpRecording auth environment is missing for an enabled upstream"
          }
        }
      }
      candidatesDir.mkdirs()
      token = createProcessToken()
      outbound = transport ?: OkHttpRecordingTransport()
    } else {
      token = null
      outbound = null
      tokenFile().delete()
    }
  }

  fun startupMessage(): String? =
    if (enabled) "portal-server: HTTP recording enabled; token file=${tokenFile().absolutePath}" else null

  fun isAllowedOrigin(origin: String?): Boolean =
    origin == null || origin in recordingConfig?.allowedOrigins.orEmpty()

  fun authorize(remoteAddress: InetAddress?, origin: String?, suppliedToken: String?): Boolean {
    if (!enabled || remoteAddress?.isLoopbackAddress != true || !isAllowedOrigin(origin)) return false
    val expected = token ?: return false
    val supplied = suppliedToken ?: return false
    return MessageDigest.isEqual(expected.encodeToByteArray(), supplied.encodeToByteArray())
  }

  fun decodeSessionCommand(body: String): HttpRecordSessionCommand = json.decodeFromString(body)

  fun decodeRequest(body: String): HostHttpRequest = json.decodeFromString(body)

  fun createSession(command: HttpRecordSessionCommand): HttpRecordingResult {
    val recordConfig = recordingConfig ?: return failure(404, "recording_disabled")
    if (!SAFE_RECORD_NAME.matches(command.upstream) || !SAFE_RECORD_NAME.matches(command.fixtureSet)) {
      return failure(400, "invalid_name")
    }
    if (command.upstream !in recordConfig.upstreams) return failure(404, "unknown_upstream")

    val now = clock.millis()
    synchronized(sessions) {
      evictExpired(now)
      if (sessions.size >= MAX_RECORD_SESSIONS) return failure(429, "session_capacity")
      val id = randomId(16)
      val suffix = randomId(6)
      val timestamp = Instant.ofEpochMilli(now).toString().replace(":", "").replace("-", "")
      val candidate = File(candidatesDir, "${command.fixtureSet}-$timestamp-$suffix.json")
      val session = RecordingSession(
        id = id,
        upstreamId = command.upstream,
        fixtureSet = command.fixtureSet,
        candidate = candidate,
        createdAt = clock.instant(),
        lastAccessMillis = now,
      )
      sessions[id] = session
      return success(
        201,
        json.encodeToString(
          HttpRecordSessionCreated(
            session = id,
            candidate = candidate.relativeTo(repoDir).invariantSeparatorsPath,
          ),
        ),
      )
    }
  }

  fun record(sessionId: String, request: HostHttpRequest): HttpRecordingResult {
    if (!SAFE_RECORD_NAME.matches(sessionId)) return failure(404, "unknown_session")
    val session = synchronized(sessions) {
      evictExpired(clock.millis())
      sessions[sessionId]?.also { it.lastAccessMillis = clock.millis() }
    } ?: return failure(404, "unknown_session")
    if (session.closed) return failure(409, "session_closed")

    val upstream = recordingConfig?.upstreams?.get(session.upstreamId)
      ?: return failure(404, "unknown_upstream")
    val method = request.method.uppercase()
    if (method !in upstream.allowedMethods) return failure(405, "method_not_allowed")
    val safePath = validateRecordPath(request.path) ?: return failure(400, "invalid_path")
    if ((request.body?.encodeToByteArray()?.size ?: 0) > MAX_RECORD_BODY_BYTES) {
      return failure(413, "request_too_large")
    }
    if (
      recordingConfig.additionalSensitiveKeys.isNotEmpty() &&
      request.body?.isNotBlank() == true &&
      runCatching { Json.parseToJsonElement(request.body.orEmpty()) }.isFailure
    ) {
      return failure(422, "unredactable_request")
    }
    val normalizedRequest = request.copy(method = method, path = safePath)
    val addresses = runCatching { dnsResolver.resolve(URI(upstream.baseUrl).host) }
      .getOrElse {
        audit(session, method, safePath, "dns_failure", 0, 0, null)
        return failure(422, "dns_failure")
      }
    if (addresses.isEmpty() || addresses.any { !isGlobalAddress(it) }) {
      audit(session, method, safePath, "non_global_address", 0, 0, null)
      return failure(422, "non_global_address")
    }
    val authValue = upstream.authEnv?.let(environment)
    if (upstream.authEnv != null && authValue.isNullOrBlank()) {
      return failure(424, "auth_unavailable")
    }
    val outboundRequest = normalizedRequest.copy(
      headers = normalizedRequest.headers.entries
        .filter { (key) ->
          val lower = key.lowercase()
          lower in upstream.forwardHeaders &&
            lower !in SENSITIVE_HTTP_HEADERS &&
            lower !in HOP_BY_HOP_HEADERS
        }
        .associate { it.key.lowercase() to it.value },
    )

    val rawResponse = try {
      outbound!!.execute(upstream, outboundRequest, addresses.toList(), authValue)
    } catch (_: RecordingBodyTooLargeException) {
      audit(session, method, safePath, "response_too_large", bodySize(request.body), 0, null)
      return failure(413, "response_too_large")
    } catch (_: java.net.SocketTimeoutException) {
      audit(session, method, safePath, "timeout", bodySize(request.body), 0, null)
      return failure(504, "timeout")
    } catch (_: Exception) {
      audit(session, method, safePath, "network_failure", bodySize(request.body), 0, null)
      return failure(502, "network_failure")
    }
    if (
      recordingConfig.additionalSensitiveKeys.isNotEmpty() &&
      rawResponse.body.isNotBlank() &&
      runCatching { Json.parseToJsonElement(rawResponse.body) }.isFailure
    ) {
      audit(
        session,
        method,
        safePath,
        "unredactable_response",
        bodySize(request.body),
        bodySize(rawResponse.body),
        null,
      )
      return failure(422, "unredactable_response")
    }

    val sensitiveKeys = recordingConfig.additionalSensitiveKeys.toSet() + DEFAULT_RECORD_SENSITIVE_KEYS
    val redactedRequest = redactRequest(normalizedRequest, upstream, sensitiveKeys)
    val redactedResponse = redactResponse(rawResponse, upstream, sensitiveKeys)
    val entry = HttpFixtureEntry(redactedRequest, redactedResponse)
    val fixture = synchronized(sessions) {
      if (session.closed) return failure(409, "session_closed")
      session.entries += entry
      session.lastAccessMillis = clock.millis()
      buildFixture(session, upstream)
    }
    val bytes = json.encodeToString(fixture).encodeToByteArray()
    val writeResult = runCatching {
      require(bytes.size <= MAX_RECORD_BODY_BYTES * 4) { "candidate too large" }
      replayService.validateCandidate(fixture)
      atomicWrite(session.candidate, bytes)
    }
    if (writeResult.isFailure) {
      synchronized(sessions) { session.entries.removeLastOrNull() }
      audit(
        session,
        method,
        safePath,
        "candidate_rejected",
        bodySize(request.body),
        bodySize(rawResponse.body),
        null,
      )
      return failure(422, "candidate_rejected")
    }
    audit(
      session,
      method,
      safePath,
      "recorded",
      bodySize(request.body),
      bodySize(rawResponse.body),
      session.candidate.name,
    )
    return success(200, json.encodeToString(redactedResponse))
  }

  fun close(sessionId: String): HttpRecordingResult {
    if (!SAFE_RECORD_NAME.matches(sessionId)) return failure(404, "unknown_session")
    val session = synchronized(sessions) {
      evictExpired(clock.millis())
      sessions[sessionId]
    } ?: return failure(404, "unknown_session")
    if (session.entries.isEmpty() || !session.candidate.isFile) return failure(409, "empty_session")

    val bytes = runCatching {
      require(session.candidate.length() <= MAX_RECORD_BODY_BYTES * 4) { "candidate too large" }
      session.candidate.readBytes().also {
        replayService.validateCandidate(json.decodeFromString<HttpFixtureSetFile>(it.decodeToString()))
      }
    }.getOrElse {
      return failure(422, "candidate_rejected")
    }
    synchronized(sessions) {
      session.closed = true
      session.lastAccessMillis = clock.millis()
    }
    val expiresAt = session.createdAt
      .plus(recordingConfig!!.fixtureTtlDays.toLong(), ChronoUnit.DAYS)
      .toString()
    return success(
      200,
      json.encodeToString(
        HttpRecordClosed(
          candidate = session.candidate.relativeTo(repoDir).invariantSeparatorsPath,
          entries = session.entries.size,
          revision = revision(bytes),
          expiresAt = expiresAt,
          reviewedFixtureExists = File(fixturesDir, "${session.fixtureSet}.json").isFile,
        ),
      ),
    )
  }

  fun errorJson(error: HttpRecordError): String = json.encodeToString(error)

  private fun buildFixture(
    session: RecordingSession,
    upstream: HttpRecordingUpstream,
  ): HttpFixtureSetFile = HttpFixtureSetFile(
    formatVersion = 1,
    recordedAt = session.createdAt.toString(),
    expiresAt = session.createdAt
      .plus(recordingConfig!!.fixtureTtlDays.toLong(), ChronoUnit.DAYS)
      .toString(),
    matchHeaders = upstream.forwardHeaders.filter { it in setOf("accept", "content-type") }.sorted(),
    entries = session.entries.toList(),
  )

  private fun redactRequest(
    request: HostHttpRequest,
    upstream: HttpRecordingUpstream,
    sensitiveKeys: Set<String>,
  ): HostHttpRequest = request.copy(
    query = request.query.mapValues { (key, value) ->
      if (isSensitiveHttpKey(key, sensitiveKeys)) "<redacted>" else value
    },
    headers = request.headers.entries
      .filter { (key) ->
        val lower = key.lowercase()
        lower in upstream.forwardHeaders &&
          lower !in SENSITIVE_HTTP_HEADERS &&
          lower !in HOP_BY_HOP_HEADERS
      }
      .associate { it.key.lowercase() to it.value },
    body = request.body?.let { redactSensitiveJson(it, sensitiveKeys) },
  )

  private fun redactResponse(
    response: HostHttpResponse,
    upstream: HttpRecordingUpstream,
    sensitiveKeys: Set<String>,
  ): HostHttpResponse = response.copy(
    body = redactSensitiveJson(response.body, sensitiveKeys),
    headers = response.headers.entries
      .filter { (key) ->
        val lower = key.lowercase()
        lower in (upstream.forwardHeaders + "content-type") &&
          lower !in SENSITIVE_HTTP_HEADERS &&
          lower !in HOP_BY_HOP_HEADERS
      }
      .associate { it.key.lowercase() to it.value },
  )

  private fun createProcessToken(): String {
    val token = randomId(32)
    val file = tokenFile()
    file.parentFile.mkdirs()
    atomicWrite(file, token.encodeToByteArray(), ownerOnly = true)
    return token
  }

  private fun tokenFile(): File = File(storeDir, "http-record.token")

  private fun atomicWrite(destination: File, bytes: ByteArray, ownerOnly: Boolean = false) {
    destination.parentFile.mkdirs()
    val temp = File(destination.parentFile, ".${destination.name}.${randomId(6)}.tmp")
    temp.writeBytes(bytes)
    if (ownerOnly) {
      runCatching {
        Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------"))
      }
    }
    try {
      try {
        Files.move(
          temp.toPath(),
          destination.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      temp.delete()
    }
  }

  private fun audit(
    session: RecordingSession,
    method: String,
    path: String,
    outcome: String,
    requestBytes: Int,
    responseBytes: Int,
    candidate: String?,
  ) {
    val pathHash = revision(path.encodeToByteArray())
    val line = buildString {
      append("""{"at":"${clock.instant()}"""")
      append(""","session":"${session.id.takeLast(8)}"""")
      append(""","upstream":"${session.upstreamId}"""")
      append(""","method":"$method"""")
      append(""","pathHash":"$pathHash"""")
      append(""","outcome":"$outcome"""")
      append(""","requestBytes":$requestBytes,"responseBytes":$responseBytes""")
      candidate?.let { append(""","candidate":"$it"""") }
      append("}\n")
    }
    runCatching {
      auditFile.parentFile.mkdirs()
      auditFile.appendText(line)
    }
  }

  private fun evictExpired(now: Long) {
    sessions.entries.removeIf { now - it.value.lastAccessMillis > RECORD_SESSION_TTL_MILLIS }
  }

  private fun randomId(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun success(status: Int, body: String): HttpRecordingResult.Success =
    HttpRecordingResult.Success(status, body)

  private fun failure(status: Int, reason: String): HttpRecordingResult.Failure =
    HttpRecordingResult.Failure(status, HttpRecordError("recording_failed", reason))

  private fun revision(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .take(8)
      .joinToString("") { "%02x".format(it) }

  private fun bodySize(body: String?): Int = body?.encodeToByteArray()?.size ?: 0
}

private class RecordingBodyTooLargeException : Exception()

private class OkHttpRecordingTransport : RecordingTransport {
  override fun execute(
    upstream: HttpRecordingUpstream,
    request: HostHttpRequest,
    addresses: List<InetAddress>,
    authValue: String?,
  ): HostHttpResponse {
    val base = upstream.baseUrl.toHttpUrl()
    val url = buildTargetUrl(base, request)
    val pinnedDns = Dns { hostname ->
      if (!hostname.equals(base.host, ignoreCase = true)) throw UnknownHostException("host mismatch")
      addresses
    }
    val client = OkHttpClient.Builder()
      .dns(pinnedDns)
      .proxy(Proxy.NO_PROXY)
      .proxyAuthenticator(Authenticator.NONE)
      .authenticator(Authenticator.NONE)
      .cookieJar(CookieJar.NO_COOKIES)
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      .callTimeout(15, TimeUnit.SECONDS)
      .build()

    val mediaType = request.headers.entries
      .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
      ?.value
      ?.toMediaTypeOrNull()
    val requestBody = request.body?.toRequestBody(mediaType)
      ?: if (request.method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(mediaType) else null
    val builder = Request.Builder().url(url).method(request.method, requestBody)
    request.headers.forEach { (name, value) ->
      val lower = name.lowercase()
      if (lower in upstream.forwardHeaders &&
        lower !in SENSITIVE_HTTP_HEADERS &&
        lower !in HOP_BY_HOP_HEADERS
      ) {
        builder.header(name, value)
      }
    }
    if (authValue != null) builder.header(upstream.authHeader, upstream.authPrefix + authValue)

    client.newCall(builder.build()).execute().use { response ->
      return response.toPortableResponse(upstream)
    }
  }

  private fun buildTargetUrl(base: HttpUrl, request: HostHttpRequest): HttpUrl {
    val combinedPath = base.encodedPath.trimEnd('/') + request.path
    val builder = base.newBuilder().encodedPath(combinedPath).query(null)
    request.query.entries.sortedBy { it.key }.forEach { (key, value) ->
      builder.addQueryParameter(key, value)
    }
    val result = builder.build()
    require(result.scheme == base.scheme && result.host == base.host && result.port == base.port) {
      "target origin changed"
    }
    return result
  }

  private fun Response.toPortableResponse(upstream: HttpRecordingUpstream): HostHttpResponse {
    val bytes = body.source().readByteArray((MAX_RECORD_BODY_BYTES + 1).toLong())
    if (bytes.size > MAX_RECORD_BODY_BYTES) throw RecordingBodyTooLargeException()
    val decoder = Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
    var headerBytes = 0
    val safeHeaders = headers.names().sorted().mapNotNull { name ->
      val lower = name.lowercase()
      if (lower !in (upstream.forwardHeaders + "content-type") ||
        lower in SENSITIVE_HTTP_HEADERS ||
        lower in HOP_BY_HOP_HEADERS
      ) {
        return@mapNotNull null
      }
      val value = headers.values(name).joinToString(",")
      headerBytes += name.length + value.length
      if (headerBytes > MAX_RECORD_HEADER_BYTES) throw RecordingBodyTooLargeException()
      lower to value
    }.toMap()
    return HostHttpResponse(code, text, safeHeaders)
  }
}

internal fun validateRecordPath(path: String): String? {
  if (!path.startsWith('/') || path.startsWith("//")) return null
  if (path.any { it == '\\' || it == '?' || it == '#' || it.code < 0x20 }) return null
  if (ENCODED_PATH_SEPARATOR.containsMatchIn(path)) return null
  if (path.split('/').any { it == "." || it == ".." }) return null
  return path
}

internal fun isGlobalAddress(address: InetAddress): Boolean {
  if (
    address.isAnyLocalAddress ||
    address.isLoopbackAddress ||
    address.isLinkLocalAddress ||
    address.isSiteLocalAddress ||
    address.isMulticastAddress
  ) {
    return false
  }
  val bytes = address.address
  return when (address) {
    is Inet4Address -> {
      val a = bytes[0].toInt() and 0xff
      val b = bytes[1].toInt() and 0xff
      when {
        a == 0 || a == 10 || a == 127 || a >= 224 -> false
        a == 100 && b in 64..127 -> false
        a == 169 && b == 254 -> false
        a == 172 && b in 16..31 -> false
        a == 192 && b == 0 -> false
        a == 192 && b == 168 -> false
        a == 198 && b in 18..19 -> false
        a == 198 && b == 51 && (bytes[2].toInt() and 0xff) == 100 -> false
        a == 203 && b == 0 && (bytes[2].toInt() and 0xff) == 113 -> false
        else -> true
      }
    }
    is Inet6Address -> {
      val first = bytes[0].toInt() and 0xff
      val second = bytes[1].toInt() and 0xff
      val uniqueLocal = first and 0xfe == 0xfc
      val linkLocal = first == 0xfe && second and 0xc0 == 0x80
      val documentation = first == 0x20 &&
        second == 0x01 &&
        (bytes[2].toInt() and 0xff) == 0x0d &&
        (bytes[3].toInt() and 0xff) == 0xb8
      !uniqueLocal && !linkLocal && !documentation
    }
    else -> false
  }
}
