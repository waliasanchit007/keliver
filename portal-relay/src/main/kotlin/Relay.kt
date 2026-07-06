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

private const val PORT = 8077

private val root = File(System.getProperty("user.home"), ".keliver-portal")
private val activeFile = File(root, "active")
private val keysDir = File(root, "keys")
private val bundlesDir = File(root, "bundles")

/** The konduit checkout the publish step compiles in. */
private val repoDir = File(System.getenv("PORTAL_REPO") ?: System.getProperty("user.dir"))

/** project/screen names are path segments — restrict to a safe charset. */
private fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unnamed" }

private fun screenFile(project: String, screen: String) = File(File(root, safe(project)), "${safe(screen)}.json")

private fun activeScreen(): Pair<String, String> {
  val lines = runCatching { activeFile.readLines() }.getOrDefault(emptyList())
  return (lines.getOrNull(0) ?: "default") to (lines.getOrNull(1) ?: "main")
}

private fun setActive(project: String, screen: String) {
  activeFile.writeText("${safe(project)}\n${safe(screen)}\n")
}

private fun ensureDefaults() {
  root.mkdirs()
  val main = screenFile("default", "main")
  if (!main.exists()) { main.parentFile.mkdirs(); main.writeText("{}") }
  if (!activeFile.exists()) setActive("default", "main")
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
  log.appendLine("publish: compiling the canonical project (screens/${canonical.name} + hand-owned logic/)")

  val gradlew = File(repoDir, "gradlew").absolutePath
  val proc = ProcessBuilder(gradlew, ":portal-published-guest:compileDevelopmentZipline", "--console=plain")
    .directory(repoDir)
    .redirectErrorStream(true)
    .start()
  val out = proc.inputStream.bufferedReader().readText()
  val code = proc.waitFor()
  log.appendLine(out.lines().filter { it.isNotBlank() }.takeLast(30).joinToString("\n"))
  if (code != 0) return false to log.appendLine("publish FAILED (gradle exit $code)").toString()

  val ziplineOut = File(repoDir, "portal-published-guest/build/zipline/Development")
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

private fun jsonList(items: List<String>): String =
  items.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }

private fun handle(ex: HttpExchange, block: () -> Unit) {
  cors(ex)
  if (ex.requestMethod == "OPTIONS") { respond(ex, 204); return }
  runCatching(block).onFailure {
    println("portal-server: ${ex.requestURI} failed: $it")
    runCatching { respond(ex, 500, "{\"error\":\"${it.message}\"}") }
  }
}

// ── V2 M1: the live document engine ─────────────────────────────────────────

private val documents = java.util.concurrent.ConcurrentHashMap<String, DocumentService>()

/**
 * M6 app project model: the "default" project's canonical screens live INSIDE
 * the guest Gradle module (git-versioned); other projects use the legacy dir.
 */
private val appScreensDir = File(repoDir, "portal-app-lib/src/jsMain/kotlin/screens")
private fun screensDirFor(project: String): File =
  if (project == "default" && appScreensDir.parentFile.exists()) appScreensDir
  else File(File(root, "kotlin"), project)

private fun screenFunctionName(screen: String): String =
  screen.replaceFirstChar { it.uppercase() } + "Screen"

/** One engine per screen; projection feeds the EXISTING draft file (devices + /tree unchanged). */
private fun docFor(q: Map<String, String>): DocumentService {
  val project = safe(q["project"] ?: "default")
  val screen = safe(q["screen"] ?: "main")
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
      packageName = if (inProject) "dev.keliver.portalpublished.screens" else null,
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

private fun startKotlinWatcher() {
  val legacyRoot = File(root, "kotlin").apply { mkdirs() }
  val dirs = buildList {
    add(legacyRoot)
    if (appScreensDir.parentFile.exists()) {
      appScreensDir.mkdirs()
      add(appScreensDir)
    }
  }
  dirs.forEach { dir ->
    val watcher = io.methvin.watcher.DirectoryWatcher.builder()
      .path(dir.toPath())
      .listener { event ->
        val f = event.path().toFile()
        if (f.name.endsWith(".kt")) {
          val key = f.absolutePath
          pendingIngest[key]?.cancel(false)
          pendingIngest[key] = ingestExec.schedule({ ingestFile(f) }, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
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
    val recognized = dev.keliver.portal.ingest.Recognizer.recognize(f.name, text)
      ?: return println("ingest: ${f.name}: no @Composable screen function — skipped")
    val newDoc = dev.keliver.portal.ingest.Reconciler.reconcile(svc.doc, recognized)
    svc.acceptExternal(newDoc)
    println("ingest: $project/$screen -> v${newDoc.version} (file edit)")
  }.onFailure { println("ingest failed for $f: $it") }
}

fun main() {
  ensureDefaults()
  startKotlinWatcher()
  val server = HttpServer.create(InetSocketAddress(PORT), 0)

  server.createContext("/projects") { ex ->
    handle(ex) {
      when (ex.requestMethod) {
        "GET" -> respond(ex, 200, jsonList(root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()))
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
