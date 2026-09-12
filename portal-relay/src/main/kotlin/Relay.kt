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
import dev.keliver.portal.exportKotlin
import dev.keliver.portal.serializeTree
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
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

private val PORT = config.port

private val root = resolveStore()

/**
 * The store, claimed for this repo. PORTAL_STORE is an explicit override for
 * one run (CI, a throwaway trial); it used to be silently ignored, which reads
 * as "isolated" and is not.
 */
private fun resolveStore(): File {
  val env = System.getenv("PORTAL_STORE")?.takeIf { it.isNotBlank() }
  val dir = try {
    if (env != null) File(env).absoluteFile else config.storeDir(repoDir, ::println)
  } catch (e: StoreOwnershipException) {
    System.err.println(e.message)
    kotlin.system.exitProcess(70)
  }
  dir.mkdirs()
  try {
    claimStoreFor(dir, repoDir)
  } catch (e: StoreOwnershipException) {
    // A refusal is an answer, not a crash. This used to surface as an
    // ExceptionInInitializerError stack trace with the actionable part buried
    // in the middle of it.
    System.err.println(e.message)
    kotlin.system.exitProcess(70)
  }
  // PORTAL_STORE is a ONE-RUN override, so it must not rebind the app. Other
  // consumers already see it: `PORTAL_STORE` is step 1 of the shell resolver
  // too, and it is an environment variable, so a build in the same environment
  // resolves it without any pointer.
  if (env == null) writeStorePointer(repoDir, dir)
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
private fun writeStorePointer(repo: File, store: File) {
  runCatching {
    val f = File(File(repo, ".gradle"), "keliver-store-path")
    f.parentFile.mkdirs()
    val line = store.absolutePath + "\n"
    if (!f.exists() || f.readText() != line) f.writeText(line)
  }.onFailure { println("portal-server: could not record the store pointer: $it") }
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

/** Relay-owned storage that sits beside the projects under [root]. Never a project. */
private val RESERVED_ROOT_DIRS = setOf("bundles", "keys", "kotlin")

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
 * P4: the project signing keypair. Zipline wants raw 32-byte Ed25519 keys as
 * hex; the JDK wraps them in PKCS#8/X.509 — the raw key is the last 32 bytes.
 */
private fun ensureKeys() {
  keysDir.mkdirs()
  val priv = File(keysDir, "ed25519.priv")
  val pub = File(keysDir, "ed25519.pub")
  if (priv.exists() && pub.exists()) return
  val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  fun raw32(encoded: ByteArray) = encoded.copyOfRange(encoded.size - 32, encoded.size)
  priv.writeText(hex(raw32(kp.private.encoded)))
  pub.writeText(hex(raw32(kp.public.encoded)))
  println("portal-server: generated Ed25519 signing keypair in $keysDir")
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
  log.appendLine("publish: compiling the canonical project (screens/${canonical.name} + hand-owned logic/)")

  val gradlew = File(repoDir, "gradlew").absolutePath
  val proc = ProcessBuilder(gradlew, config.publishTask, "--console=plain")
    .directory(repoDir)
    .redirectErrorStream(true)
    .start()
  val out = proc.inputStream.bufferedReader().readText()
  val code = proc.waitFor()
  log.appendLine(out.lines().filter { it.isNotBlank() }.takeLast(30).joinToString("\n"))
  if (code != 0) return false to log.appendLine("publish FAILED (gradle exit $code)").toString()

  val ziplineOut = File(repoDir, config.publishOutput)
  if (!ziplineOut.exists()) return false to log.appendLine("publish FAILED: no zipline output at $ziplineOut").toString()
  val version = nextBundleVersion()
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

private fun cors(ex: HttpExchange) {
  ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
  ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
  ex.responseHeaders.add("Access-Control-Allow-Headers", "*")
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

private fun handle(ex: HttpExchange, block: () -> Unit) {
  cors(ex)
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
    origin?.let { ex.responseHeaders.add("Access-Control-Allow-Origin", it) }
    ex.responseHeaders.add("Vary", "Origin")
    ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
    ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, X-Portal-Record-Token")
    respond(ex, 204)
    return
  }
  val suppliedToken = ex.requestHeaders.getFirst("X-Portal-Record-Token")
  if (!httpRecordingService.authorize(ex.remoteAddress.address, origin, suppliedToken)) {
    respond(ex, 404)
    return
  }
  origin?.let { ex.responseHeaders.add("Access-Control-Allow-Origin", it) }
  ex.responseHeaders.add("Vary", "Origin")
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
  // C1: components FIRST so screens recognize their calls on the first pass.
  if (appComponentsDir.exists()) {
    val reg = Components.rebuild("default", appComponentsDir)
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
            when {
              isComponentFile(f) -> ingestComponentFile(f)
              isFlowFile(f) -> ingestFlowFile(f)
              else -> ingestFile(f)
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
    if (svc.wasSelfWrite(text)) return
    // C1: recognize with the project's component registry so component calls
    // become editable Widget nodes instead of RawCode.
    val recognized = dev.keliver.portal.ingest.Recognizer.recognize(f.name, text, Components.registry(project))
      ?: return println("ingest: ${f.name}: no @Composable screen function — skipped")
    val newDoc = dev.keliver.portal.ingest.Reconciler.reconcile(svc.doc, recognized)
    svc.acceptExternal(newDoc)
    println("ingest: $project/$screen -> v${newDoc.version} (file edit)")
  }.onFailure { println("ingest failed for $f: $it") }
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
    val reg = Components.rebuild(project, dir)
    println("ingest(component): $project -> ${reg.names().sorted()} (${f.name} changed)")
    reingestScreens(project)
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

/** Re-ingest every open/known screen of [project] against the current registry. */
private fun reingestScreens(project: String) {
  val dir = screensDirFor(project)
  (dir.listFiles { x -> x.name.endsWith(".kt") && !x.name.startsWith("Compiled_") } ?: emptyArray())
    .forEach { runCatching { ingestFile(it) } }
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
  println("portal-server: repo=$repoDir (via $repoDirSource)")
  ensureDefaults()
  httpRecordingService.startupMessage()?.let(::println)
  bootScan()
  reconcileStore()
  startKotlinWatcher()
  val server = HttpServer.create(InetSocketAddress(PORT), 0)

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
  server.createContext("/devstate") { ex ->
    handle(ex) {
      val (p, s) = activeScreen()
      val version = documents["$p/$s"]?.doc?.version ?: 0L
      respond(ex, 200, "{\"project\":\"$p\",\"screen\":\"$s\",\"version\":$version}")
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

  // #16 H2: explicit, loopback-only recording. Unlike normal editor routes,
  // these never receive wildcard CORS and return 404 for every failed guard.
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
    handle(ex) { respond(ex, 200, DocJson.encodeToString(docFor(query(ex)).doc)) }
  }
  server.createContext("/ops") { ex ->
    handle(ex) {
      val q = query(ex)
      val batch = DocJson.decodeFromString<OpBatch>(ex.requestBody.readBytes().decodeToString())
      val svc = docFor(q)
      val ack = if (q["dryRun"] == "1") svc.dryRun(batch) else svc.submit(batch)
      val code = if (ack.ok) 200 else if (ack.error?.startsWith("stale") == true) 409 else 422
      respond(ex, code, DocJson.encodeToString(ack))
    }
  }
  server.createContext("/undo") { ex ->
    handle(ex) { respond(ex, 200, DocJson.encodeToString(docFor(query(ex)).undo(session(ex)))) }
  }
  server.createContext("/redo") { ex ->
    handle(ex) { respond(ex, 200, DocJson.encodeToString(docFor(query(ex)).redo(session(ex)))) }
  }
  server.createContext("/doc-events") { ex -> // SSE: {"version":N} on every change
    cors(ex)
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

  // /bundles/latest?widgetVersion=W -> newest compatible bundle; /bundles/vN/<file> -> static.
  server.createContext("/bundles") { ex ->
    handle(ex) {
      val path = ex.requestURI.path.removePrefix("/bundles").trimStart('/')
      when {
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
