import dev.keliver.portal.ComponentRegistry
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.ingest.Recognizer
import dev.keliver.portal.ingest.TokenTable
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `keliver check` (PLAN_2026-11 WS5): the checks that read files only. The
 * tools bundle's `bin/keliver-check` runs the app's compile task first and then
 * this (`relay/bin/keliver-check-jvm`), and adds the compile result to the same
 * JSON-lines stream.
 *
 *   keliver-check-jvm [app-dir] [--only editor,api,budget]
 *
 * Every line on stdout is one JSON object:
 *   {"check":"editor","severity":"error","file":"…/Home.kt","line":12,"message":"…","text":"…"}
 *   {"check":"api","severity":"warning","file":…,"line":…,"rule":"java-io","message":"…"}
 *   {"check":"budget","severity":"error"|"info",…,"bytes":N,"budgetBytes":M}
 *   {"check":"editor","severity":"pass","message":"3 screen(s), 2 component(s): 0 RawCode"}
 * Paths are relative to the app dir.
 *
 * Exit status: 0 no error (warnings allowed); 1 at least one error; 2 usage or
 * environment (no keliver.portal.json, unreadable config).
 */
object KeliverCheck {
  /** One finding or one pass line. */
  data class Finding(
    val check: String,
    val severity: String, // error | warning | info | pass | skip
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val extra: Map<String, Any?> = emptyMap(),
  ) {
    fun toJson(): JsonObject = buildJsonObject {
      put("check", check)
      put("severity", severity)
      file?.let { put("file", it) }
      line?.let { put("line", it) }
      put("message", message)
      extra.forEach { (k, v) ->
        when (v) {
          null -> {}
          is Number -> put(k, JsonPrimitive(v))
          is Boolean -> put(k, JsonPrimitive(v))
          else -> put(k, v.toString())
        }
      }
    }
  }

  // ---- (2) editor compatibility --------------------------------------------

  /**
   * Every screen and component file through the same recognizer, component
   * registry and token table the relay uses; each RawCode node is an error
   * with its file:line (D14: "0 RawCode ingest").
   */
  fun editor(appDir: File, config: PortalConfig): List<Finding> {
    val screensDir = File(appDir, config.screensDir)
    val componentsDir = File(appDir, config.resolvedComponentsDir())
    if (!screensDir.isDirectory) {
      return listOf(Finding("editor", "error", "screensDir ${config.screensDir} is not a directory"))
    }
    val tokens = CompiledAdapters.appTokens(appDir, config, project = "keliver-check")
    val fileId: (File) -> String = { Tokens.fileId(it, appDir) }
    val registry: ComponentRegistry =
      if (componentsDir.isDirectory) Components.rebuild("keliver-check", componentsDir, tokens, fileId)
      else dev.keliver.portal.EmptyComponentRegistry
    val out = mutableListOf<Finding>()
    var screens = 0
    var components = 0
    var raw = 0
    fun ktFiles(d: File) = (d.listFiles { f -> f.isFile && f.name.endsWith(".kt") } ?: emptyArray()).sortedBy { it.name }

    for (f in ktFiles(screensDir)) {
      val rel = rel(appDir, f)
      val text = f.readText()
      val r = runCatching { Recognizer.recognize(fileId(f), text, registry, tokens) }
      r.exceptionOrNull()?.let {
        out += Finding("editor", "error", "ingest failed: $it", rel); raw++
      }
      val rec = r.getOrNull() ?: continue
      screens++
      val found = rawCodes(rec.root, text)
      raw += found.size
      found.forEach { (node, line) -> out += rawFinding(rel, line, node) }
    }
    for (f in if (componentsDir.isDirectory) ktFiles(componentsDir) else emptyList()) {
      val rel = rel(appDir, f)
      val text = f.readText()
      // The spec (name, diagnostic) as the registry sees it; the body as the relay
      // opens a component document (DocumentService.forComponent: recognize asComponent).
      val r = runCatching {
        Recognizer.recognizeComponent(fileId(f), text, registry, tokens)?.spec to
          Recognizer.recognize(fileId(f), text, registry, tokens, asComponent = true)
      }
      r.exceptionOrNull()?.let { out += Finding("editor", "error", "ingest failed: $it", rel); raw++ }
      val (spec, doc) = r.getOrNull() ?: continue
      components++
      if (spec == null) {
        out += Finding("editor", "error", "no @Composable component in this file: the editor cannot open it", rel); raw++
        continue
      }
      doc?.let { d ->
        val found = rawCodes(d.root, text)
        raw += found.size
        found.forEach { (node, line) -> out += rawFinding(rel, line, node) }
      }
      // The relay refuses a document whose function is not the component's
      // (DocumentService.forComponent), so that, like no body, is a box.
      if (doc == null || doc.screenName != spec.name || spec.diagnostic != null) {
        val why = spec.diagnostic
          ?: if (doc == null) "the body is not recognized"
          else "the file's first @Composable is ${doc.screenName}, not ${spec.name}"
        out += Finding("editor", "error", "component ${spec.name} is opaque in the editor: $why", rel); raw++
      }
    }
    out += if (raw == 0) {
      Finding("editor", "pass", "$screens screen(s), $components component(s): 0 RawCode")
    } else {
      Finding("editor", "info", "$screens screen(s), $components component(s): $raw problem(s)")
    }
    return out
  }

  private fun rawFinding(rel: String, line: Int?, node: DocNode.RawCode) =
    Finding("editor", "error", "RawCode: the editor cannot edit this code", rel, line,
      mapOf("text" to node.text.lineSequence().first().take(160), "kindHint" to node.kindHint))

  /**
   * Every RawCode node in [root] with its 1-based line in [source], in tree
   * order. RawCode keeps its source verbatim, so each is looked for after the
   * previous one (null when the text is not in the file).
   *
   * Limits (docs/KELIVER_CHECK.md): a text search, not a source position. When
   * the search from the cursor misses it falls back to the first match in the
   * file, so a snippet that also appears earlier (a comment, a string, an
   * identical statement) can get the earlier line; it assumes tree order is
   * source order. Follow-up: carry the PSI offset on DocNode.RawCode.
   */
  internal fun rawCodes(root: DocNode, source: String): List<Pair<DocNode.RawCode, Int?>> {
    val text = source.replace("\r\n", "\n")
    val out = mutableListOf<Pair<DocNode.RawCode, Int?>>()
    var cursor = 0
    fun walk(n: DocNode) {
      when (n) {
        is DocNode.RawCode -> {
          val at = if (n.text.isBlank()) null
          else text.indexOf(n.text, cursor).takeIf { it >= 0 } ?: text.indexOf(n.text).takeIf { it >= 0 }
          if (at != null) cursor = at + n.text.length
          out += n to at?.let { a -> 1 + text.substring(0, a).count { it == '\n' } }
        }
        is DocNode.Widget -> n.children.forEach(::walk)
      }
    }
    walk(root)
    return out
  }

  // ---- (3) unsupported APIs --------------------------------------------------

  /** A pattern a guest cannot use, and why. */
  data class Rule(val id: String, val regex: Regex, val why: String)

  /**
   * The guest is Kotlin/JS in QuickJS (docs/SCREEN_ARCHITECTURE.md §2): no
   * JVM, no Android or iOS platform API, no files, sockets or secrets; the host
   * owns those and offers them as services. Hilt/Dagger need JVM reflection
   * and annotation processing (§5). Line-based, so a match is a warning to
   * look at, not proof.
   */
  val RULES: List<Rule> = listOf(
    Rule("android", Regex("""^\s*import\s+android\."""), "Android platform API: the guest runs in QuickJS, not on Android"),
    Rule("androidx", Regex("""^\s*import\s+androidx\.(?!compose\.runtime\.)"""),
      "AndroidX is Android-only; a guest uses androidx.compose.runtime and Keliver widgets only"),
    Rule("ios-platform", Regex("""^\s*import\s+platform\."""), "iOS/Kotlin-Native platform API: not available to a Kotlin/JS guest"),
    Rule("java", Regex("""^\s*import\s+(java\.|javax\.(?!inject\.))"""), // javax.inject is di-reflection's
      "JVM API (java.io, java.net, java.util.concurrent, reflection…): not on Kotlin/JS"),
    Rule("jvm-reflection", Regex("""::class\.java\b|\.javaClass\b|Class\.forName\(|import\s+kotlin\.reflect\.(full|jvm)\."""),
      "JVM reflection: not on Kotlin/JS"),
    Rule("kotlin-jvm", Regex("""^\s*import\s+kotlin\.(jvm|io\.path|concurrent)\.|@Synchronized\b|\bsynchronized\s*\(|@JvmStatic\b"""),
      "JVM-only Kotlin API"),
    Rule("jvm-system", Regex("""\bSystem\.(getenv|getProperty|currentTimeMillis|nanoTime|exit)\b|\bThread\.|\bRuntime\.getRuntime"""),
      "java.lang.System/Thread/Runtime: not on Kotlin/JS"),
    Rule("blocking", Regex("""\brunBlocking\b|\bDispatchers\.IO\b"""), "blocking coroutines API: not on Kotlin/JS"),
    Rule("di-reflection", Regex("""^\s*import\s+(dagger\.|javax\.inject\.)"""),
      "Hilt/Dagger need JVM reflection and annotation processing (SCREEN_ARCHITECTURE §5); wire the graph by hand"),
    Rule("http-client", Regex("""^\s*import\s+(okhttp3\.|retrofit2\.|io\.ktor\.client\.)"""),
      "networking is the host's (secrets stay native): call a host service, e.g. keliver-http"),
    Rule("db-driver", Regex("""^\s*import\s+(app\.cash\.sqldelight\.driver\.|android\.database\.)"""),
      "a database driver is the host's: take a capability interface (e.g. SqlDriver) instead"),
    Rule("file-io", Regex("""^\s*import\s+okio\.(FileSystem|Path)\b|\bFileSystem\.SYSTEM\b"""),
      "the guest has no file system: ask the host"),
  )

  /** The guest's own source: screens, components, logic and flows dirs, recursively. */
  fun guestSourceDirs(appDir: File, config: PortalConfig): List<File> =
    (listOf(config.screensDir, config.resolvedComponentsDir(), config.resolvedFlowsDir()) + config.resolvedLogicDirs())
      .distinct().map { File(appDir, it) }.filter { it.isDirectory }

  fun api(appDir: File, config: PortalConfig): List<Finding> {
    val files = guestSourceDirs(appDir, config)
      .flatMap { d -> d.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }
      .distinctBy { it.canonicalPath }.sortedBy { rel(appDir, it) }
    val out = mutableListOf<Finding>()
    for (f in files) {
      var inBlock = false
      f.readLines().forEachIndexed { i, raw ->
        val code = stripComments(raw, inBlock).also { inBlock = it.second }.first
        for (rule in RULES) if (rule.regex.containsMatchIn(code)) {
          out += Finding("api", "warning", rule.why, rel(appDir, f), i + 1, mapOf("rule" to rule.id, "text" to raw.trim().take(160)))
        }
      }
    }
    val warnings = out.size
    out += Finding("api", if (warnings == 0) "pass" else "info", "${files.size} guest file(s): $warnings unsupported-API warning(s)")
    return out
  }

  /** [line] without `//` and `/* */` comments; the second value is whether a block comment is still open. */
  internal fun stripComments(line: String, inBlockAtStart: Boolean): Pair<String, Boolean> {
    val sb = StringBuilder()
    var inBlock = inBlockAtStart
    var inString = false
    var i = 0
    while (i < line.length) {
      val c = line[i]
      val next = line.getOrNull(i + 1)
      when {
        inBlock -> if (c == '*' && next == '/') { inBlock = false; i++ }
        inString -> { sb.append(c); if (c == '\\') { next?.let { sb.append(it) }; i++ } else if (c == '"') inString = false }
        c == '"' -> { sb.append(c); inString = true }
        c == '/' && next == '/' -> return sb.toString() to false
        c == '/' && next == '*' -> { inBlock = true; i++ }
        else -> sb.append(c)
      }
      i++
    }
    return sb.toString() to inBlock
  }

  // ---- (4) bundle budget -----------------------------------------------------

  @Serializable
  data class CheckConfig(
    /** The task whose success is "the guest compiles". Null = the app's `publishTask`. */
    val compileTask: String? = null,
    /** The built bundle's size budget: every file under `publishOutput`, in bytes. Null = no budget. */
    val bundleBudgetBytes: Long? = null,
  )

  @Serializable
  private data class CheckSection(val check: CheckConfig = CheckConfig())

  fun checkConfig(appDir: File): CheckConfig {
    val f = File(appDir, "keliver.portal.json")
    if (!f.exists()) return CheckConfig()
    return Json { ignoreUnknownKeys = true }.decodeFromString(CheckSection.serializer(), f.readText()).check
  }

  /** What this run's compile did, from the wrapper: [result] "pass" | "fail", or null when it did not run. */
  data class CompileRun(val result: String?, val startedEpochSeconds: Long?)

  fun budget(appDir: File, config: PortalConfig, check: CheckConfig, compile: CompileRun = CompileRun(null, null)): List<Finding> {
    val budget = check.bundleBudgetBytes
      ?: return listOf(Finding("budget", "skip", "no check.bundleBudgetBytes in keliver.portal.json"))
    // A failed compile leaves the last good bundle behind: measuring it would pass a broken build.
    if (compile.result == "fail") {
      return listOf(Finding("budget", "skip", "the compile failed in this run: the bundle in ${config.publishOutput} is stale",
        extra = mapOf("budgetBytes" to budget)))
    }
    val dir = File(appDir, config.publishOutput)
    if (!dir.isDirectory) {
      return listOf(Finding("budget", "error", "no built bundle at ${config.publishOutput} (run the compile check first)",
        extra = mapOf("budgetBytes" to budget)))
    }
    val files = dir.walkTopDown().filter { it.isFile }.toList()
    val bytes = files.sumOf { it.length() }
    val largest = files.maxByOrNull { it.length() }?.let { "${rel(dir, it)} ${it.length()} B" }
    // Rebuilt by this run: some file is at least as new as the compile's start. False when
    // the task was up to date, or when check.compileTask does not write publishOutput.
    val rebuilt = compile.startedEpochSeconds?.let { s -> files.any { it.lastModified() >= s * 1000 } }
    val note = when (rebuilt) {
      null -> " (not compiled by this run)"
      false -> " (not rewritten by this run's compile: up to date, or check.compileTask does not write publishOutput)"
      true -> ""
    }
    val extra = mapOf("bytes" to bytes, "budgetBytes" to budget, "files" to files.size, "largest" to largest, "rebuilt" to rebuilt)
    return listOf(
      if (bytes > budget) Finding("budget", "error", "bundle is $bytes B, over the budget of $budget B$note", config.publishOutput, extra = extra)
      else Finding("budget", "pass", "bundle is $bytes B of a $budget B budget$note", config.publishOutput, extra = extra),
    )
  }

  // ---- (5) state screenshots against goldens ---------------------------------
  // TODO(WS5): render each @ScreenState to PNG (the snapshot runner, WS6) and
  // compare with checked-in goldens; report {"check":"goldens",…} per state.

  fun goldens(): List<Finding> =
    listOf(Finding("goldens", "skip", "state screenshots against goldens are not implemented yet (WS5 TODO)"))

  internal fun rel(base: File, f: File): String = Tokens.fileId(f, base)

  val ALL = listOf("editor", "api", "budget", "goldens")

  fun run(appDir: File, only: List<String>, compile: CompileRun = CompileRun(null, null)): List<Finding> {
    val config = loadPortalConfig(appDir)
    val check = checkConfig(appDir)
    return only.flatMap {
      when (it) {
        "editor" -> editor(appDir, config)
        "api" -> api(appDir, config)
        "budget" -> budget(appDir, config, check, compile)
        "goldens" -> goldens()
        else -> emptyList()
      }
    }
  }
}

fun main(args: Array<String>) {
  fun usage(msg: String): Nothing {
    System.err.println("keliver-check-jvm: $msg\nusage: keliver-check-jvm [app-dir] [--only ${KeliverCheck.ALL.joinToString(",")}] [--print-compile-task]")
    exitProcess(2)
  }
  var dir: String? = null
  var only = KeliverCheck.ALL
  var printTask = false
  var compileResult: String? = null
  var compileStarted: Long? = null
  var i = 0
  while (i < args.size) {
    when (val a = args[i]) {
      "--only" -> only = (args.getOrNull(++i) ?: usage("--only needs a value")).split(',').filter { it.isNotBlank() }
      "--compile-result" -> compileResult = (args.getOrNull(++i)?.takeIf { it == "pass" || it == "fail" }
        ?: usage("--compile-result needs pass or fail"))
      "--compile-started" -> compileStarted = (args.getOrNull(++i)?.toLongOrNull() ?: usage("--compile-started needs epoch seconds"))
      "--print-compile-task" -> printTask = true
      "-h", "--help" -> usage("help")
      else -> if (a.startsWith("--") || dir != null) usage("unknown argument $a") else dir = a
    }
    i++
  }
  only.firstOrNull { it !in KeliverCheck.ALL }?.let { usage("unknown check $it") }
  val appDir = File(dir ?: ".").absoluteFile.normalize()
  if (!File(appDir, "keliver.portal.json").isFile) {
    System.err.println("keliver-check-jvm: $appDir has no keliver.portal.json (run it in a Keliver app)")
    exitProcess(2)
  }
  val findings = try {
    if (printTask) {
      println(KeliverCheck.checkConfig(appDir).compileTask ?: loadPortalConfig(appDir).publishTask)
      exitProcess(0)
    }
    KeliverCheck.run(appDir, only, KeliverCheck.CompileRun(compileResult, compileStarted))
  } catch (e: kotlinx.serialization.SerializationException) {
    System.err.println("keliver-check-jvm: keliver.portal.json is not readable: ${e.message}")
    exitProcess(2)
  } catch (e: Throwable) {
    // Never let a crash read as "no findings": the wrapper turns exit 2 (and a
    // stream without the jvm-done line) into an environment failure.
    System.err.println("keliver-check-jvm: the checks crashed: $e")
    e.printStackTrace()
    exitProcess(2)
  }
  findings.forEach { println(it.toJson()) }
  // The end-of-stream marker: the wrapper treats a stream without it as a crash.
  println("""{"check":"jvm-done"}""")
  exitProcess(if (findings.any { it.severity == "error" }) 1 else 0)
}
