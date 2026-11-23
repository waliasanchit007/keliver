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
package dev.keliver.buildsupport

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

/**
 * Mocha's per-test timeout, in milliseconds, for every Kotlin/JS and Kotlin/Wasm test task: Node.js
 * (`jsNodeTest`, mocha run by Node) and browser (`jsBrowserTest`, `wasmJsBrowserTest`, mocha run
 * by karma in headless Chrome). Mocha's own default is 2 s.
 *
 * Tests that take a few milliseconds locally have exceeded 2 s on loaded hosted macOS runners,
 * and not just one test: `KeliverNavPreparationTest.keysAreChecked[js, node]` (3.8 s in a job of
 * 1 h 45 m; it pays the task's first source-map load, because Compose logs the stack of the
 * composition the test makes throw) and
 * `CompiledComponentEditorTest.aComponentThatThrowsWhileComposingFallsBackAndTheCanvasStillRenders`
 * `[wasmJs, browser, ChromeHeadless]`. 15 s absorbs that slowness. A test that hangs still fails,
 * with mocha's "Timeout of 15000ms exceeded" naming it; karma's `browserNoActivityTimeout` (30 s
 * by default) outlasts it, so a browser hang is reported as that timeout, not as a lost browser.
 *
 * A module that needs longer sets it after `redwoodBuild { }` (keliver-leak-detector does both):
 * for Node.js a `useMocha { timeout = … }` in its build file (it reuses the framework configured
 * here, so its value wins); for browsers a file in its own `karma.config.d`, which is still read,
 * after this one, and this one never lowers a timeout already set.
 */
internal const val JS_TEST_TIMEOUT_MILLIS = 15_000

private const val KARMA_TIMEOUT_FILE = "00-keliver-mocha-timeout.js"

internal fun Project.configureJsTestTimeouts() {
  val karmaConfigDir = layout.buildDirectory.dir("keliver-karma.config.d")
  val karmaConfig = tasks.register("keliverKarmaConfig", KarmaConfigDirectory::class.java) {
    it.description = "Karma config for this module's browser tests: Keliver's mocha timeout, " +
      "then the module's own karma.config.d."
    it.mochaTimeoutMillis.set(JS_TEST_TIMEOUT_MILLIS)
    it.moduleConfigFiles.from(fileTree("karma.config.d") { tree -> tree.include("*.js") })
    it.outputDirectory.set(karmaConfigDir)
  }

  // Registered when the build plugin is applied, so before any target's test task configuration:
  // the framework is created here, and a module's later useMocha or useKarma block reuses it
  // (KotlinJsTest only replaces a framework of a different kind). KGP's browser()/nodejs() add
  // their defaults only when no framework exists yet, hence the explicit ChromeHeadless below.
  // A module wanting another browser uses its karma.config.d (`browsers`), as leak-detector does.
  tasks.withType(KotlinJsTest::class.java).configureEach { task ->
    when {
      task.name.endsWith("NodeTest") -> task.useMocha {
        timeout = "${JS_TEST_TIMEOUT_MILLIS}ms"
      }
      task.name.endsWith("BrowserTest") -> {
        task.useKarma {
          useChromeHeadless() // KGP's own default browser.
          // Replaces KGP's default directory (<module>/karma.config.d); the task copies its files.
          useConfigDirectory(karmaConfigDir.get().asFile)
        }
        task.inputs.files(karmaConfig).withPropertyName("keliverKarmaConfig")
          .withPathSensitivity(PathSensitivity.RELATIVE)
      }
    }
  }
}

/** Writes Keliver's mocha timeout for karma, then copies the module's own `karma.config.d` files. */
@DisableCachingByDefault(because = "Writes and copies a few small files")
internal abstract class KarmaConfigDirectory : DefaultTask() {
  @get:Input
  abstract val mochaTimeoutMillis: Property<Int>

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val moduleConfigFiles: ConfigurableFileCollection

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun write() {
    val out = outputDirectory.get().asFile
    out.deleteRecursively()
    out.mkdirs()
    val timeout = mochaTimeoutMillis.get()
    File(out, KARMA_TIMEOUT_FILE).writeText(
      """
      |// Written by Keliver's build (build-support JsTestTimeouts.kt): mocha's per-test timeout for
      |// browser tests. Never lowers a timeout already set; a module's own karma.config.d files
      |// follow this one and may raise it.
      |config.client.mocha = Object.assign({}, config.client.mocha);
      |if (!(config.client.mocha.timeout >= $timeout)) {
      |  config.client.mocha.timeout = $timeout;
      |}
      |
      """.trimMargin(),
    )
    for (file in moduleConfigFiles.files.sortedBy { it.name }) {
      check(file.name != KARMA_TIMEOUT_FILE) { "$file shadows Keliver's $KARMA_TIMEOUT_FILE" }
      file.copyTo(File(out, file.name))
    }
  }
}
