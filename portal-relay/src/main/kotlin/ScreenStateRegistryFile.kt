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
@file:JvmName("ScreenStateRegistryKt")

import dev.keliver.portal.ingest.ScreenStateRegistryGenerator
import java.io.File

/**
 * MS4-28: writes (or checks) the app's generated `@ScreenState` registry
 * ([PortalConfig.screenStateRegistry]) from the screens directory `GET /states` reads, through
 * the same index ([ScreenStatesIndex]), so the registry and `/states` list the same states and the
 * same problems (an unreadable screen file included). The relay writes it before each preview
 * build, so the editor's build always compiles a current one; [main] writes or checks it without
 * a relay (CI, a pre-commit hook). The file is rewritten only when its text changes.
 */
object ScreenStateRegistryFile {
  /**
   * The command that writes or checks the file without a relay: [main]'s class, named by this
   * file's `@file:JvmName`. Every generated registry's header, the docs and portal-relay's
   * `screenStateRegistry` Gradle task name it.
   */
  const val COMMAND: String = "ScreenStateRegistryKt"

  const val REGENERATE_HINT: String =
    "Written by the Keliver relay before each preview build (keliver.portal.json screenStateRegistry); " +
      "check it with `$COMMAND <app-dir> --check`."

  private const val WHAT = "screenStateRegistry.out"

  /**
   * The registry for the app at [repoDir]; null when [config] has no screenStateRegistry block.
   * [index] is how `GET /states` reads the screens directory (a fresh one by default).
   */
  fun generate(repoDir: File, config: PortalConfig, index: ScreenStatesIndex = ScreenStatesIndex()): ScreenStateRegistryGenerator.Output? {
    val cfg = config.screenStateRegistry ?: return null
    val screens = index.forDir(File(repoDir, config.screensDir))
    return ScreenStateRegistryGenerator.generate(screens, cfg.packageName, cfg.valName, REGENERATE_HINT)
  }

  /**
   * The relay's startup warning when the block can't take effect: without a `previewBuildTask` the
   * relay never builds, so it never writes the registry (as [CompiledAdapters.configWarning]).
   */
  fun configWarning(config: PortalConfig): String? =
    if (config.screenStateRegistry != null && config.previewBuildTask.isBlank()) {
      "portal-server: keliver.portal.json sets screenStateRegistry but no previewBuildTask — " +
        "this relay will not write ${config.screenStateRegistry.out}; " +
        "run `$COMMAND <app-dir>` after changing a @ScreenState, and `$COMMAND <app-dir> --check` in CI"
    } else {
      null
    }

  /** Null on success (or when off); otherwise why the registry could not be written. */
  fun emit(repoDir: File, config: PortalConfig, index: ScreenStatesIndex = ScreenStatesIndex()): String? {
    val cfg = config.screenStateRegistry ?: return null
    return runCatching {
      val out = outFile(repoDir, config, cfg)
      val gen = generate(repoDir, config, index)!!
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
  fun check(repoDir: File, config: PortalConfig, index: ScreenStatesIndex = ScreenStatesIndex()): String? {
    val cfg = config.screenStateRegistry ?: return "keliver.portal.json has no screenStateRegistry block"
    return runCatching {
      val out = outFile(repoDir, config, cfg)
      val gen = generate(repoDir, config, index)!!
      val existing = if (out.isFile) out.readText().replace("\r\n", "\n") else return "${cfg.out} does not exist"
      if (existing == gen.source) null else "${cfg.out} is stale: regenerate it (start the relay, or run this without --check)"
    }.getOrElse { "screen-state registry: ${it.message ?: it::class.simpleName}" }
  }

  /**
   * The directories the relay watches for the app (Relay.kt's startKotlinWatcher, recursively),
   * each with what it is: a registry written into one would start another preview build on every
   * write, and the screens, components and tokens scans would read it as one of theirs.
   */
  internal fun watchedDirs(repoDir: File, config: PortalConfig): List<Pair<String, File>> = buildList {
    add("screens" to File(repoDir, config.screensDir))
    add("components" to File(repoDir, config.resolvedComponentsDir()))
    add("flows" to File(repoDir, config.resolvedFlowsDir()))
    config.resolvedTokensDirs().forEach { add("tokens" to File(it).let { f -> if (f.isAbsolute) f else File(repoDir, it) }) }
    config.resolvedLogicDirs().forEach { add("logic" to File(repoDir, it)) }
  }

  /** [ScreenStateRegistryConfig.out], refused in (or under) any directory the relay watches. */
  private fun outFile(repoDir: File, config: PortalConfig, cfg: ScreenStateRegistryConfig): File {
    val out = CompiledAdapters.outFile(repoDir, cfg.out, WHAT)
    val path = out.canonicalFile.toPath()
    for ((kind, dir) in watchedDirs(repoDir, config)) {
      require(!path.startsWith(dir.canonicalFile.toPath())) {
        val rel = dir.canonicalFile.relativeToOrNull(repoDir.canonicalFile)?.invariantSeparatorsPath ?: dir.path
        "$WHAT must not be in the $kind directory ($rel): the relay watches it, so each write would start " +
          "another preview build${if (kind == "screens") ", and it would be listed as a screen" else ""}: ${cfg.out}"
      }
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
  val index = ScreenStatesIndex()
  val problem = if (check) ScreenStateRegistryFile.check(dir, config, index) else ScreenStateRegistryFile.emit(dir, config, index)
  if (problem != null) {
    System.err.println(problem)
    kotlin.system.exitProcess(1)
  }
  val gen = ScreenStateRegistryFile.generate(dir, config, index)
  gen?.problems?.forEach { System.err.println("warning: $it") }
  println(
    when {
      gen == null -> "keliver.portal.json has no screenStateRegistry block: nothing written"
      check -> "screen-state registry is up to date (${gen.states} states)"
      else -> "screen-state registry written (${gen.states} states)"
    },
  )
}
