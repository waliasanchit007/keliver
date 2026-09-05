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
import java.io.File
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val SAFE_CONFIG_NAME = Regex("[A-Za-z0-9._-]{1,128}")
private val SAFE_HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
private val FORBIDDEN_RECORD_HEADERS = setOf(
  "authorization",
  "proxy-authorization",
  "cookie",
  "set-cookie",
  "host",
  "connection",
  "content-length",
  "transfer-encoding",
  "upgrade",
  "forwarded",
  "x-forwarded-for",
  "x-forwarded-host",
  "x-forwarded-proto",
)

@Serializable
data class AppRuntimeMetadata(
  val keliverVersion: String,
  val widgetVersion: Int,
) {
  init {
    require(keliverVersion.isNotBlank()) { "appRuntime.keliverVersion must not be blank" }
    require(widgetVersion > 0) { "appRuntime.widgetVersion must be positive" }
  }
}

@Serializable
data class HttpRecordingUpstream(
  val baseUrl: String,
  val allowedMethods: List<String> = listOf("GET"),
  val forwardHeaders: List<String> = listOf("accept", "content-type"),
  val authEnv: String? = null,
  val authHeader: String = "Authorization",
  val authPrefix: String = "Bearer ",
) {
  init {
    val uri = runCatching { URI(baseUrl) }.getOrNull()
    require(
      uri != null &&
        uri.isAbsolute &&
        uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null,
    ) {
      "httpRecording upstream baseUrl must be an absolute HTTPS URL without user-info, query, or fragment"
    }
    val host = uri!!.host
    val ipv4Literal = host.split('.').size == 4 && host.split('.').all {
      it.toIntOrNull() in 0..255
    }
    require(':' !in host && !ipv4Literal) {
      "httpRecording upstream baseUrl must use a DNS hostname, not an IP literal"
    }
    require(allowedMethods.isNotEmpty()) { "httpRecording allowedMethods must not be empty" }
    require(allowedMethods.all { it == it.uppercase() && it in SAFE_HTTP_METHODS }) {
      "httpRecording allowedMethods may contain only ${SAFE_HTTP_METHODS.sorted()}"
    }
    require(allowedMethods.distinct().size == allowedMethods.size) {
      "httpRecording allowedMethods must be unique"
    }
    require(forwardHeaders.all { it == it.lowercase() && it !in FORBIDDEN_RECORD_HEADERS }) {
      "httpRecording forwardHeaders must be lowercase and non-sensitive"
    }
    require(forwardHeaders.distinct().size == forwardHeaders.size) {
      "httpRecording forwardHeaders must be unique"
    }
    require(authEnv == null || SAFE_CONFIG_NAME.matches(authEnv)) {
      "httpRecording authEnv must be a simple environment-variable name"
    }
    require(authHeader.isNotBlank() && authHeader.lowercase() !in FORBIDDEN_RECORD_HEADERS - "authorization") {
      "httpRecording authHeader is invalid"
    }
    require(authHeader.lowercase() in setOf("authorization", "x-api-key", "x-auth-token")) {
      "httpRecording authHeader must be Authorization, X-Api-Key, or X-Auth-Token"
    }
    require('\r' !in authPrefix && '\n' !in authPrefix) {
      "httpRecording authPrefix must not contain line breaks"
    }
  }
}

@Serializable
data class HttpRecordingConfig(
  val allowedOrigins: List<String> = emptyList(),
  val fixtureTtlDays: Int = 30,
  val additionalSensitiveKeys: List<String> = emptyList(),
  val upstreams: Map<String, HttpRecordingUpstream> = emptyMap(),
) {
  init {
    require(fixtureTtlDays in 1..365) { "httpRecording.fixtureTtlDays must be in 1..365" }
    require(allowedOrigins.distinct().size == allowedOrigins.size) {
      "httpRecording.allowedOrigins must be unique"
    }
    allowedOrigins.forEach { origin ->
      val uri = runCatching { URI(origin) }.getOrNull()
      require(
        uri != null &&
          uri.isAbsolute &&
          uri.scheme in setOf("http", "https") &&
          uri.host in setOf("localhost", "127.0.0.1", "::1") &&
          uri.rawPath.isNullOrEmpty() &&
          uri.rawQuery == null &&
          uri.rawFragment == null,
      ) {
        "httpRecording.allowedOrigins must be exact loopback HTTP(S) origins"
      }
    }
    require(
      additionalSensitiveKeys.all {
        it.isNotBlank() && it == it.lowercase() && it.matches(Regex("[a-z0-9_-]{1,64}"))
      },
    ) {
      "httpRecording.additionalSensitiveKeys must be lowercase key names"
    }
    require(additionalSensitiveKeys.distinct().size == additionalSensitiveKeys.size) {
      "httpRecording.additionalSensitiveKeys must be unique"
    }
    require(upstreams.keys.all(SAFE_CONFIG_NAME::matches)) {
      "httpRecording upstream IDs must use letters, digits, '.', '_', or '-'"
    }
  }
}

@Serializable
private data class RuntimeMetadataResponse(
  val appRuntime: AppRuntimeMetadata?,
)

/**
 * Separability groundwork: everything the portal-server needs to know about
 * the app repo it serves, read from keliver.portal.json at the repo root.
 * Every field defaults to this repo's layout, so the file is optional here
 * and REQUIRED only for a future split-out app repo.
 */
@Serializable
data class PortalConfig(
  val port: Int = 8077,
  val screensDir: String = "portal-app-lib/src/jsMain/kotlin/screens",
  /**
   * C1: app-owned reusable components ("molecules"). Null = a `components`
   * directory that is a SIBLING of [screensDir] (so old config files stay valid
   * and new repos get it for free). Set explicitly to relocate.
   */
  val componentsDir: String? = null,
  val publishTask: String = ":portal-published-guest:compileDevelopmentZipline",
  val publishOutput: String = "portal-published-guest/build/zipline/Development",
  val store: String = "~/.keliver-portal",
  /**
   * P3-12 live-presenter preview: logic dirs to watch (null = a `logic` sibling
   * of screensDir), the gradle task that rebuilds the per-app editor, where its
   * dist lands, and where SUCCESSFUL builds are promoted for serving (the
   * last-known-good copy an http server should serve).
   */
  val logicDirs: List<String>? = null,
  /** #13 F1: flow declarations (flow{} DSL). Null = a `flows` sibling of [screensDir]. */
  val flowsDir: String? = null,
  /**
   * The per-app editor build. EMPTY = this app has no editor of its own, so
   * the relay does not attempt a preview build at all and the bundled generic
   * editor is served instead.
   *
   * These used to default to `:web-spike:…` — THIS repo's dogfood editor.
   * Every consumer inherited that, and since `web-spike` does not exist in
   * their build, the relay reported `project 'web-spike' not found` and a red
   * "preview build failed" chip from the first second of their first run.
   * Keliver's own `keliver.portal.json` now sets these explicitly; the default
   * is the case a consumer is actually in.
   */
  val previewBuildTask: String = "",
  val previewDist: String = "",
  val previewServeDir: String = "build/portal-editor-live",
  /**
   * #16 H1: app-owned, reviewed HTTP replay fixtures. The resolved directory
   * must stay inside the repository and the relay never writes it in replay
   * mode.
   */
  val httpFixturesDir: String = "portal-fixtures/http",
  /** #16 H2: reviewed outbound targets. Still inert without PORTAL_HTTP_RECORD=1. */
  val httpRecording: HttpRecordingConfig? = null,
  /**
   * K4: explicit version of the app/device runtime this editor is previewing.
   * Never infer this from Gradle: catalogs, BOMs, and composite substitution
   * can make the editor's resolved version differ from the app's target.
   */
  val appRuntime: AppRuntimeMetadata? = null,
)

fun PortalConfig.resolvedLogicDirs(): List<String> =
  logicDirs ?: listOf(
    screensDir.substringBeforeLast('/', "").let { if (it.isEmpty()) "logic" else "$it/logic" },
  )

/** The resolved components dir: explicit [componentsDir], else a `components` sibling of screens. */
fun PortalConfig.resolvedComponentsDir(): String =
  componentsDir ?: (screensDir.substringBeforeLast('/', "").let { if (it.isEmpty()) "components" else "$it/components" })

/** The resolved flows dir: explicit [flowsDir], else a `flows` sibling of screens. */
fun PortalConfig.resolvedFlowsDir(): String =
  flowsDir ?: (screensDir.substringBeforeLast('/', "").let { if (it.isEmpty()) "flows" else "$it/flows" })

/** Resolve replay fixtures and reject paths that escape the app repository. */
fun PortalConfig.resolvedHttpFixturesDir(repoDir: File): File {
  val repo = repoDir.canonicalFile
  val fixtures = File(repo, httpFixturesDir).canonicalFile
  require(fixtures.toPath().startsWith(repo.toPath())) {
    "httpFixturesDir must stay inside the app repository: $httpFixturesDir"
  }
  return fixtures
}

fun loadPortalConfig(repoDir: File): PortalConfig {
  val f = File(repoDir, "keliver.portal.json")
  if (!f.exists()) return PortalConfig()
  return Json { ignoreUnknownKeys = true }.decodeFromString(PortalConfig.serializer(), f.readText())
}

fun PortalConfig.storeDir(): File =
  if (store.startsWith("~/")) File(System.getProperty("user.home"), store.removePrefix("~/")) else File(store)

fun PortalConfig.runtimeMetadataJson(): String =
  Json.encodeToString(
    RuntimeMetadataResponse.serializer(),
    RuntimeMetadataResponse(appRuntime),
  )
