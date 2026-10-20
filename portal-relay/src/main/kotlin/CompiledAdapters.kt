import dev.keliver.portal.ingest.CompiledAdapterGenerator
import dev.keliver.portal.ingest.TokenTable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Compiled component preview (docs/COMPILED_COMPONENT_PREVIEW.md): writes the
 * per-app editor's generated adapters ([PortalConfig.compiledComponents]) from
 * the app's component files, before each preview build. The file is rewritten
 * only when its text changes, so an unchanged signature costs no recompile.
 *
 * The same code backs the relay and the command line ([main]: write, or
 * `--check` that the checked-in file is current), with the same design-token
 * table, so the two cannot disagree.
 */
object CompiledAdapters {
  const val REGENERATE_HINT: String =
    "Written by the Keliver relay before each preview build (keliver.portal.json compiledComponents)."

  /** The generated adapters for the app at [repoDir]; null when [config] has no compiledComponents block. */
  fun generate(repoDir: File, config: PortalConfig, componentsDir: File, tokens: TokenTable): CompiledAdapterGenerator.Output? {
    val cfg = config.compiledComponents ?: return null
    val files = (componentsDir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray())
      .sortedBy { it.name }
      // A file's token identity is its repo-relative path (Tokens.fileId):
      // under any other name its file-private constants are invisible.
      .map { CompiledAdapterGenerator.SourceFile(Tokens.fileId(it, repoDir), it.readText()) }
    return CompiledAdapterGenerator.generate(files, cfg.packageName, cfg.valName, tokens, cfg.sameModule, REGENERATE_HINT)
  }

  /** Null on success (or when off); otherwise why the adapters could not be written. */
  fun emit(repoDir: File, config: PortalConfig, componentsDir: File, tokens: TokenTable): String? {
    val cfg = config.compiledComponents ?: return null
    return runCatching {
      val out = outFile(repoDir, cfg.out)
      val gen = generate(repoDir, config, componentsDir, tokens)!!
      val existing = if (out.isFile) out.readText() else null
      if (existing != null && !existing.startsWith(CompiledAdapterGenerator.GENERATED_HEADER)) {
        error("${cfg.out} exists and was not written by the generator (its first line is not the GENERATED header); refusing to overwrite it")
      }
      // Keep the file's own line endings (a CRLF checkout stays CRLF).
      val text = if (existing != null && "\r\n" in existing) gen.source.replace("\n", "\r\n") else gen.source
      if (existing != text) {
        writeAtomically(out, text)
        println("portal-server: compiled component adapters -> ${cfg.out} (${gen.adapted.size} adapted, ${gen.refused.size} refused)")
      }
      null
    }.getOrElse { "compiled component adapters: ${it.message ?: it::class.simpleName}" }
  }

  /** Null when the checked-in file matches what [emit] would write; otherwise why not. */
  fun check(repoDir: File, config: PortalConfig, componentsDir: File, tokens: TokenTable): String? {
    val cfg = config.compiledComponents ?: return "keliver.portal.json has no compiledComponents block"
    val out = File(repoDir, cfg.out)
    val gen = generate(repoDir, config, componentsDir, tokens)!!
    val existing = if (out.isFile) out.readText().replace("\r\n", "\n") else return "${cfg.out} does not exist"
    return if (existing == gen.source) null else "${cfg.out} is stale: regenerate it (start the relay, or run this without --check)"
  }

  /** A warning for a config that cannot take effect; null when none. */
  fun configWarning(config: PortalConfig): String? =
    if (config.compiledComponents != null && config.previewBuildTask.isBlank()) {
      "portal-server: keliver.portal.json sets compiledComponents but no previewBuildTask — " +
        "the adapters are written only before a per-app editor build, so nothing will use them"
    } else {
      null
    }

  /** The relay's design-token table for the app, built exactly as Relay.kt's rebuildTokens does for "default". */
  fun appTokens(repoDir: File, config: PortalConfig, project: String = "compiled-adapters-cli"): TokenTable {
    val tokenDirs = config.resolvedTokensDirs()
      .map { File(it).let { f -> if (f.isAbsolute) f else File(repoDir, it) } }
      .filter { it.isDirectory }
    Tokens.rebuild(
      project,
      flatDirs = listOf(File(repoDir, config.screensDir), File(repoDir, config.resolvedComponentsDir())),
      deepDirs = tokenDirs,
      root = repoDir,
    )
    return Tokens.table(project)
  }

  /**
   * [rel] inside [repoDir]: it must stay inside the repo once resolved, and
   * neither it nor a directory on its way may be a symbolic link (the relay
   * would otherwise write wherever the link points).
   */
  private fun outFile(repoDir: File, rel: String): File {
    val repo = repoDir.canonicalFile
    val out = File(repo, rel)
    require(out.canonicalFile.toPath().startsWith(repo.toPath())) { "compiledComponents.out must stay inside the app repository: $rel" }
    var p: File? = out
    while (p != null && p.absolutePath != repo.absolutePath) {
      require(!Files.isSymbolicLink(p.toPath())) { "compiledComponents.out must not go through a symbolic link: $p" }
      p = p.parentFile
    }
    return out
  }

  /** Temp file in the same directory, then an atomic rename: a build never sees half a file. */
  private fun writeAtomically(out: File, text: String) {
    out.parentFile.mkdirs()
    val tmp = File(out.parentFile, ".${out.name}.tmp")
    tmp.writeText(text)
    try {
      Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(tmp.toPath())
    }
    check(!Files.isSymbolicLink(out.toPath()) && Files.isRegularFile(out.toPath(), LinkOption.NOFOLLOW_LINKS))
  }
}

/**
 * `CompiledAdaptersKt <app-dir> [--check]`: generate the app's compiled
 * component adapters without a relay (CI, a pre-commit hook), or check the
 * checked-in file is current (exit 1 when stale). Same generator, same token
 * table as the relay.
 */
fun main(args: Array<String>) {
  val check = "--check" in args
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: ".").absoluteFile
  val config = loadPortalConfig(dir)
  val components = File(dir, config.resolvedComponentsDir())
  val tokens = CompiledAdapters.appTokens(dir, config)
  val problem = if (check) CompiledAdapters.check(dir, config, components, tokens) else CompiledAdapters.emit(dir, config, components, tokens)
  if (problem != null) {
    System.err.println(problem)
    kotlin.system.exitProcess(1)
  }
  println(if (check) "compiled component adapters are up to date" else "compiled component adapters written")
}
