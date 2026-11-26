/*
 * Copyright (C) 2026 Keliver contributors.
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
import dev.keliver.portal.ingest.ScreenStateRegistryGenerator
import java.io.File

/**
 * MS4-28: writes (or checks) the app's generated `@ScreenState` registry
 * ([PortalConfig.screenStateRegistry]) from the screens directory `GET /states` reads, with the
 * same recognizer, so the registry and `/states` list the same states. The relay writes it before
 * each preview build, so the editor's build always compiles a current one; [main] writes or
 * checks it without a relay (CI, a pre-commit hook). The file is rewritten only when its text
 * changes.
 */
object ScreenStateRegistryFile {
  const val REGENERATE_HINT: String =
    "Written by the Keliver relay before each preview build (keliver.portal.json screenStateRegistry); " +
      "check it with `ScreenStateRegistryKt <app-dir> --check`."

  private const val WHAT = "screenStateRegistry.out"

  /** The screen files `GET /states` reads for the app at [repoDir]: each `.kt` of its screens directory. */
  fun screenFiles(repoDir: File, config: PortalConfig): List<ScreenStateRegistryGenerator.SourceFile> =
    (File(repoDir, config.screensDir).listFiles { f -> f.isFile && f.name.endsWith(".kt") } ?: emptyArray())
      .sortedBy { it.name }
      .map { ScreenStateRegistryGenerator.SourceFile(it.name, it.readText()) }

  /** The registry for the app at [repoDir]; null when [config] has no screenStateRegistry block. */
  fun generate(repoDir: File, config: PortalConfig): ScreenStateRegistryGenerator.Output? {
    val cfg = config.screenStateRegistry ?: return null
    return ScreenStateRegistryGenerator.generate(screenFiles(repoDir, config), cfg.packageName, cfg.valName, REGENERATE_HINT)
  }

  /** Null on success (or when off); otherwise why the registry could not be written. */
  fun emit(repoDir: File, config: PortalConfig): String? {
    val cfg = config.screenStateRegistry ?: return null
    return runCatching {
      val out = outFile(repoDir, config, cfg)
      val gen = generate(repoDir, config)!!
      val existing = if (out.isFile) out.readText() else null
      if (existing != null && !existing.startsWith(ScreenStateRegistryGenerator.GENERATED_HEADER)) {
        error("${cfg.out} exists and was not written by the generator (its first line is not the GENERATED header); refusing to overwrite it")
      }
      // Keep the file's own line endings (a CRLF checkout stays CRLF).
      val text = if (existing != null && "\r\n" in existing) gen.source.replace("\n", "\r\n") else gen.source
      if (existing != text) {
        CompiledAdapters.writeAtomically(out, text, WHAT)
        println("portal-server: screen-state registry -> ${cfg.out} (${gen.states} states, ${gen.problems.size} problems)")
      }
      null
    }.getOrElse { "screen-state registry: ${it.message ?: it::class.simpleName}" }
  }

  /** Null when the checked-in file matches what [emit] would write; otherwise why not. */
  fun check(repoDir: File, config: PortalConfig): String? {
    val cfg = config.screenStateRegistry ?: return "keliver.portal.json has no screenStateRegistry block"
    return runCatching {
      val out = outFile(repoDir, config, cfg)
      val gen = generate(repoDir, config)!!
      val existing = if (out.isFile) out.readText().replace("\r\n", "\n") else return "${cfg.out} does not exist"
      if (existing == gen.source) null else "${cfg.out} is stale: regenerate it (start the relay, or run this without --check)"
    }.getOrElse { "screen-state registry: ${it.message ?: it::class.simpleName}" }
  }

  /** [ScreenStateRegistryConfig.out], refused inside the screens directory (it would be listed as a screen). */
  private fun outFile(repoDir: File, config: PortalConfig, cfg: ScreenStateRegistryConfig): File {
    val out = CompiledAdapters.outFile(repoDir, cfg.out, WHAT)
    require(out.canonicalFile.parentFile != File(repoDir, config.screensDir).canonicalFile) {
      "$WHAT must not be in the screens directory (${config.screensDir}), where it would be listed as a screen: ${cfg.out}"
    }
    return out
  }
}

/**
 * `ScreenStateRegistryKt <app-dir> [--check]`: write the app's `@ScreenState` registry
 * (keliver.portal.json `screenStateRegistry`) without a relay, or check that the checked-in file
 * is current (exit 1 when stale). Problems (`@ScreenState` functions the registry leaves out, as
 * `GET /states` reports them) are printed; they are in the file too.
 */
fun main(args: Array<String>) {
  val check = "--check" in args
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: ".").absoluteFile
  val config = loadPortalConfig(dir)
  val problem = if (check) ScreenStateRegistryFile.check(dir, config) else ScreenStateRegistryFile.emit(dir, config)
  if (problem != null) {
    System.err.println(problem)
    kotlin.system.exitProcess(1)
  }
  val gen = ScreenStateRegistryFile.generate(dir, config)
  gen?.problems?.forEach { System.err.println("warning: $it") }
  println(
    when {
      gen == null -> "keliver.portal.json has no screenStateRegistry block: nothing written"
      check -> "screen-state registry is up to date (${gen.states} states)"
      else -> "screen-state registry written (${gen.states} states)"
    },
  )
}
