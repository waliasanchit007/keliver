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
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fail the build if the sum of [bundleFiles] sizes exceeds [maxBytes].
 *
 * The QuickJS guest bundle ships as a manifest plus N module
 * `.zipline` files; meaningful size is the sum across them all. The
 * task input is a `ConfigurableFileCollection` so adopters point it
 * at whatever output layout their guest module produces:
 *
 * ```
 * tasks.register<dev.konduit.gradle.BundleSizeBudgetTask>("checkBundleSize") {
 *     bundleFiles.from(fileTree("build/zipline") { include("*.zipline") })
 *     maxBytes.set(500_000L)               // 500 KB
 *     warnAtBytes.set(400_000L)            // optional — warn at 80%
 * }
 * tasks.named("check") { dependsOn("checkBundleSize") }
 * ```
 *
 * The success log message includes the current size, the budget, and
 * the remaining headroom so adopters can see how close they are to
 * the limit on every CI run:
 *
 * ```
 * Bundle size: 312,847 bytes (budget: 500,000 bytes, 187,153 headroom)
 * ```
 *
 * Why a Gradle task rather than a CI script: keeps the budget
 * versioned with the build, runs deterministically off the same input
 * files every time, and avoids OS-specific `du` / `find` shell-script
 * portability issues. It's also automatically hookable into `check`
 * so size regressions block PRs the same way test failures do.
 */
public abstract class BundleSizeBudgetTask : DefaultTask() {

  /**
   * Files counted toward the budget. Adopters typically pass a
   * [fileTree] over the guest module's zipline output directory.
   * Files that don't exist on disk at task-execution time are
   * silently ignored (so a fresh build that hasn't compiled the
   * guest doesn't fail this check).
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val bundleFiles: ConfigurableFileCollection

  /**
   * Hard budget in bytes. Exceeding this fails the build with a
   * diagnostic message naming the actual size and the budget.
   */
  @get:Input
  public abstract val maxBytes: Property<Long>

  /**
   * Optional soft threshold in bytes. If set and the current size is
   * `[warnAtBytes, maxBytes)`, the task emits a warning but doesn't
   * fail. Useful for catching size growth before it hits the hard
   * budget.
   */
  @get:Input
  @get:Optional
  public abstract val warnAtBytes: Property<Long>

  @TaskAction
  internal fun run() {
    val files = bundleFiles.files.filter { it.exists() && it.isFile }
    val totalBytes = files.sumOf { it.length() }
    val budget = maxBytes.get()
    val warnThreshold = warnAtBytes.orNull

    val sizeLabel = "%,d".format(totalBytes)
    val budgetLabel = "%,d".format(budget)

    when {
      totalBytes > budget -> {
        val over = totalBytes - budget
        throw GradleException(
          buildString {
            appendLine("Bundle size exceeds budget.")
            appendLine("  Size:    $sizeLabel bytes")
            appendLine("  Budget:  $budgetLabel bytes")
            appendLine("  Over by: %,d bytes".format(over))
            appendLine()
            appendLine("Files counted:")
            files.sortedByDescending { it.length() }.forEach {
              appendLine("  %,12d  ${it.name}".format(it.length()))
            }
            appendLine()
            appendLine("To raise the budget, bump `maxBytes` on the task")
            appendLine("definition. To diagnose, look at the largest files above")
            appendLine("— Kotlin/JS bundles often grow from accidental adoption")
            appendLine("of large transitive deps (Coil, Ktor full client, etc.)")
            appendLine("on the GUEST side. Check the guest module's")
            appendLine("`dependencies {}` for anything that doesn't need to be")
            appendLine("there.")
          },
        )
      }

      warnThreshold != null && totalBytes >= warnThreshold -> {
        val headroom = budget - totalBytes
        logger.warn(
          "Bundle size approaching budget: $sizeLabel / $budgetLabel bytes (%,d headroom).".format(headroom),
        )
      }

      else -> {
        val headroom = budget - totalBytes
        logger.lifecycle(
          "Bundle size: $sizeLabel bytes (budget: $budgetLabel bytes, %,d headroom)".format(headroom),
        )
      }
    }
  }
}
