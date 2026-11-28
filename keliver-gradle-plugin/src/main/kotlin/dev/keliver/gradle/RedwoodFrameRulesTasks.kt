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
package dev.keliver.gradle

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import org.gradle.language.base.plugins.LifecycleBasePlugin.BUILD_GROUP
import org.gradle.language.base.plugins.LifecycleBasePlugin.CHECK_TASK_NAME
import org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

/**
 * NF5's frame rules (docs/SHOW_BEFORE_COMPUTE.md 2.4), in the module that applies
 * `dev.keliver.generator.protocol.host`:
 *
 * ```
 * redwoodSchema {
 *   source = projects.schema
 *   type = 'com.example.MySchema'
 *   frameRules { strict = true }
 * }
 * ```
 */
public abstract class FrameRulesExtension {
  /**
   * Whether the checked-in review list (`<Schema>FrameRules.review.txt` in this module, the values a
   * `Shell` frame keeps) must match the schema's. With it, `check` fails until every such value is
   * classified in the schema (`@NotData`, `@NeverCached`) or accepted into the list with
   * `redwoodFrameRulesGenerate`. Without it (the default) the list is printed as a warning.
   */
  public abstract val strict: Property<Boolean>
}

internal const val FRAME_RULES_GENERATE_TASK_NAME = "redwoodFrameRulesGenerate"

/** Registers the review list's check (wired into `check`) and its generate task. */
internal fun Project.registerFrameRulesTasks(
  extension: RedwoodGeneratorExtension,
  toolClasspath: FileCollection,
  schemaClasspath: FileCollection,
) {
  extension.frameRules.strict.convention(false)
  val reviewFile = extension.type.map { layout.projectDirectory.file("${flatName(it)}FrameRules.review.txt") }

  tasks.register(FRAME_RULES_GENERATE_TASK_NAME, RedwoodFrameRulesGenerateTask::class.java) {
    it.group = BUILD_GROUP
    it.description = "Write the schema's frame-rules review list (the values a Shell frame keeps)"
    it.toolClasspath.from(toolClasspath)
    it.classpath.from(schemaClasspath)
    it.schemaType.set(extension.type)
    it.reviewFile.set(reviewFile)
  }
  val check = tasks.register("redwoodFrameRulesCheck", RedwoodFrameRulesCheckTask::class.java) {
    it.group = VERIFICATION_GROUP
    it.description = "Check the schema's frame-rules review list (strict: fail until it matches)"
    it.toolClasspath.from(toolClasspath)
    it.classpath.from(schemaClasspath)
    it.schemaType.set(extension.type)
    it.reviewFile.set(reviewFile)
    it.strict.set(extension.frameRules.strict)
    it.dummyOutputFile.set(layout.buildDirectory.file("tmp/redwoodFrameRulesCheckDummy.txt"))
  }
  tasks.matching { it.name == CHECK_TASK_NAME }.configureEach { it.dependsOn(check) }
}

/** `KeliverComposeGateA` for `dev.keliver.compose.gatea.KeliverComposeGateA`, as codegen names it. */
private fun flatName(type: String): String =
  type.split('.').dropWhile { it.firstOrNull()?.isUpperCase() != true }.joinToString("")

@CacheableTask
internal abstract class RedwoodFrameRulesCheckTask @Inject constructor(
  private val workerExecutor: WorkerExecutor,
) : DefaultTask() {
  @get:Classpath
  abstract val toolClasspath: ConfigurableFileCollection

  @get:Classpath
  abstract val classpath: ConfigurableFileCollection

  @get:Input
  abstract val schemaType: Property<String>

  @get:Input
  abstract val strict: Property<Boolean>

  @get:InputFiles
  @get:PathSensitive(RELATIVE)
  abstract val reviewFile: RegularFileProperty

  /** Empty; lets Gradle skip the check when nothing changed. */
  @get:OutputFile
  abstract val dummyOutputFile: RegularFileProperty

  @TaskAction
  fun run() {
    dummyOutputFile.get().asFile.apply {
      parentFile.mkdirs()
      writeBytes(byteArrayOf())
    }
    submit(workerExecutor, toolClasspath, classpath, schemaType, reviewFile, "check", strict.get())
  }
}

@DisableCachingByDefault(because = "Writes the reviewed list into the source tree; it must never come from a cache.")
internal abstract class RedwoodFrameRulesGenerateTask @Inject constructor(
  private val workerExecutor: WorkerExecutor,
) : DefaultTask() {
  @get:Classpath
  abstract val toolClasspath: ConfigurableFileCollection

  @get:Classpath
  abstract val classpath: ConfigurableFileCollection

  @get:Input
  abstract val schemaType: Property<String>

  @get:OutputFile
  abstract val reviewFile: RegularFileProperty

  @TaskAction
  fun run() {
    submit(workerExecutor, toolClasspath, classpath, schemaType, reviewFile, "generate", strict = false)
  }
}

private fun submit(
  workerExecutor: WorkerExecutor,
  toolClasspath: FileCollection,
  classpath: FileCollection,
  schemaType: Property<String>,
  reviewFile: RegularFileProperty,
  mode: String,
  strict: Boolean,
) {
  workerExecutor.noIsolation().submit(RedwoodFrameRulesWorker::class.java) {
    it.toolClasspath.from(toolClasspath)
    it.classpath.from(classpath)
    it.schemaType.set(schemaType)
    it.reviewFile.set(reviewFile)
    it.mode.set(mode)
    it.strict.set(strict)
  }
}

private interface RedwoodFrameRulesParameters : WorkParameters {
  val toolClasspath: ConfigurableFileCollection
  val classpath: ConfigurableFileCollection
  val schemaType: Property<String>
  val reviewFile: RegularFileProperty
  val mode: Property<String>
  val strict: Property<Boolean>
}

private abstract class RedwoodFrameRulesWorker @Inject constructor(
  private val execOperations: ExecOperations,
) : WorkAction<RedwoodFrameRulesParameters> {
  override fun execute() {
    execOperations.javaexec { exec ->
      exec.classpath = parameters.toolClasspath
      exec.mainClass.set("dev.keliver.tooling.codegen.Main")
      exec.args = buildList {
        add("frame-rules")
        add("--mode")
        add(parameters.mode.get())
        add("--review")
        add(parameters.reviewFile.get().asFile.absolutePath)
        add("--fix-with")
        add(FRAME_RULES_GENERATE_TASK_NAME)
        if (parameters.strict.get()) add("--strict")
        add("--class-path")
        add(parameters.classpath.files.joinToString(File.pathSeparator))
        add(parameters.schemaType.get())
      }
    }
  }
}
