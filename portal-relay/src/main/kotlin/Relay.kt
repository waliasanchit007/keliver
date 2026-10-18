/*
 * spike/keliver-web portal — the local-first PORTAL SERVER (P2).
 * Evolved from the M2 tree relay: a JDK HttpServer (still no deps) that now
 * persists projects/screens/drafts as files under ~/.keliver-portal/ so a
 * browser refresh never loses work.
 *
 * Endpoints (all CORS-open; the browser editor drives them):
 *   GET  /projects                          -> ["default", ...]
 *   POST /projects            body=name     -> 204 (creates project + "main" screen)
 *   GET  /screens?project=P                 -> ["main", ...]
 *   POST /screens?project=P   body=name     -> 204
 *   GET  /draft?project=P&screen=S          -> stored tree JSON or {}
 *   PUT  /draft?project=P&screen=S body=json-> 204
 *   POST /active?project=P&screen=S         -> 204 (which screen the device mirrors)
 *   GET|POST /tree                          -> LEGACY, unchanged shape: the ACTIVE
 *                                              screen's draft. The device guest keeps
 *                                              polling GET /tree exactly as before.
 *
 * Store: ~/.keliver-portal/<project>/<screen>.json ; active screen in
 * ~/.keliver-portal/active (two lines: project, screen). default/main auto-created.
 * Binds 0.0.0.0 so the emulator reaches it at 10.0.2.2:8077.
 */
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.keliver.portal.deserializeTree
import dev.keliver.portal.document.DocJson
import dev.keliver.portal.document.OpBatch
import dev.keliver.portal.document.withoutConditionals
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.serializeTree
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * The app repo checkout the portal serves (and the publish step compiles in).
 * Resolution (P2-9): PORTAL_REPO env → nearest ancestor of cwd holding a
 * keliver.portal.json → cwd. The startup banner names which source won.
 */
private fun discoverRepoUpward(): File? {
  var d: File? = File(System.getProperty("user.dir"))
  repeat(8) {
    val dir = d ?: return null
    if (File(dir, "keliver.portal.json").exists()) return dir
    d = dir.parentFile
  }
  return null
}

private val repoResolution: Pair<String, File> = run {
  val env = System.getenv("PORTAL_REPO")
  if (env != null) return@run "PORTAL_REPO env" to File(env)
  val discovered = discoverRepoUpward()
  if (discovered != null) return@run "keliver.portal.json discovered at $discovered" to discovered
  "cwd (no keliver.portal.json found upward)" to File(System.getProperty("user.dir"))
}
private val repoDirSource = repoResolution.first
private val repoDir = repoResolution.second

/** Separability: the repo's keliver.portal.json (all fields default to this repo's layout). */
private val config = loadPortalConfig(repoDir)
private val configFileAtStart = File(repoDir, "keliver.portal.json").isFile

private val PORT = config.port

private val root = resolveStore()

/**
 * The store, claimed for this repo. PORTAL_STORE is an explicit override for
 * one run (CI, a throwaway trial); it used to be silently ignored, which reads
 * as "isolated" and is not.
 */
private fun resolveStore(): File {
  val env = System.getenv("PORTAL_STORE")?.takeIf { it.isNotBlank() }

  // RESOLUTION HAPPENS INSIDE THE LOCK. It used to happen before it, so a
  // startup that waited on a recovery in flight went on to claim the store it
  // had resolved BEFORE that recovery ran — and then wrote that stale answer
  // back into the pointer, undoing the rebinding it had just waited for.
  // Selecting, claiming and recording the binding are one transaction.
  var resolved: File? = null
  val failure: String? = withStoreLock(
    repoDir,
    onTakeover = ::println,
    onWaiting = { lock ->
      println(
        "portal-server: waiting for a store recovery to finish before resolving the store " +
          "(lock: $lock)",
      )
    },
    onBusy = { lock ->
      System.err.println(
        "portal-server: a store recovery is in progress for this app (lock: $lock).\n" +
          "  Wait for keliver-store-recover.sh to finish and start again. If nothing is\n" +
          "  running, remove that directory.",
      )
      kotlin.system.exitProcess(70)
    },
    // No unlocked fallback. Claiming a store and writing the pointer are the
    // two writes the lock exists to serialize, so a lock that cannot be taken
    // means this process does not do them.
    onUnavailable = { lock, why ->
      System.err.println(
        "portal-server: the store lock is unavailable, so the portal will not start.\n" +
          "  $why\n" +
          "  lock: $lock\n" +
          "  Starting without it would let a store recovery and this startup write the\n" +
          "  binding at the same time. Make the app directory writable and try again.",
      )
      kotlin.system.exitProcess(70)
    },
  ) {
    try {
      val dir = if (env != null) File(env).absoluteFile else config.storeDir(repoDir, ::println)

      // VALIDATE THE POINTER DESTINATION BEFORE CLAIMING ANYTHING. Claiming
      // first and discovering the pointer is unwritable afterwards left an
      // empty, owner-marked apps/<slug>-<hash> behind — a store nobody can use
      // and, because the hash is the app's, a permanent second candidate that
      // makes a later symlink launch refuse as a split. Measured: a failed
      // start created apps/app-470fafae with owner set and no keys.
      if (env == null) {
        storePointerObstacle(repoDir)?.let { return@withStoreLock it }
      }

      // Whether THIS startup created them decides what it may undo below.
      val storeExisted = dir.isDirectory
      val ownerExisted = File(dir, "owner").isFile
      dir.mkdirs()
      claimStoreFor(dir, repoDir)
      // PORTAL_STORE is a ONE-RUN override, so it must not rebind the app.
      // Other consumers already see it: PORTAL_STORE is step 1 of the shell
      // resolver too, and it is an environment variable, so a build in the
      // same environment resolves it without any pointer.
      val pointerFailure = if (env == null) writeStorePointer(repoDir, dir) else null
      if (pointerFailure != null) {
        // The window between validating and writing is real — a directory can
        // be replaced in it — so undo exactly what this startup created and
        // nothing else. An identity that was already here is never touched.
        undoStartupClaim(dir, repoDir, storeExisted, ownerExisted)?.let(::println)
        pointerFailure
      } else {
        resolved = dir
        null
      }
    } catch (e: StoreOwnershipException) {
      // A refusal is an answer, not a crash. This used to surface as an
      // ExceptionInInitializerError stack trace with the actionable part
      // buried in the middle of it. Reported AFTER the lock is released.
      e.message ?: "portal store: refused"
    }
  }
  if (failure != null) {
    System.err.println(failure)
    kotlin.system.exitProcess(70)
  }
  val dir = resolved!!
  legacyStoreOrNull(dir)?.let { println(it.describe()) }
  return dir
}

/**
 * Tell the rest of the toolchain where this repo's store is.
 *
 * The publisher signs with `<store>/keys/ed25519.priv` and the device hosts
 * embed `<store>/keys/ed25519.pub`, but they are Gradle builds that cannot ask
 * the relay. Rather than duplicating the path derivation in three build files,
 * the relay records the resolved path and they read it. `.gradle/` because it
 * is build state, is gitignored by convention, and survives `clean`.
 *
 * It is also the app half of the ownership binding (docs/STORE_IDENTITY.md):
 * it travels with a renamed directory, which is how a moved app still finds
 * its own store. NOT written for a PORTAL_STORE run — that override is for one
 * run and must not rebind the app, and a build in the same environment reads
 * the variable directly.
 */
/**
 * Is there anything about the pointer's destination that will stop it being
 * written? Returns the refusal, or null when it looks writable. Checked BEFORE
 * the store is claimed, so a start that cannot finish leaves nothing behind.
 */
private fun storePointerObstacle(repo: File): String? {
  val gradleDir = File(repo, ".gradle")
  val pointer = File(gradleDir, "keliver-store-path")
  val why = when {
    gradleDir.exists() && !gradleDir.isDirectory -> "$gradleDir exists and is not a directory"
    !gradleDir.isDirectory && !runCatching { gradleDir.mkdirs() }.getOrDefault(false) ->
      "$gradleDir could not be created"
    pointer.exists() && pointer.isDirectory -> "$pointer is a directory, not a file"
    pointer.exists() && !pointer.canWrite() -> "$pointer is not writable"
    else -> {
      // A probe, not a permission bit: the failure modes that matter here are
      // the ones `canWrite` does not model.
      val probe = File(gradleDir, ".keliver-pointer-probe.${ProcessHandle.current().pid()}")
      val ok = runCatching { probe.writeText("probe\n"); probe.delete(); true }.getOrDefault(false)
      runCatching { probe.delete() }
      if (ok) null else "$gradleDir is not writable"
    }
  }
  return why?.let {
    "portal-server: the store pointer cannot be written, so the portal will not start.\n" +
      "  $it\n" +
      "  No store has been claimed or created: this is checked before the store is\n" +
      "  touched. (The app's .gradle directory may have been created to test it.)\n" +
      "  Make that path writable — the pointer must be a regular file — and start again."
  }
}

/**
 * Undo what this startup created, and only that. Returns a note to print, or
 * null when there was nothing of ours to remove.
 */
private fun undoStartupClaim(dir: File, repo: File, storeExisted: Boolean, ownerExisted: Boolean): String? {
  if (ownerExisted || storeExisted) return null
  val owner = File(dir, "owner")
  // Only if it still names us — someone else may have taken it in the interim.
  val mine = runCatching { owner.readText().trim() == repo.absoluteFile.canonicalFile.path }
    .getOrDefault(false)
  if (!mine) return null
  runCatching { owner.delete() }
  // Only an EMPTY directory: never delete a store that has acquired contents.
  val removed = runCatching { (dir.listFiles()?.isEmpty() ?: false) && dir.delete() }.getOrDefault(false)
  return if (removed) {
    "portal-server: removed the store $dir that this failed start had just created " +
      "(it held no identity and would otherwise have read as a second store for this app)"
  } else {
    "portal-server: note — $dir was created by this failed start and could not be removed; " +
      "it holds no identity, and it can be deleted safely"
  }
}

private fun writeStorePointer(repo: File, store: File): String? {
  val f = File(File(repo, ".gradle"), "keliver-store-path")
  val line = store.absolutePath + "\n"
  // TEST-ONLY, inert unless set to 1: the window between validating the
  // destination and writing it is real (a directory can be replaced in it) but
  // cannot be hit from outside, and an untested rollback is not a rollback.
  if (System.getenv("KELIVER_RELAY_FAIL_POINTER") == "1") {
    return "portal-server: the store pointer could not be written (fault injection).\n" +
      "  $f\n" +
      "  Anything this start had just created has been removed."
  }
  return runCatching {
    f.parentFile.mkdirs()
    if (!f.exists() || f.readText() != line) f.writeText(line)
    check(f.readText() == line) { "the pointer does not read back as written" }
    null
  }.getOrElse { e ->
    // Not a warning. The pointer is half the binding: the publisher signs with
    // the key in the store it names and the device hosts embed the public key
    // from there, so a relay that serves without it hands the next build a
    // different identity. This used to print and carry on.
    "portal-server: the store pointer could not be written, so the portal will not start.\n" +
      "  $f\n  $e\n" +
      "  The store itself is unchanged and still belongs to this app. Make that path\n" +
      "  writable (it must be a regular file, not a directory) and start again."
  }
}
private val activeFile = File(root, "active")
private val keysDir = File(root, "keys")
private val bundlesDir = File(root, "bundles")
private val httpReplayService = HttpReplayService(repoDir, config)
private val httpRecordingService = HttpRecordingService(
  repoDir = repoDir,
  config = config,
  storeDir = root,
  enabledByEnvironment = System.getenv("PORTAL_HTTP_RECORD") == "1",
)

/** project/screen names are path segments — restrict to a safe charset. */
private fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unnamed" }

private fun screenFile(project: String, screen: String) = File(File(root, safe(project)), "${safe(screen)}.json")

private fun projectNames(): List<String> =
  root.listFiles { f -> f.isDirectory && f.name !in RESERVED_ROOT_DIRS }
    ?.map { it.name }?.sorted() ?: emptyList()

private fun activeScreen(): Pair<String, String> {
  val lines = runCatching { activeFile.readLines() }.getOrDefault(emptyList())
  return (lines.getOrNull(0) ?: "default") to (lines.getOrNull(1) ?: "main")
}

private fun setActive(project: String, screen: String) {
  activeFile.writeText("${safe(project)}\n${safe(screen)}\n")
}

private fun screenNames(project: String): List<String> =
  File(root, safe(project)).listFiles { f -> f.name.endsWith(".json") }
    ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()

/**
 * Seed an empty project and point `active` at a screen that really exists.
 *
 * Runs AFTER [bootScan], so it can see the app's actual screens. Previously
 * [ensureDefaults] created a `main` screen and activated it before the repo
 * was scanned; `keliver-init` scaffolds `home.kt`, so an adopter's first view
 * was an empty phantom `main` with their real screen sitting unselected in a
 * dropdown that showed no selection at all.
 */
private fun reconcileStore() {
  val projects = projectNames()
  val (project, screen) = activeScreen()
  val p = if (project in projects) project else projects.firstOrNull() ?: "default"

  var screens = screenNames(p)
  if (screens.isEmpty()) {
    // Genuinely empty app: give it something to edit, as before.
    val main = screenFile(p, "main")
    main.parentFile.mkdirs()
    if (!main.exists()) main.writeText("{}")
    screens = listOf("main")
  }
  if (p == project && screen in screens) return

  val target = screens.first()
  setActive(p, target)
  println("portal-server: active screen '$project/$screen' not found; selected '$p/$target'")
}

private fun ensureDefaults() {
  root.mkdirs()
  screenFile("default", "main").parentFile.mkdirs()
  // NOTE: deliberately does NOT seed a `main` screen here. It used to, before
  // anything was known about the repo, and then pointed `active` at it — so an
  // app scaffolded by `keliver-init` (which writes home.kt, not main.kt) opened
  // the editor on an empty phantom document with its real screen unselected.
  // Seeding and activation now happen in [reconcileStore], after the boot scan
  // knows which screens actually exist.
  ensureKeys()
  bundlesDir.mkdirs()
}

private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

/**
 * P4: the project signing keypair. Created, and its protection checked, by
 * SigningKeys.kt (U27). A key that is not owner-only, or half a key pair (U29),
 * is reported here on every start and refused by [publish]; neither is ever
 * changed here.
 */
private fun ensureKeys() {
  val generated = try {
    ensureSigningKeys(keysDir)
  } catch (e: SigningKeyException) {
    System.err.println("portal-server: ${e.message}")
    kotlin.system.exitProcess(78)
  } catch (e: java.io.IOException) {
    System.err.println("portal-server: could not create this app's signing key in $keysDir: $e. Nothing was replaced.")
    kotlin.system.exitProcess(78)
  }
  if (generated) {
    println(
      "portal-server: generated this app's Ed25519 signing key in $keysDir " +
        "($PRIVATE_KEY_FILE ${modeString(File(keysDir, PRIVATE_KEY_FILE))}, keys/ ${modeString(keysDir)}, as read back)",
    )
  }
  keyProblem()?.let { System.err.println("portal-server: WARNING — $it") }
}

/**
 * Why the relay must not publish with this app's identity, or null when it may.
 * A store with neither key file is not this check's business: that publish was,
 * and still is, an unsigned bundle. This gates the relay's `/publish` only; a
 * Gradle build run directly signs with whatever key is in the store.
 */
private fun keyProblem(protection: KeyProtection? = keyProtection(keysDir)): String? = when (protection) {
  null, KeyProtection.OwnerOnly -> null
  is KeyProtection.Exposed -> buildString {
    appendLine("this app's signing identity is not owner-only: ${protection.detail}.")
    appendLine("  The private key signs every bundle this app's production hosts accept, so the relay will not")
    appendLine("  publish until it is.")
    if (protection.chmodFixes) {
      appendLine("  To keep this identity and make it owner-only (modes only; nothing is rotated):")
      appendLine("    ${tightenCommands(keysDir)}")
    } else {
      appendLine("  No chmod can fix this. The store has to live where only you own and can write it, on a")
      appendLine("  filesystem that enforces permissions (docs/STORE_IDENTITY.md §2: how a store is located).")
    }
    append("  That does not undo earlier exposure. If other users of this machine are not trusted, whether to ")
    append("rotate the key is your decision; Keliver never rotates it (docs/KNOWN_BUGS.md U27).")
  }
  is KeyProtection.Unverified -> buildString {
    appendLine("this app's signing identity cannot be checked: ${protection.detail}.")
    append("  The relay publishes only with a key it has measured as owner-only, so it will not publish. ")
    append("Move this app's store to a filesystem with POSIX permissions (PORTAL_STORE, or \"store\" in keliver.portal.json).")
  }
  is KeyProtection.Incomplete -> buildString {
    appendLine(protection.detail)
    append("  The relay will not publish with half an identity. Editing is unaffected.")
  }
}

// ---------------------------------------------------------------------------
// P4 publish pipeline

private fun nextBundleVersion(): Int =
  (bundlesDir.listFiles { f -> f.isDirectory && f.name.startsWith("v") }
    ?.mapNotNull { it.name.removePrefix("v").toIntOrNull() }?.maxOrNull() ?: 0) + 1

/**
 * M6: publish compiles the CANONICAL app project as-is (screens/ are already
 * the source of truth in git; logic/ + entry are hand-owned) -> sign -> store.
 */
private fun publish(): Pair<Boolean, String> {
  val log = StringBuilder()
  val (p, s) = activeScreen()
  val canonical = File(screensDirFor(p), "$s.kt")
  if (!canonical.exists()) return false to "nothing to publish: no canonical screen at $canonical"
  // Publish-safety gate: a contract member still marked TODO(portal) is a portal
  // binding the presenter never implemented — it would ship rendering the
  // defaulted no-op. Drafts must not reach prod; reject and name the members.
  val unimpl = dev.keliver.portal.ingest.ContractWriteBack.unimplementedMembers(canonical.readText())
  if (unimpl.isNotEmpty()) {
    return false to buildString {
      appendLine("publish REJECTED: ${unimpl.size} contract member(s) still TODO(portal) — implement them in the presenter (or remove the binding) before shipping to prod:")
      unimpl.forEach { appendLine("  • $it") }
    }
  }
  // U27: measured now, not at startup — the mode can change while the relay runs.
  keyProblem()?.let { return false to "publish REFUSED: $it\nNothing was compiled or signed." }
  log.appendLine("publish: compiling the canonical project (screens/${canonical.name} + hand-owned logic/)")

  // The publish task and output come from keliver.portal.json as it is now (PublishConfig).
  val current = PublishConfig.current(repoDir, configFileAtStart).getOrElse {
    return false to log.appendLine("publish REFUSED: keliver.portal.json cannot be read: ${it.message}\nNothing was compiled or signed.").toString()
  }
  PublishConfig.restartNeeded(config, current).takeIf { it.isNotEmpty() }?.let {
    log.appendLine(
      "publish: keliver.portal.json changed since the relay started. Restart the relay for these to take effect: " +
        "${it.joinToString()}. (publishTask and publishOutput are read at every publish.)",
    )
  }
  if (current.publishTask != config.publishTask) log.appendLine("publish: publishTask is now ${current.publishTask}")
  if (current.publishOutput != config.publishOutput) log.appendLine("publish: publishOutput is now ${current.publishOutput}")

  // W4: the bundle is signed for the version it will be stored as. The relay
  // numbers its bundles in publish order, so its sequence is the version.
  val version = nextBundleVersion()
  val gradlew = File(repoDir, "gradlew").absolutePath
  fun build(): Pair<Int, String> {
    val proc = ProcessBuilder(gradlew, current.publishTask, "-Pkeliver.sequence=$version", "--console=plain")
      .directory(repoDir)
      .redirectErrorStream(true)
      .start()
    val out = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    log.appendLine(out.lines().filter { it.isNotBlank() }.takeLast(30).joinToString("\n"))
    // The response keeps the last 30 lines; the whole output goes to a file (PublishLogs).
    runCatching { PublishLogs.save(root, version, out) }
      .onSuccess { log.appendLine("publish: full Gradle output: ${it.absolutePath}") }
      .onFailure { log.appendLine("publish: the full Gradle output could not be saved: $it") }
    return code to out
  }
  // U34: a Kotlin compiler crash that a second run has always passed. Once more, never twice.
  val (code, retried) = PublishRetry.run(
    onRetry = { log.appendLine("publish: the Kotlin compiler crashed (Internal compiler error, KNOWN_BUGS U34); building once more") },
    build = ::build,
  )
  if (code != 0) {
    return false to log.appendLine("publish FAILED${if (retried) " after one retry" else ""} (gradle exit $code)").toString()
  }

  val ziplineOut = File(repoDir, current.publishOutput)
  if (!ziplineOut.exists()) return false to log.appendLine("publish FAILED: no zipline output at $ziplineOut").toString()
  // Never store a bundle that every production host would refuse. The relay
  // does not sign; the compile task does, and when its signing block is
  // missing, above `kotlin {}`, or cannot find the store, it still succeeds and
  // writes an UNSIGNED bundle. That used to be reported as "publish OK".
  val manifest = File(ziplineOut, "manifest.zipline.json")
  val publicKey = File(keysDir, "ed25519.pub")
  val unsignedWhy = when {
    !manifest.isFile -> "no manifest.zipline.json in $ziplineOut"
    !publicKey.isFile -> "no public key at $publicKey"
    else -> publishedSignatureProblem(manifest.readText(), publicKey.readText())
  }
  if (unsignedWhy != null) {
    return false to log.appendLine("publish REFUSED: $unsignedWhy.").appendLine(
      """
      |  Nothing was stored. The relay does not sign: `${current.publishTask}` must, with
      |  ${File(keysDir, "ed25519.priv")} (never printed). In a keliver-init app, run
      |  keliver-new-publish-target.sh once: it writes publishTask, publishOutput and the
      |  signing block. That block must stay BELOW `kotlin {}`, and the build finds the
      |  store through the tools bundle, so start the portal with keliver-portal (or
      |  set KELIVER_TOOLS_BIN to the bundle's bin/).
      """.trimMargin(),
    ).toString()
  }
  log.appendLine("publish: the manifest is signed with this app's $PORTAL_SIGNING_KEY_NAME key")
  // Once a bundle in this store carries a signed sequence, an unsequenced one is
  // refused: hosts with rollback protection would refuse it and show no bundle.
  val storeIsSequenced = bundlesDir.listFiles { f -> f.isDirectory && f.name.startsWith("v") }.orEmpty()
    .any { dir -> File(dir, "manifest.zipline.json").takeIf { it.isFile }?.let { signedSequenceText(it.readText()) } != null }
  when (val signedSequence = signedSequenceText(manifest.readText())) {
    null -> if (storeIsSequenced) {
      return false to log.appendLine(
        "publish REFUSED: the manifest carries no signed $SEQUENCE_METADATA_KEY, but this app has published sequenced " +
          "bundles: hosts with rollback protection would refuse it and show no bundle. Its signing block predates W4; run " +
          "keliver-new-publish-target.sh to replace it. Nothing was stored.",
      ).toString()
    } else log.appendLine(
      "publish: WARNING the manifest carries no signed $SEQUENCE_METADATA_KEY (its signing block predates W4). " +
        "A host with rollback protection refuses it once it has run a sequenced bundle; run " +
        "keliver-new-publish-target.sh to replace the block.",
    )
    version.toString() -> log.appendLine("publish: signed for sequence $version")
    else -> return false to log.appendLine(
      "publish REFUSED: the manifest is signed for sequence $signedSequence, but this bundle would be v$version. " +
        "Nothing was stored.",
    ).toString()
  }
  val dest = File(bundlesDir, "v$version")
  ziplineOut.copyRecursively(dest, overwrite = true)
  // M6: the audit hash is of the CANONICAL screen source (what actually compiled).
  val srcHash = hex(MessageDigest.getInstance("SHA-256").digest(canonical.readBytes())).take(16)
  // M7: the app project declares required host capabilities beside its screens.
  val capsFile = File(screensDirFor(p), "capabilities.txt")
  val caps = if (capsFile.exists()) {
    capsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
  } else {
    emptyList()
  }
  val capsJson = caps.joinToString(",") { "\"$it\"" }
  File(dest, "meta.json").writeText(
    "{\"version\":$version,\"widgetVersion\":1,\"capabilities\":[$capsJson],\"project\":\"$p\",\"screen\":\"$s\",\"srcHash\":\"$srcHash\",\"createdAt\":${System.currentTimeMillis()}}",
  )
  log.appendLine("publish OK: bundle v$version (widgetVersion=1, srcHash=$srcHash) -> $dest")
  return true to log.toString()
}

private fun query(ex: HttpExchange): Map<String, String> =
  (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
    val (k, v) = it.split('=', limit = 2)
    k to URLDecoder.decode(v, "UTF-8")
  }

private fun respond(ex: HttpExchange, code: Int, body: String? = null, contentType: String = "application/json") {
  if (body == null) { ex.sendResponseHeaders(code, -1); ex.close(); return }
  val bytes = body.encodeToByteArray()
  ex.responseHeaders.add("Content-Type", contentType)
  ex.sendResponseHeaders(code, bytes.size.toLong())
  ex.responseBody.use { it.write(bytes) }
}

private fun readBoundedBody(ex: HttpExchange, maxBytes: Int): String {
  val bytes = ex.requestBody.readNBytes(maxBytes + 1)
  require(bytes.size <= maxBytes) { "request body exceeds $maxBytes bytes" }
  return bytes.decodeToString()
}

private fun jsonList(items: List<String>): String =
  items.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }

// Every request reaches a handler only through RelayAccess.admit (the server is
// created through RelayAccess.gate), which also sets the CORS headers for an
// admitted loopback origin. A preflight that gets here was admitted.
private fun handle(ex: HttpExchange, block: () -> Unit) {
  if (ex.requestMethod == "OPTIONS") { respond(ex, 204); return }
  runCatching(block).onFailure {
    // A named screen that does not exist is a client error, and saying so is
    // the point: it used to be answered by inventing the screen.
    val code = if (it is UnknownScreen) 404 else 500
    println("portal-server: ${ex.requestURI} failed: $it")
    runCatching { respond(ex, code, "{\"error\":\"${it.message?.replace("\"", "'")}\"}") }
  }
}

private fun handleRecording(ex: HttpExchange, block: () -> Unit) {
  val origin = ex.requestHeaders.getFirst("Origin")
  val loopback = ex.remoteAddress.address?.isLoopbackAddress == true
  if (ex.requestMethod == "OPTIONS") {
    if (!httpRecordingService.enabled || !loopback || !httpRecordingService.isAllowedOrigin(origin)) {
      respond(ex, 404)
      return
    }
    // RelayAccess.admit already named the (loopback) origin; narrow what it allows.
    ex.responseHeaders.set("Access-Control-Allow-Methods", "POST, OPTIONS")
    ex.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, X-Portal-Record-Token")
    respond(ex, 204)
    return
  }
  val suppliedToken = ex.requestHeaders.getFirst("X-Portal-Record-Token")
  if (!httpRecordingService.authorize(ex.remoteAddress.address, origin, suppliedToken)) {
    respond(ex, 404)
    return
  }
  runCatching(block).onFailure {
    println("portal-server: HTTP recording request failed safely")
    runCatching { respond(ex, 500, """{"error":"recording_failed","reason":"internal"}""") }
  }
}

private fun respondRecording(ex: HttpExchange, result: HttpRecordingResult) {
  when (result) {
    is HttpRecordingResult.Success -> respond(ex, result.status, result.body)
    is HttpRecordingResult.Failure -> respond(
      ex,
      result.status,
      httpRecordingService.errorJson(result.error),
    )
  }
}

// ── V2 M1: the live document engine ─────────────────────────────────────────

private val documents = java.util.concurrent.ConcurrentHashMap<String, DocumentService>()

/**
 * M6 app project model: the "default" project's canonical screens live INSIDE
 * the guest Gradle module (git-versioned); other projects use the legacy dir.
 */
private val appScreensDir = File(repoDir, config.screensDir)
private fun screensDirFor(project: String): File =
  if (project == "default" && appScreensDir.parentFile.exists()) appScreensDir
  else File(File(root, "kotlin"), project)

/** C1: project components live next to screens (default project = the repo dir). */
private val appComponentsDir = File(repoDir, config.resolvedComponentsDir())

/** #13 F1: flow declarations live next to screens too (flow{} DSL files). */
private val appFlowsDir = File(repoDir, config.resolvedFlowsDir())
private fun flowsDirFor(project: String): File =
  if (project == "default") appFlowsDir
  else File(screensDirFor(project).parentFile, "flows")
// ── P3-12: live-preview rebuild orchestration ───────────────────────────────

private val previewBuilder = PreviewBuilder(
  PreviewDistributionRunner(repoDir, config),
  enabled = config.previewBuildTask.isNotBlank(),
)

private fun componentsDirFor(project: String): File =
  if (project == "default") appComponentsDir
  else File(screensDirFor(project).parentFile, "components")

/**
 * K2: extra design-token dirs (keliver.portal.json `tokensDirs`, relative to
 * the repo or absolute); the screens and components dirs are scanned anyway.
 * A missing one is skipped with a note — the relay never creates it in the
 * app's tree. They belong to the "default" project (the app); a legacy store
 * project scans only its own screens/components dirs.
 */
private val appTokensDirs: List<File> = config.resolvedTokensDirs()
  .map { File(it).let { f -> if (f.isAbsolute) f else File(repoDir, it) } }
  .filter { d ->
    d.isDirectory.also { ok -> if (!ok) println("portal-server: tokensDirs entry $d does not exist — skipped") }
  }

/** K2: a file's token identity — its path relative to the app repo. */
private fun tokenFileId(f: File): String = Tokens.fileId(f, repoDir)

/** K2: rescan [project]'s design tokens; true when anything resolution can observe changed. */
private fun rebuildTokens(project: String): Boolean = Tokens.rebuild(
  project,
  flatDirs = listOf(screensDirFor(project), componentsDirFor(project)),
  deepDirs = if (project == "default") appTokensDirs else emptyList(),
  root = repoDir,
)

/**
 * The package a NEWLY created screen file should declare.
 *
 * This was hardcoded to `dev.keliver.portalpublished.screens` — this repo's own
 * dogfood package — for every app whose screens dir is the configured one,
 * which is every adopter. A screen created by the portal therefore landed in
 * the consumer's source tree declaring Keliver's internal namespace, which does
 * not match their directory and does not compile. Infer it from a sibling
 * screen instead; fall back to the dogfood package only when there is nothing
 * to learn from.
 */
private fun screensPackageFor(project: String): String {
  val dir = screensDirFor(project)
  val sibling = dir.listFiles { f -> f.name.endsWith(".kt") }
    ?.sortedBy { it.name }
    ?.firstNotNullOfOrNull { f ->
      Regex("""^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""", RegexOption.MULTILINE)
        .find(runCatching { f.readText() }.getOrDefault(""))
        ?.groupValues?.get(1)
    }
  return sibling ?: "dev.keliver.portalpublished.screens"
}

private fun screenFunctionName(screen: String): String =
  screen.replaceFirstChar { it.uppercase() } + "Screen"

/** Raised when a request names a screen the app does not have. */
class UnknownScreen(val project: String, val screen: String, val known: List<String>) :
  IllegalArgumentException(
    "no screen '$screen' in project '$project'" +
      if (known.isEmpty()) " (this app has no screens yet)"
      else "; known screens: ${known.joinToString(", ")}",
  )

/**
 * True when the screen exists either as a store document or as a `.kt` in the
 * app's screens dir. The `.kt` check matters on a cold store: the boot scan has
 * ingested by then, but a screen added while the relay is up must still resolve.
 */
private fun knownScreen(project: String, screen: String): Boolean =
  screenFile(project, screen).exists() ||
    File(screensDirFor(project), "$screen.kt").exists() ||
    documents.containsKey("$project/$screen")

/** One engine per screen; projection feeds the EXISTING draft file (devices + /tree unchanged). */
private fun docFor(q: Map<String, String>): DocumentService {
  // Default to the ACTIVE screen, not a literal "main". These defaults were
  // independent of /active, so a parameterless GET /doc addressed a screen
  // named "main" whether or not the app had one — and because the engine
  // materializes its backing file, that single read CREATED main.kt (plus a
  // Compiled_main.kt) inside the consumer's screens directory, in a package
  // that is not theirs. An adopter got junk in their git working tree from
  // merely opening the editor.
  val (activeProject, activeScreenName) = activeScreen()
  val project = safe(q["project"] ?: activeProject)
  val screen = safe(q["screen"] ?: activeScreenName)
  // A screen this app does not have is an ERROR, not an invitation to invent
  // one. Minting on read is how a foreign screen name — from a shared store, a
  // stale link, or a typo — ended up materialised as <screen>.kt plus
  // Compiled_<screen>.kt inside an app's source tree. The engine writes its
  // backing file, so "just reading" a document creates source.
  if (!knownScreen(project, screen)) throw UnknownScreen(project, screen, screenNames(project))
  return documents.getOrPut("$project/$screen") {
    val f = screenFile(project, screen)
    val inProject = screensDirFor(project) == appScreensDir
    DocumentService.fromFileOrTree(
      screenKey = "$project/$screen",
      treeJson = if (f.exists()) f.readText() else null,
      onProjected = { tree ->
        f.parentFile.mkdirs()
        f.writeText(serializeTree(tree))
      },
      kotlinFile = File(screensDirFor(project), "$screen.kt"),
      functionName = screenFunctionName(screen),
      packageName = if (inProject) screensPackageFor(project) else null,
      components = { Components.registry(project) },
      tokens = { Tokens.table(project) },
      tokenFileId = tokenFileId(File(screensDirFor(project), "$screen.kt")),
      tokensVersion = { Tokens.version(project) },
    ).also { it.ensureKotlinFile() }
  }
}

private fun session(ex: HttpExchange): String =
  ex.requestHeaders.getFirst("X-Portal-Session") ?: "anon"

// ── V2 M3: the .kt files are the source of truth — watch + ingest ──────────

private val ingestExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
  Thread(r, "kt-ingest").apply { isDaemon = true }
}
private val pendingIngest = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

/**
 * P2-8/9: the watcher only reacts to file EVENTS (and macOS `touch` fires
 * none), so screens created or edited while the relay was down never appeared.
 * At boot: ingest every screen .kt in the app screens dir, and retire store
 * mirrors (~store/default/<screen>.json) whose .kt no longer exists — stale
 * mirrors from past sessions otherwise shadow the /screens picker forever.
 */
private fun bootScan() {
  // K2: design tokens first — component bodies and screens reference them.
  rebuildTokens("default")
  println("portal-server: tokens boot scan -> ${Tokens.table("default").offered.size} token(s)")
  // Legacy store projects (their screens under <store>/kotlin/<project>) get their tables too.
  File(root, "kotlin").listFiles { f -> f.isDirectory }?.forEach { d -> runCatching { rebuildTokens(d.name) } }
  // C1: components FIRST so screens recognize their calls on the first pass.
  if (appComponentsDir.exists()) {
    val reg = Components.rebuild("default", appComponentsDir, Tokens.table("default"), ::tokenFileId)
    println("portal-server: components boot scan -> ${reg.names().sorted()} from $appComponentsDir")
  }
  // #13 F1: flow declarations (screens don't depend on them; order is free).
  if (appFlowsDir.exists()) {
    val flows = Flows.rebuild("default", appFlowsDir)
    println("portal-server: flows boot scan -> ${flows.keys.sorted()} from $appFlowsDir")
  }
  if (!appScreensDir.exists()) return
  val ktScreens = (appScreensDir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray())
  ktScreens.forEach { runCatching { ingestFile(it) }.onFailure { e -> println("boot ingest failed for $it: $e") } }
  val names = ktScreens.map { it.nameWithoutExtension }.toSet()
  File(root, "default").listFiles { f -> f.name.endsWith(".json") }?.forEach { mirror ->
    val screen = mirror.name.removeSuffix(".json")
    if (screen !in names) {
      mirror.delete()
      println("portal-server: retired stale store mirror default/$screen (no ${screen}.kt in $appScreensDir)")
    }
  }
  println("portal-server: boot scan ingested ${ktScreens.size} screen(s) from $appScreensDir")
}

private fun startKotlinWatcher() {
  val legacyRoot = File(root, "kotlin").apply { mkdirs() }
  val dirs = buildList {
    add(legacyRoot)
    if (appScreensDir.parentFile.exists()) {
      appScreensDir.mkdirs()
      add(appScreensDir)
    }
    // C1: watch the components dir (same debounce + self-write behavior).
    if (appComponentsDir.parentFile?.exists() == true) {
      appComponentsDir.mkdirs()
      add(appComponentsDir)
    }
    // #13 F1: watch the flows dir — edits re-derive the nav graph live.
    if (appFlowsDir.parentFile?.exists() == true) {
      appFlowsDir.mkdirs()
      add(appFlowsDir)
    }
    // K2: watch extra design-token dirs — a changed constant re-resolves the canvas.
    addAll(appTokensDirs)
    // P3-12: watch logic dirs — presenter edits trigger the preview rebuild.
    config.resolvedLogicDirs().forEach { rel ->
      val d = File(repoDir, rel)
      if (d.parentFile?.exists() == true) { d.mkdirs(); add(d) }
    }
  }.distinctBy { it.absolutePath }
  dirs.forEach { dir ->
    val watcher = io.methvin.watcher.DirectoryWatcher.builder()
      .path(dir.toPath())
      .listener { event ->
        val f = event.path().toFile()
        if (f.name.endsWith(".kt") && !f.name.startsWith("Compiled_")) {
          val key = f.absolutePath
          pendingIngest[key]?.cancel(false)
          val task = Runnable {
            // K2 (B2): screen/component/flow membership first; a tokens dir may contain them.
            for (route in routeKtEvent(f, ::isComponentFile, ::isFlowFile, listOf(appScreensDir), File(root, "kotlin"), appTokensDirs)) {
              when (route) {
                KtRoute.COMPONENT -> ingestComponentFile(f)
                KtRoute.FLOW -> ingestFlowFile(f)
                KtRoute.SCREEN -> ingestScreenFile(f)
                KtRoute.TOKENS -> ingestTokenFile(f)
                KtRoute.OTHER -> ingestFile(f)
              }
            }
          }
          pendingIngest[key] = ingestExec.schedule(task, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
          previewBuilder.trigger() // P3-12: any source change rebuilds the live editor
        }
      }
      .build()
    Thread({ watcher.watch() }, "kt-watcher-${dir.name}").apply { isDaemon = true }.start()
    println("portal-server: watching $dir (edits in ANY editor go live)")
  }
}

private fun ingestFile(f: File) {
  runCatching {
    if (!f.exists()) return
    // M6: repo screens dir → project "default"; legacy dir → project = parent dir name.
    val project = if (f.parentFile.absolutePath == appScreensDir.absolutePath) "default" else f.parentFile.name
    val screen = f.nameWithoutExtension
    val text = f.readText()
    val svc = docFor(mapOf("project" to project, "screen" to screen))
    when (svc.fileChanged(text)) {
      DocumentService.FileChange.SELF_WRITE -> {}
      DocumentService.FileChange.NOT_A_SCREEN -> println("ingest: ${f.name}: no @Composable screen function — skipped")
      DocumentService.FileChange.INGESTED -> println("ingest: $project/$screen -> v${svc.doc.version} (file edit)")
    }
  }.onFailure { println("ingest failed for $f: $it") }
}

/** The project a screen file belongs to (repo screens dir = "default"; legacy dir = its name). */
private fun screenProject(f: File): String =
  if (f.parentFile.absolutePath == appScreensDir.absolutePath) "default" else f.parentFile.name

/**
 * K2: a screen file changed. Screen files may declare design tokens too: when
 * the token table changed, every screen (and component) re-resolves;
 * otherwise only this file is ingested.
 */
private fun ingestScreenFile(f: File) {
  val project = screenProject(f)
  // The relay's own write-back (~400 ms after every edit) is not an edit: skip
  // it before rescanning anything (review S6). Unchanged files are cached anyway.
  val open = documents["$project/${f.nameWithoutExtension}"]
  if (open != null && f.exists() && open.wasSelfWrite(f.readText())) return
  val tokensChanged = runCatching { rebuildTokens(project) }.getOrDefault(false)
  if (tokensChanged) refreshProject(project, "${f.name} changed tokens") else ingestFile(f)
}

/** K2: a file in a design-token dir changed — rescan; re-resolve everything if the table changed. */
private fun ingestTokenFile(f: File) {
  runCatching {
    if (rebuildTokens("default")) refreshProject("default", "${f.name} changed tokens")
  }.onFailure { println("token ingest failed for $f: $it") }
}

/**
 * K2: a design-token declaration changed. Components rebuild against the new
 * table and every screen is re-recognized from its file — even one the relay
 * wrote itself, since its TEXT did not change — so a changed value updates the
 * canvas and a vanished or re-kinded token becomes RawCode.
 */
private fun refreshProject(project: String, why: String) {
  val dir = componentsDirFor(project)
  if (dir.exists()) Components.rebuild(project, dir, Tokens.table(project), ::tokenFileId)
  println("ingest(tokens): $project -> ${Tokens.table(project).offered.size} token(s) ($why)")
  reingestScreens(project, force = true)
  // Open editors refresh their token picker even when no document changed.
  documents.filterKeys { it.startsWith("$project/") }.values.forEach { it.announce() }
}

/** Re-recognize one screen against the current registries; retried while its write-back is pending. */
private fun forceReingest(project: String, screen: String) {
  runCatching {
    val svc = docFor(mapOf("project" to project, "screen" to screen))
    if (!svc.reingest()) {
      ingestExec.schedule({ forceReingest(project, screen) }, 600, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
  }.onFailure { println("re-ingest failed for $project/$screen: $it") }
}

/** C1: whether [f] is a component definition file (not a screen). */
private fun isComponentFile(f: File): Boolean {
  val p = f.parentFile ?: return false
  return p.absolutePath == appComponentsDir.absolutePath || p.name == "components"
}

/**
 * C1: a component file changed — rebuild the project's registry deterministically
 * and re-ingest every screen (and thus dependent-component previews refresh via
 * screen re-recognition). Removal is handled the same way: rebuild sees the file
 * gone and drops it.
 */
private fun ingestComponentFile(f: File) {
  runCatching {
    val project = if (isDefaultComponents(f)) "default" else (f.parentFile.parentFile?.name ?: "default")
    val dir = componentsDirFor(project)
    // K2: component files may declare design tokens; a changed table re-resolves every screen.
    val tokensChanged = rebuildTokens(project)
    val reg = Components.rebuild(project, dir, Tokens.table(project), ::tokenFileId)
    println("ingest(component): $project -> ${reg.names().sorted()} (${f.name} changed)")
    reingestScreens(project, force = tokensChanged)
    if (tokensChanged) documents.filterKeys { it.startsWith("$project/") }.values.forEach { it.announce() }
  }.onFailure { println("component ingest failed for $f: $it") }
}

// #13 F1: flow declarations — whole-dir rebuild on any flows/ change.
private fun isFlowFile(f: File): Boolean {
  val p = f.parentFile ?: return false
  return p.absolutePath == appFlowsDir.absolutePath || p.name == "flows"
}

private fun ingestFlowFile(f: File) {
  runCatching {
    val project = if (f.parentFile.absolutePath == appFlowsDir.absolutePath) "default"
    else (f.parentFile.parentFile?.name ?: "default")
    val flows = Flows.rebuild(project, flowsDirFor(project))
    println("ingest(flow): $project -> ${flows.keys.sorted()} (${f.name} changed)")
  }.onFailure { println("flow ingest failed for $f: $it") }
}

private fun isDefaultComponents(f: File): Boolean =
  f.parentFile?.absolutePath == appComponentsDir.absolutePath

/**
 * Re-ingest every open/known screen of [project] against the current registry.
 * [force] (K2) re-recognizes files the relay wrote itself too.
 */
private fun reingestScreens(project: String, force: Boolean = false) {
  val dir = screensDirFor(project)
  (dir.listFiles { x -> x.name.endsWith(".kt") && !x.name.startsWith("Compiled_") } ?: emptyArray())
    .forEach { f -> runCatching { if (force) forceReingest(project, f.nameWithoutExtension) else ingestFile(f) } }
}

fun main(args: Array<String>) {
  // P2-9: repoDir resolves BEFORE main (top-level state), so a positional arg
  // cannot be honored — fail loudly instead of silently serving the wrong repo.
  if (args.isNotEmpty()) {
    System.err.println(
      "portal-server: positional arguments are not supported. Point the server at an app repo\n" +
        "with PORTAL_REPO=<dir>, or run it from inside a repo containing keliver.portal.json.",
    )
    kotlin.system.exitProcess(64)
  }
  // U35: where the relay listens is settled before anything is scanned or
  // written, and a port something else already answers on is refused, not shared.
  val bindAddress = runCatching { RelayAccess.bindAddress(System.getenv(RelayAccess.BIND_ENV)) }.getOrElse {
    System.err.println("portal-server: ${it.message}")
    kotlin.system.exitProcess(64)
  }
  RelayAccess.portTakenBy(PORT)?.let { taken ->
    System.err.println(
      "portal-server: port $PORT is already in use (answering on $taken). Stop that process, " +
        "or set another \"port\" in keliver.portal.json.",
    )
    kotlin.system.exitProcess(64)
  }
  println("portal-server: repo=$repoDir (via $repoDirSource)")
  ensureDefaults()
  httpRecordingService.startupMessage()?.let(::println)
  bootScan()
  reconcileStore()
  startKotlinWatcher()
  // The relay has write endpoints (edits, write-back, publish), so it listens on
  // 127.0.0.1 only. An emulator (10.0.2.2), a simulator and `adb reverse` all
  // arrive there. KELIVER_RELAY_BIND=all opts in to every interface, for a
  // physical phone on the same Wi-Fi (docs/DEVICE_PROOF.md); everyone on that
  // network can then reach it. Every context passes RelayAccess.admit (U36).
  val server = RelayAccess.gate(HttpServer.create(InetSocketAddress(bindAddress, PORT), 0))
  if (bindAddress == RelayAccess.LOOPBACK) {
    println("portal-server: listening on 127.0.0.1:$PORT (loopback only)")
  } else {
    println(
      "portal-server: listening on every interface (*:$PORT, ${RelayAccess.BIND_ENV}=all) — NOT loopback: " +
        "anyone on this network can reach its edit and publish endpoints",
    )
  }

  // W6: POST /report from production hosts built to report here; counts at
  // GET /reports and /reports/view (HostReports.kt). Kept under the store.
  HostReports.register(server, HostReports(File(root, "reports"))) { ex, block -> handle(ex, block) }

  server.createContext("/projects") { ex ->
    handle(ex) {
      when (ex.requestMethod) {
        // Reserved: the relay's OWN storage lives beside the projects under
        // `root`. Listing raw directories exposed `bundles`, `keys` and
        // `kotlin` in the editor's project picker as if they were projects —
        // and `keys` holds the Ed25519 signing keypair, so selecting it would
        // have written screen JSON into the key directory.
        "GET" -> respond(ex, 200, jsonList(projectNames()))
        "POST" -> {
          val name = safe(ex.requestBody.readBytes().decodeToString().trim())
          val main = screenFile(name, "main")
          main.parentFile.mkdirs()
          if (!main.exists()) main.writeText("{}")
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  // M9: the overlay dev runtime polls this for the active screen's live doc
  // version — if it's ahead of the bundle's baked COMPILED_VERSION, overlay.
  // K3: `writeBack` is that screen's last write-back status (null = none yet):
  // a `refused` or `failed` one means the document is ahead of its .kt file.
  server.createContext("/devstate") { ex ->
    handle(ex) {
      val (p, s) = activeScreen()
      val svc = documents["$p/$s"]
      val version = svc?.doc?.version ?: 0L
      val writeBack = svc?.writeBack?.json() ?: "null"
      respond(ex, 200, "{\"project\":\"$p\",\"screen\":\"$s\",\"version\":$version,\"writeBack\":$writeBack}")
    }
  }

  // M8: the app project's declared host-capability requirements (drives preview fidelity).
  server.createContext("/capabilities") { ex ->
    handle(ex) {
      val project = safe(query(ex)["project"] ?: "default")
      val capsFile = File(screensDirFor(project), "capabilities.txt")
      val caps = if (capsFile.exists()) {
        capsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
      } else {
        emptyList()
      }
      respond(ex, 200, jsonList(caps))
    }
  }

  // K4: authoritative app-target metadata from keliver.portal.json. The
  // running editor compares this with its own build-embedded versions.
  server.createContext("/runtime-metadata") { ex ->
    handle(ex) { respond(ex, 200, config.runtimeMetadataJson()) }
  }

  // #16 H1: app-owned deterministic HTTP replay. This endpoint is read-only
  // with respect to both the repository and the network: a miss fails closed.
  server.createContext("/http-fixtures") { ex ->
    handle(ex) {
      when (ex.requestMethod) {
        "GET" -> respond(ex, 200, httpReplayService.catalogJson())
        else -> respond(ex, 405)
      }
    }
  }

  server.createContext("/http-replay") { ex ->
    handle(ex) {
      if (ex.requestMethod != "POST") {
        respond(ex, 405)
        return@handle
      }
      val params = query(ex)
      val fixtureSet = params["fixtureSet"].orEmpty()
      val session = params["session"].orEmpty()
      val body = runCatching { readBoundedBody(ex, 1024 * 1024) }.getOrElse {
        respond(ex, 413, """{"error":"request_too_large"}""")
        return@handle
      }
      val request = runCatching { httpReplayService.decodeRequest(body) }.getOrElse {
        respond(ex, 400, """{"error":"invalid_request"}""")
        return@handle
      }
      when (val result = httpReplayService.replay(fixtureSet, session, request)) {
        is HttpReplayResult.Match -> respond(
          ex,
          200,
          httpReplayService.responseJson(result.response),
        )
        is HttpReplayResult.Failure -> respond(
          ex,
          result.status,
          httpReplayService.errorJson(result.error),
        )
      }
    }
  }

  // #16 H2: explicit, loopback-only recording. Past RelayAccess.admit (which
  // every route passes), these also require a loopback peer, an origin from
  // httpRecording.allowedOrigins and the token, and return 404 for every failed guard.
  server.createContext("/http-record/sessions") { ex ->
    handleRecording(ex) {
      if (ex.requestMethod != "POST") {
        respond(ex, 405)
        return@handleRecording
      }
      val body = runCatching { readBoundedBody(ex, 64 * 1024) }.getOrElse {
        respond(ex, 413, """{"error":"recording_failed","reason":"request_too_large"}""")
        return@handleRecording
      }
      val command = runCatching { httpRecordingService.decodeSessionCommand(body) }.getOrElse {
        respond(ex, 400, """{"error":"recording_failed","reason":"invalid_request"}""")
        return@handleRecording
      }
      respondRecording(ex, httpRecordingService.createSession(command))
    }
  }

  server.createContext("/http-record/close") { ex ->
    handleRecording(ex) {
      if (ex.requestMethod != "POST") {
        respond(ex, 405)
        return@handleRecording
      }
      respondRecording(ex, httpRecordingService.close(query(ex)["session"].orEmpty()))
    }
  }

  server.createContext("/http-record") { ex ->
    handleRecording(ex) {
      if (ex.requestMethod != "POST") {
        respond(ex, 405)
        return@handleRecording
      }
      val body = runCatching { readBoundedBody(ex, 1024 * 1024) }.getOrElse {
        respond(ex, 413, """{"error":"recording_failed","reason":"request_too_large"}""")
        return@handleRecording
      }
      val request = runCatching { httpRecordingService.decodeRequest(body) }.getOrElse {
        respond(ex, 400, """{"error":"recording_failed","reason":"invalid_request"}""")
        return@handleRecording
      }
      respondRecording(
        ex,
        httpRecordingService.record(query(ex)["session"].orEmpty(), request),
      )
    }
  }

  // K5: the canvas's image pass-through for the app's declared image hosts
  // (PreviewAssets.kt holds every rule), and the host list the editor reads.
  val previewAssets = PreviewAssets(config.previewAssetHosts)
  if (previewAssets.enabled) println("portal-server: preview images via /preview-asset for ${previewAssets.hosts.joinToString()}")
  // GET only, checked before its pool as well as in serve(): a fetch is the one thing it does.
  previewAssets.install(server)
  server.createContext("/preview-assets") { ex ->
    handle(ex) { respond(ex, 200, previewAssets.describeJson()) }
  }

  // P3-12: live-preview build status (id/promotedId/state/error) for the editor.
  server.createContext("/preview-build") { ex ->
    handle(ex) { respond(ex, 200, previewBuilder.statusJson()) }
  }

  // C1: project components (specs + body trees) for the editor palette + preview.
  server.createContext("/components") { ex ->
    handle(ex) {
      val project = safe(query(ex)["project"] ?: "default")
      respond(ex, 200, Components.toJson(project))
    }
  }

  // K2: the project's design tokens (ref, kind, resolved value, declaring
  // file, package/import) for the editor's token picker and agents.
  server.createContext("/tokens") { ex ->
    handle(ex) {
      val project = safe(query(ex)["project"] ?: "default")
      respond(ex, 200, Tokens.toJson(project))
    }
  }

  // #13 F1: declared flows + the nav graph DERIVED from the project's current
  // screen trees (read from the store mirrors — each screen's live projection).
  server.createContext("/flow") { ex ->
    handle(ex) {
      val project = safe(query(ex)["project"] ?: "default")
      val screens = File(root, project).listFiles { f -> f.name.endsWith(".json") }
        ?.mapNotNull { f ->
          runCatching { f.name.removeSuffix(".json") to deserializeTree(f.readText()) }.getOrNull()
        }?.toMap() ?: emptyMap()
      respond(ex, 200, Flows.toJson(project, screens))
    }
  }

  server.createContext("/screens") { ex ->
    handle(ex) {
      val project = safe(query(ex)["project"] ?: "default")
      when (ex.requestMethod) {
        "GET" -> respond(
          ex, 200,
          jsonList(
            File(root, project).listFiles { f -> f.name.endsWith(".json") }
              ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList(),
          ),
        )
        "POST" -> {
          val name = safe(ex.requestBody.readBytes().decodeToString().trim())
          val f = screenFile(project, name)
          f.parentFile.mkdirs()
          if (!f.exists()) f.writeText("{}")
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  server.createContext("/draft") { ex ->
    handle(ex) {
      val q = query(ex)
      val project = q["project"] ?: "default"
      val screen = q["screen"] ?: "main"
      val f = screenFile(project, screen)
      when (ex.requestMethod) {
        "GET" -> respond(ex, 200, if (f.exists()) f.readText() else "{}")
        "PUT" -> {
          f.parentFile.mkdirs()
          f.writeText(ex.requestBody.readBytes().decodeToString())
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  server.createContext("/active") { ex ->
    handle(ex) {
      when (ex.requestMethod) {
        "GET" -> {
          val (p, s) = activeScreen()
          respond(ex, 200, "{\"project\":\"$p\",\"screen\":\"$s\"}")
        }
        "POST" -> {
          val q = query(ex)
          setActive(q["project"] ?: "default", q["screen"] ?: "main")
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  // ── V2 M1 document routes ──────────────────────────────────────────────
  server.createContext("/doc") { ex ->
    handle(ex) {
      // K2 (review S5): only a client that advertises design tokens (`tokens=1`)
      // gets PropValue.Token; an older editor gets their literal values.
      // K3: likewise PropValue.Conditional (`conditionals=1`); an older one gets the true branch.
      val q = query(ex)
      val doc = docFor(q).doc.let { if (q["conditionals"] == "1") it else it.withoutConditionals() }
      respond(ex, 200, DocJson.encodeToString(if (q["tokens"] == "1") doc else Tokens.withoutTokens(doc)))
    }
  }
  server.createContext("/ops") { ex ->
    handle(ex) {
      if (ex.requestMethod != "POST") { respond(ex, 405); return@handle }
      val q = query(ex)
      val batch = DocJson.decodeFromString<OpBatch>(ex.requestBody.readBytes().decodeToString())
      val svc = docFor(q)
      val ack = if (q["dryRun"] == "1") svc.dryRun(batch) else svc.submit(batch)
      val code = if (ack.ok) 200 else if (ack.error?.startsWith("stale") == true) 409 else 422
      respond(ex, code, DocJson.encodeToString(ack))
    }
  }
  // POST only, like /ops: they change the document, and a GET is what a page's <img> sends.
  server.createContext("/undo") { ex ->
    handle(ex) {
      if (ex.requestMethod != "POST") { respond(ex, 405); return@handle }
      respond(ex, 200, DocJson.encodeToString(docFor(query(ex)).undo(session(ex))))
    }
  }
  server.createContext("/redo") { ex ->
    handle(ex) {
      if (ex.requestMethod != "POST") { respond(ex, 405); return@handle }
      respond(ex, 200, DocJson.encodeToString(docFor(query(ex)).redo(session(ex))))
    }
  }
  server.createContext("/doc-events") { ex -> // SSE: {"version":N,"tokens":T[,"writeBack":{…}]} on every change and write-back
    ex.responseHeaders.add("Content-Type", "text/event-stream")
    ex.responseHeaders.add("Cache-Control", "no-cache")
    ex.sendResponseHeaders(200, 0)
    docFor(query(ex)).subscribe(ex.responseBody) // stream stays open
  }

  // LEGACY device endpoint — unchanged wire shape. Mirrors the ACTIVE screen's draft.
  server.createContext("/tree") { ex ->
    handle(ex) {
      val (p, s) = activeScreen()
      val f = screenFile(p, s)
      when (ex.requestMethod) {
        "GET" -> respond(ex, 200, if (f.exists()) f.readText() else "{}")
        "POST" -> {
          f.parentFile.mkdirs()
          f.writeText(ex.requestBody.readBytes().decodeToString())
          respond(ex, 204)
        }
        else -> respond(ex, 405)
      }
    }
  }

  server.createContext("/publickey") { ex ->
    handle(ex) { respond(ex, 200, File(keysDir, "ed25519.pub").readText().trim(), contentType = "text/plain") }
  }

  server.createContext("/publish") { ex ->
    handle(ex) {
      when (ex.requestMethod) {
        "POST" -> {
          val (ok, logText) = publish()
          respond(ex, if (ok) 200 else 422, logText, contentType = "text/plain")
        }
        else -> respond(ex, 405)
      }
    }
  }

  // /bundles/index.json -> every bundle (W3); /bundles/latest?widgetVersion=W -> newest compatible
  // bundle (hosts from tools 0.3.7 and earlier); /bundles/vN/<file> -> static.
  server.createContext("/bundles") { ex ->
    handle(ex) {
      val path = ex.requestURI.path.removePrefix("/bundles").trimStart('/')
      when {
        // W3: the static-layout index (StaticIndex.kt), generated from this store,
        // so hosts built from the current templates read one protocol everywhere.
        path == INDEX_FILE -> {
          ex.responseHeaders.add("Cache-Control", "no-cache")
          respond(ex, 200, relayIndexJson(bundlesDir))
        }
        path == "latest" -> {
          val q = query(ex)
          val want = q["widgetVersion"]?.toIntOrNull() ?: Int.MAX_VALUE
          // M7: capability gating — a bundle only qualifies when every capability
          // it REQUIRES is in the host's declared list (caps=a@1,b@2).
          val hostCaps = (q["caps"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
          val best = bundlesDir.listFiles { f -> f.isDirectory && f.name.startsWith("v") }
            ?.filter { dir ->
              val metaText = File(dir, "meta.json").takeIf { it.exists() }?.readText() ?: ""
              val recorded = Regex("\"widgetVersion\":(\\d+)").find(metaText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
              val required = Regex("\"capabilities\":\\[([^\\]]*)]").find(metaText)
                ?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
                ?: emptyList()
              recorded <= want && hostCaps.containsAll(required)
            }
            ?.maxByOrNull { it.name.removePrefix("v").toIntOrNull() ?: 0 }
          if (best == null) {
            respond(ex, 404, "{\"error\":\"no compatible bundle\"}")
          } else {
            respond(ex, 200, "{\"version\":${best.name.removePrefix("v")},\"manifestUrl\":\"/bundles/${best.name}/manifest.zipline.json\"}")
          }
        }
        else -> {
          val f = File(bundlesDir, path)
          // stay inside the store; serve bundle files raw
          if (!f.canonicalPath.startsWith(bundlesDir.canonicalPath) || !f.isFile) {
            respond(ex, 404)
          } else {
            val bytes = f.readBytes()
            ex.responseHeaders.add("Content-Type", if (f.name.endsWith(".json")) "application/json" else "application/octet-stream")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
          }
        }
      }
    }
  }

  server.executor = null
  server.start()
  println("portal-server: :$PORT — store=$root repo=$repoDir  active=${activeScreen()}")
  println("portal-server: endpoints /projects /screens /draft /active /tree /publickey /publish /bundles")
}
