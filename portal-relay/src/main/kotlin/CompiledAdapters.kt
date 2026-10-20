import dev.keliver.portal.ingest.CompiledAdapterGenerator
import dev.keliver.portal.ingest.TokenTable
import java.io.File

/**
 * Compiled component preview (docs/COMPILED_COMPONENT_PREVIEW.md): writes the
 * per-app editor's generated adapters ([PortalConfig.compiledComponents]) from
 * the app's component files, before each preview build. The file is rewritten
 * only when its text changes, so an unchanged signature costs no recompile.
 */
object CompiledAdapters {
  const val REGENERATE_HINT: String =
    "Written by the Keliver relay before each preview build (keliver.portal.json compiledComponents)."

  /** Null on success (or when off); otherwise why the adapters could not be written. */
  fun emit(repoDir: File, config: PortalConfig, componentsDir: File, tokens: TokenTable): String? {
    val cfg = config.compiledComponents ?: return null
    return runCatching {
      val repo = repoDir.canonicalFile
      val out = File(repo, cfg.out).canonicalFile
      require(out.toPath().startsWith(repo.toPath())) { "compiledComponents.out must stay inside the app repository: ${cfg.out}" }
      val files = (componentsDir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray())
        .sortedBy { it.name }
        .map { CompiledAdapterGenerator.SourceFile(it.name, it.readText()) }
      val gen = CompiledAdapterGenerator.generate(files, cfg.packageName, cfg.valName, tokens, cfg.sameModule, REGENERATE_HINT)
      if (!out.isFile || out.readText() != gen.source) {
        out.parentFile.mkdirs()
        out.writeText(gen.source)
        println("portal-server: compiled component adapters -> ${cfg.out} (${gen.adapted.size} adapted, ${gen.refused.size} refused)")
      }
      null
    }.getOrElse { "compiled component adapters: ${it.message ?: it::class.simpleName}" }
  }
}
