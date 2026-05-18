/*
 * Copyright (C) 2026 Konduit contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.konduit.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Reject `ZiplineService` interface methods with function-typed
 * parameters — the silent-failure shape documented as KNOWN_BUGS U11.
 *
 * Adopters typically wire this up by applying the
 * `dev.konduit.zipline-shapes` plugin, which auto-registers a
 * `validateZiplineServiceShapes` task scanning `src` recursively and
 * hooks into the `check` lifecycle. Power users can register the task
 * directly:
 *
 * ```
 * tasks.register<dev.konduit.gradle.ValidateZiplineServiceShapesTask>(
 *     "validateZiplineServiceShapes",
 * ) {
 *     protocolFiles.from(fileTree("src/commonMain/kotlin"))
 * }
 * tasks.named("check") { dependsOn("validateZiplineServiceShapes") }
 * ```
 *
 * On failure the task throws a `GradleException` whose message names
 * every offending interface, the offending parameter, and the
 * canonical workaround (a callback `ZiplineService` like
 * `SnackbarResultCallback`).
 */
public abstract class ValidateZiplineServiceShapesTask : DefaultTask() {

  /**
   * Kotlin source files to scan. Adopters typically pass the
   * `commonMain` source tree of the module that declares the
   * `ZiplineService` interfaces (e.g. `shared/src/commonMain/kotlin`).
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val protocolFiles: ConfigurableFileCollection

  @TaskAction
  internal fun run() {
    val findings = mutableListOf<ZiplineShapeScanner.Finding>()
    protocolFiles.asFileTree.visit { details ->
      if (details.isDirectory) return@visit
      if (!details.file.name.endsWith(".kt")) return@visit
      val content = details.file.readText()
      // Skip files that don't reference ZiplineService at all — cheap
      // string check before invoking the full scanner.
      if ("ZiplineService" !in content) return@visit
      findings += ZiplineShapeScanner.scan(
        content = content,
        sourceLabel = details.relativePath.pathString,
      )
    }
    if (findings.isNotEmpty()) {
      throw GradleException(ZiplineShapeScanner.formatError(findings))
    }
  }
}
