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
  /**
   * Document store location. **Null (the default) means "one store per app,
   * derived from the repo path"** — see [storeDir].
   *
   * It used to default to the literal `~/.keliver-portal`, a MACHINE-GLOBAL
   * directory shared by every app on the machine. Two apps then shared one
   * store, and both of the store's own invariants turned destructive:
   *
   *   * `bootScan` retires store mirrors whose `.kt` is missing from THIS
   *     app's screens dir, so starting app B deleted app A's documents;
   *   * with both running, app B's `/screens` listed app A's screens, and
   *     opening one materialised app A's screen as a `.kt` (plus its
   *     `Compiled_*.kt`) inside app B's source tree.
   *
   * An explicit value is still honoured — it is the supported way to place the
   * store — but a store records the repo that owns it and refuses to serve a
   * different one.
   */
  val store: String? = null,
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

/** The global root that per-app stores live under. */
private fun portalHome(): File = File(System.getProperty("user.home"), ".keliver-portal")

/** Stable, readable per-repo directory name: <dir-name>-<8 hex of the abs path>. */
internal fun appStoreName(repoDir: File): String {
  val abs = repoDir.absoluteFile.canonicalFile.path
  val digest = java.security.MessageDigest.getInstance("SHA-256").digest(abs.toByteArray())
  val hash = digest.take(4).joinToString("") { "%02x".format(it) }
  val slug = repoDir.absoluteFile.name.lowercase().replace(Regex("[^a-z0-9._-]"), "-").ifEmpty { "app" }
  return "$slug-$hash"
}

/**
 * Where this repo's documents live.
 *
 * Default: `~/.keliver-portal/apps/<slug>-<hash>` — inside the familiar global
 * root, but owned by exactly one repo. Never inside the app's source tree.
 * An explicit [store] is honoured verbatim (`~/` expanded, relative paths
 * resolved against the repo).
 */
fun PortalConfig.storeDir(repoDir: File): File {
  val s = store ?: return File(File(portalHome(), "apps"), appStoreName(repoDir))
  return when {
    s.startsWith("~/") -> File(System.getProperty("user.home"), s.removePrefix("~/"))
    File(s).isAbsolute -> File(s)
    else -> File(repoDir, s)
  }
}

/**
 * A store belongs to one repo. The marker is created on first use and checked
 * on every start: sharing a store between repos is the defect this closes
 * (U17), so it fails fast and says how to fix it rather than silently serving
 * the wrong documents.
 *
 * Acquisition is ATOMIC. This used to be `exists()` then `writeText()`, and two
 * relays starting together against an unowned store both saw "no owner" and
 * both proceeded — a race that handed them the shared store the marker exists
 * to prevent. A concurrency test showed 16 of 16 simultaneous claimants
 * winning. `CREATE_NEW` lets exactly one create the file.
 */
fun claimStoreFor(storeDir: File, repoDir: File) {
  storeDir.mkdirs()
  val owner = File(storeDir, "owner").toPath()
  val me = repoDir.absoluteFile.canonicalFile.path

  try {
    java.nio.file.Files.newByteChannel(
      owner,
      java.nio.file.StandardOpenOption.CREATE_NEW,
      java.nio.file.StandardOpenOption.WRITE,
    ).use { it.write(java.nio.ByteBuffer.wrap((me + "\n").toByteArray())) }
    return
  } catch (_: java.nio.file.FileAlreadyExistsException) {
    // Someone owns it — possibly a winner still writing its own name.
  }

  // The winner creates the marker and writes into it as two steps, so a loser
  // can briefly observe an empty file. Wait out that window rather than
  // reporting a conflict with a blank owner.
  var theirs = ""
  repeat(40) {
    theirs = runCatching { java.nio.file.Files.readString(owner).trim() }.getOrDefault("")
    if (theirs.isNotEmpty()) return@repeat
    Thread.sleep(5)
  }

  if (theirs == me) return
  throw IllegalStateException(
    buildString {
      appendLine("portal store conflict: $storeDir already belongs to another app.")
      appendLine("  owner: ${theirs.ifEmpty { "(unknown — the marker exists but is empty)" }}")
      appendLine("  this:  $me")
      appendLine("Two apps must not share one document store — the boot scan retires")
      appendLine("mirrors that do not match the app it is serving, which would delete the")
      appendLine("other app's documents, and opening a foreign screen writes its .kt into")
      appendLine("this app's source tree.")
      appendLine("Fix: remove \"store\" from this app's keliver.portal.json to get its own")
      appendLine("store, or point it at a directory this app alone uses.")
    },
  )
}

/**
 * What a pre-relocation `~/.keliver-portal` still holds.
 *
 * Before per-app stores, everything lived in one global directory. Moving the
 * default must not make a developer's signing identity, published bundles or
 * documents look deleted — they are still on disk, just no longer the store
 * this repo uses. The relay reports them and points at the adopt route; it
 * does NOT copy them, because a global store cannot be attributed to one repo
 * without being told which.
 */
class LegacyStoreContents(val root: File) {
  val hasKeys: Boolean = File(root, "keys/ed25519.priv").isFile
  val bundles: Int = File(root, "bundles").listFiles()?.count { it.isDirectory } ?: 0
  val projects: List<String> = root.listFiles()
    ?.filter { it.isDirectory && it.name !in setOf("apps", "bundles", "keys", "kotlin") }
    ?.filter { p -> p.listFiles()?.any { it.name.endsWith(".json") } == true }
    ?.map { it.name }?.sorted() ?: emptyList()
  val anything: Boolean get() = hasKeys || bundles > 0 || projects.isNotEmpty()

  fun describe(): String = buildString {
    appendLine("portal-server: NOTE — the legacy shared store at $root still holds:")
    if (hasKeys) appendLine("    - a signing identity (keys/)")
    if (bundles > 0) appendLine("    - $bundles published bundle(s)")
    if (projects.isNotEmpty()) appendLine("    - documents for project(s): ${projects.joinToString(", ")}")
    appendLine("  This app now uses its own store, so it does not see them. Nothing was")
    appendLine("  moved or deleted. To keep using that identity and history for THIS app:")
    appendLine("    scripts/keliver-adopt-legacy-store.sh <app-dir>")
    append("  or set \"store\" in keliver.portal.json to the legacy path for the one app that owns it.")
  }
}

/** Legacy contents, or null when the store in use IS the legacy directory. */
fun legacyStoreOrNull(storeInUse: File): LegacyStoreContents? {
  val legacy = portalHome()
  if (storeInUse.canonicalFile == legacy.canonicalFile) return null
  if (!legacy.isDirectory) return null
  return LegacyStoreContents(legacy).takeIf { it.anything }
}

fun PortalConfig.runtimeMetadataJson(): String =
  Json.encodeToString(
    RuntimeMetadataResponse.serializer(),
    RuntimeMetadataResponse(appRuntime),
  )
