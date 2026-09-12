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

/**
 * Refusals that mean "I will not guess which store is yours".
 *
 * [IllegalStateException] so that callers written against the original
 * `claimStoreFor` contract keep working; the relay catches this one to print
 * the message instead of a stack trace.
 */
class StoreOwnershipException(message: String) : IllegalStateException(message)

/**
 * The readable half of a default store directory name.
 *
 * Defined over the basename's **UTF-8 bytes**, not its characters, because
 * three languages have to produce the same answer. Kotlin's old regex mapped
 * each UTF-16 code *unit*, Python's `isalnum()` is Unicode-aware and maps each
 * code *point*, so `café-☕` slugged as `caf----` in Kotlin and `café---` in
 * the shell mirror — the same app, two store directories. Bytes, ASCII
 * lowercasing, and collapsed runs leave nothing for them to disagree about.
 */
internal fun storeSlug(name: String): String {
  val sb = StringBuilder()
  for (byte in name.toByteArray(Charsets.UTF_8)) {
    val c = (byte.toInt() and 0xff).toChar()
    val lower = if (c in 'A'..'Z') (c.code + 32).toChar() else c
    sb.append(
      if (lower in 'a'..'z' || lower in '0'..'9' || lower == '.' || lower == '_' || lower == '-') lower else '-',
    )
  }
  val slug = sb.toString().replace(Regex("-+"), "-").trim('-')
  // "." and ".." are directory names the store root must never be given.
  return if (slug.isEmpty() || slug == "." || slug == "..") "app" else slug
}

/**
 * The hash half: 8 hex digits of SHA-256 over the app's CANONICAL path, so a
 * symlink and the real path hash identically. This half was already canonical
 * before the U25.1 fix; the slug was not.
 */
internal fun appStoreHash(repoDir: File): String {
  val abs = repoDir.absoluteFile.canonicalFile.path
  val digest = java.security.MessageDigest.getInstance("SHA-256").digest(abs.toByteArray())
  return digest.take(4).joinToString("") { "%02x".format(it) }
}

/** Stable, readable per-repo directory name: `<slug>-<8 hex of the canonical path>`. */
internal fun appStoreName(repoDir: File): String =
  "${storeSlug(repoDir.absoluteFile.canonicalFile.name)}-${appStoreHash(repoDir)}"

/** The app-side half of the binding: machine-local, uncommitted, `.gradle` is gitignored. */
internal fun storePointerFile(repoDir: File): File = File(File(repoDir, ".gradle"), "keliver-store-path")

/**
 * The app-side lock, shared with `keliver-store-recover.sh`.
 *
 * Claiming a store and writing the pointer are two writes, and the relay is not
 * the only writer: the recovery command performs the same two-sided update. A
 * lock held only by recovery commands would not serialize against a relay
 * starting at that moment, which is how a store can end up owned by an app
 * whose pointer names a different one. `mkdir` is the atomic create, so the
 * shell and the JVM can hold the same lock.
 */
internal fun storeLockDir(repoDir: File): File = File(File(repoDir, ".gradle"), "keliver-store.lock")

/**
 * Run [block] holding the app lock.
 *
 * EVERYTHING that decides or records the binding belongs inside [block] —
 * resolution included. Resolving first and locking afterwards means the store
 * this process claims can be the one a recovery replaced while it waited, and
 * it then writes that stale answer back into the pointer, undoing the recovery.
 *
 * Waits [waitMillis] for a recovery in flight, then gives up rather than
 * proceeding — a bounded wait is the point; two writers is the failure.
 * If the lock directory cannot be created at all (a read-only app tree), the
 * block runs unlocked and [onUnlocked] is told: refusing to start an app whose
 * tree is read-only would be a worse outcome than an unsynchronised startup
 * that nothing else is contending for.
 */
internal fun <T> withStoreLock(
  repoDir: File,
  waitMillis: Long = 20_000,
  onUnlocked: (String) -> Unit = {},
  onBusy: (File) -> Nothing,
  block: () -> T,
): T {
  val lock = storeLockDir(repoDir)
  val parentReady = runCatching { lock.parentFile.mkdirs(); lock.parentFile.isDirectory }.getOrDefault(false)
  if (!parentReady) {
    onUnlocked("portal-server: could not create ${lock.parentFile}; starting without the store lock")
    return block()
  }
  val deadline = System.currentTimeMillis() + waitMillis
  while (true) {
    if (runCatching { lock.mkdir() }.getOrDefault(false)) break
    if (!lock.exists()) {
      // Cannot create it and it is not there: not a contention problem.
      onUnlocked("portal-server: could not take $lock; starting without the store lock")
      return block()
    }
    if (claimStaleLock(lock)) {
      onUnlocked("portal-server: taking over $lock — the process that held it is gone")
      continue
    }
    if (System.currentTimeMillis() >= deadline) onBusy(lock)
    Thread.sleep(200)
  }
  runCatching { File(lock, "pid").writeText(ProcessHandle.current().pid().toString() + "\n") }
  fun release() = runCatching { File(lock, "pid").delete(); lock.delete() }
  // Best effort for a hard kill; the ordinary path releases in the finally.
  val hook = Thread { release() }
  Runtime.getRuntime().addShutdownHook(hook)
  try {
    return block()
  } finally {
    release()
    runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
  }
}

/**
 * Clear a lock whose holder is gone, so that the caller can retry `mkdir`.
 *
 * The naive form — read the pid, see it is dead, delete the directory — can
 * delete a lock a DIFFERENT contender acquired between those two steps, and
 * then there are two writers. So the takeover is CLAIMED: the `pid` file is
 * renamed aside, which exactly one contender can do, and the claim is checked
 * to still hold the dead pid that was inspected. A live holder always writes
 * its `pid` before doing anything, and a new holder can only exist after this
 * same rename removed the old marker — so a successful, content-checked claim
 * proves this is not somebody else's live lock.
 *
 * An UNREADABLE or MISSING pid returns false: the cost of waiting is a
 * message, the cost of stealing is two writers.
 *
 * `keliver-store-recover.sh` implements the same protocol, so the shell and
 * the JVM contend correctly with each other.
 */
internal fun claimStaleLock(lock: File, betweenCheckAndClaim: () -> Unit = {}): Boolean {
  val pidFile = File(lock, "pid")
  val recorded = runCatching { pidFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
  val pid = recorded.toLongOrNull() ?: return false
  val alive = runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(true)
  if (alive) return false

  // A seam, no-op in production: the window between deciding the holder is
  // dead and claiming the marker is exactly where a racing contender can slip
  // in, and StoreLockTest drives that interleaving through here rather than
  // hoping to hit it by timing.
  betweenCheckAndClaim()

  val claim = File(lock, "pid.stale.${ProcessHandle.current().pid()}")
  if (!runCatching { pidFile.renameTo(claim) }.getOrDefault(false)) return false
  if (runCatching { claim.readText().trim() }.getOrNull() != recorded) {
    // Not the marker that was inspected: put it back and wait rather than delete.
    runCatching { claim.renameTo(pidFile) }
    return false
  }
  runCatching { lock.listFiles()?.forEach { it.delete() } }
  return runCatching { lock.delete() }.getOrDefault(false)
}

/**
 * Step 4 of the contract: the first-boot default, plus the compatibility scan
 * that keeps an app that already has a store from being handed a new one.
 *
 * The hash was canonical before this change and still is, so an existing store
 * directory can differ from the name computed today only in its SLUG — an
 * app-v2/current symlink split (U25.1), or the older Kotlin/Python slug rules.
 * Rather than mint a second identity, look for any `apps` entry whose name
 * ends in `-<hash>`:
 *
 *   * none — this really is a first boot;
 *   * one  — that is this app's store whatever it is called; use it and say so;
 *   * more — the split already happened. Picking one silently is the bug being
 *     fixed, so refuse and name them.
 */
internal fun defaultStoreDir(repoDir: File, notify: (String) -> Unit = {}): File {
  val apps = File(portalHome(), "apps")
  val hash = appStoreHash(repoDir)
  val preferred = File(apps, "${storeSlug(repoDir.absoluteFile.canonicalFile.name)}-$hash")

  // The scan runs even when `preferred` exists. Returning it on sight would
  // hide exactly the case this is for: after a symlink split BOTH names exist,
  // and one of them is the canonical one — so a short-circuit would silently
  // pick it and leave the other identity, which published bundles may verify
  // against, unmentioned.
  val existing = (apps.listFiles() ?: emptyArray())
    .filter { it.isDirectory && it.name.endsWith("-$hash") }
    .sortedBy { it.name }
  return when {
    existing.isEmpty() -> preferred
    existing.size == 1 && existing.single().name == preferred.name -> preferred
    existing.size == 1 -> existing.single().also {
      notify("portal-server: using this app's existing store $it (recorded under an older name; the default is now ${preferred.name})")
    }
    else -> throw StoreOwnershipException(
      buildString {
        appendLine("portal store split: ${existing.size} stores exist for this app.")
        existing.forEach { appendLine("  - ${it.name}   identity ${publicKeyFingerprint(it)}") }
        appendLine("  app: ${repoDir.absoluteFile.canonicalFile.path}")
        appendLine("  the name a first boot would choose today is ${preferred.name}")
        appendLine("This happens when one app was launched through more than one path — a")
        appendLine("symlink and the real directory resolved different store names before the")
        appendLine("slug was canonicalised. Nothing has been deleted.")
        appendLine("Fix: choose the store to keep (its identity is the one your published")
        appendLine("bundles verify against) and bind this app to it:")
        appendLine("  keliver-store-recover.sh ${repoDir.absoluteFile.canonicalFile.path} --store <one of the above>")
      },
    )
  }
}

/**
 * A store's identity, safe to print: SHA-256 of the PUBLIC key, first 16 hex.
 * `keys/ed25519.priv` is never read here or anywhere that reports a conflict.
 */
internal fun publicKeyFingerprint(storeDir: File): String {
  val pub = File(storeDir, "keys/ed25519.pub")
  if (!pub.isFile) return "none yet"
  val bytes = runCatching { pub.readBytes() }.getOrElse { return "unreadable" }
  val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
  return digest.take(8).joinToString("") { "%02x".format(it) }
}

/**
 * Where this repo's documents live. The contract is docs/STORE_IDENTITY.md:
 *
 *   1. PORTAL_STORE                        (the relay handles this one)
 *   2. "store" in keliver.portal.json
 *   3. <app>/.gradle/keliver-store-path    (the binding pointer)
 *   4. ~/.keliver-portal/apps/<slug>-<hash>
 *
 * Step 3 is new here. `scripts/keliver-store-path.sh` has always documented and
 * implemented it — this function, the authority it claims to mirror, did not —
 * and that gap is U23: the pointer moves with a renamed directory, so reading
 * it is what lets a moved app still find its own identity instead of minting a
 * fresh one. Whether it may then USE it is [claimStoreFor]'s decision.
 */
fun PortalConfig.storeDir(repoDir: File, notify: (String) -> Unit = {}): File {
  store?.let { s ->
    return when {
      s.startsWith("~/") -> File(System.getProperty("user.home"), s.removePrefix("~/"))
      File(s).isAbsolute -> File(s)
      else -> File(repoDir, s)
    }
  }
  val pointer = storePointerFile(repoDir)
  if (pointer.isFile) {
    val pointed = runCatching { pointer.readText().trim() }.getOrDefault("")
    if (pointed.isNotEmpty()) return File(pointed)
  }
  return defaultStoreDir(repoDir, notify)
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

  // Which refusal this is depends on how we got here. If this app's own
  // pointer names this store, the app moved (or was copied); if not, two
  // different apps are aiming at one store. Both refuse — the pointer cannot
  // tell a move from a `cp -a`, so adopting on sight would hand a copy someone
  // else's signing key — but the instruction differs, and the old one sent
  // people into U24: it told them to point "store" at the directory holding
  // their identity, which is exactly the claim being refused here.
  val pointed = runCatching {
    val f = storePointerFile(repoDir)
    f.isFile && File(f.readText().trim()).absoluteFile.canonicalFile == storeDir.absoluteFile.canonicalFile
  }.getOrDefault(false)
  val ownerExists = theirs.isNotEmpty() && File(theirs).isDirectory

  throw StoreOwnershipException(
    buildString {
      if (pointed) {
        appendLine("portal store: this app has moved.")
        appendLine("  store: $storeDir")
        appendLine("  identity: ${publicKeyFingerprint(storeDir)}")
        appendLine("  it is recorded as belonging to: ${theirs.ifEmpty { "(unknown — the marker is empty)" }}")
        appendLine("  this app is now at:             $me")
        appendLine("Nothing has been deleted, and no new signing identity has been created.")
        appendLine("The store above still holds this app's keys, documents and bundles.")
        appendLine("If this app was MOVED or RENAMED, rebind it — keys and documents are")
        appendLine("preserved and nothing is copied:")
        appendLine("  keliver-store-recover.sh $me")
        appendLine("If this is a COPY of that app and should be independent, drop the")
        appendLine("pointer it inherited and it will start its own store:")
        appendLine("  rm ${storePointerFile(repoDir)}")
      } else {
        appendLine("portal store conflict: $storeDir already belongs to another app.")
        appendLine("  owner: ${theirs.ifEmpty { "(unknown — the marker exists but is empty)" }}")
        appendLine("  this:  $me")
        appendLine("Two apps must not share one document store — the boot scan retires")
        appendLine("mirrors that do not match the app it is serving, which would delete the")
        appendLine("other app's documents, and opening a foreign screen writes its .kt into")
        appendLine("this app's source tree.")
        if (ownerExists) {
          appendLine("The recorded owner still exists on disk, so these are two live apps.")
          appendLine("Fix: remove \"store\" from this app's keliver.portal.json and let it take")
          appendLine("a store of its own, or point it at a directory this app alone uses.")
        } else {
          appendLine("The recorded owner is not on disk — but an absent path is not proof that")
          appendLine("this app is the one that left it, so the store is not reclaimed for you.")
          appendLine("If this IS that app under a new path, rebind it explicitly:")
          appendLine("  keliver-store-recover.sh $me --store $storeDir")
          appendLine("Otherwise remove \"store\" from keliver.portal.json to get a store of its own.")
        }
      }
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
