/*
 * Copyright (C) 2026 Konduit contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.keliver.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.io.File
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundleSizeBudgetTaskTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun bundleFile(name: String, bytes: Int): File {
    val f = tempFolder.newFile(name)
    f.writeBytes(ByteArray(bytes))
    return f
  }

  private fun task(
    files: Collection<File>,
    max: Long,
    warn: Long? = null,
  ): BundleSizeBudgetTask {
    val project = ProjectBuilder.builder().build()
    return project.tasks
      .create("checkBundleSize", BundleSizeBudgetTask::class.java)
      .also {
        it.bundleFiles.from(files)
        it.maxBytes.set(max)
        if (warn != null) it.warnAtBytes.set(warn)
      }
  }

  @Test
  fun passes_when_total_is_under_budget() {
    val t = task(
      files = listOf(bundleFile("a.zipline", 100), bundleFile("b.zipline", 200)),
      max = 1_000L,
    )

    t.run()  // does not throw
  }

  @Test
  fun fails_when_total_exceeds_budget() {
    val t = task(
      files = listOf(bundleFile("a.zipline", 600), bundleFile("b.zipline", 600)),
      max = 1_000L,
    )

    try {
      t.run()
      throw AssertionError("expected GradleException")
    } catch (e: GradleException) {
      val msg = e.message ?: ""
      assertThat(msg).contains("exceeds budget")
      assertThat(msg).contains("Over by:")
    }
  }

  @Test
  fun nonexistent_files_are_silently_ignored() {
    val real = bundleFile("real.zipline", 200)
    val phantom = File(tempFolder.root, "missing.zipline")
    val t = task(files = listOf(real, phantom), max = 1_000L)

    t.run()  // doesn't fail despite phantom not existing
  }

  @Test
  fun warn_threshold_does_not_fail() {
    val t = task(
      files = listOf(bundleFile("a.zipline", 850)),
      max = 1_000L,
      warn = 800L,
    )

    t.run()  // 850 >= 800 → warning, but not exception
  }

  @Test
  fun error_message_lists_largest_files_first() {
    val small = bundleFile("small.zipline", 100)
    val large = bundleFile("large.zipline", 950)
    val t = task(files = listOf(small, large), max = 500L)

    try {
      t.run()
      throw AssertionError("expected GradleException")
    } catch (e: GradleException) {
      val msg = e.message ?: ""
      val largeIdx = msg.indexOf("large.zipline")
      val smallIdx = msg.indexOf("small.zipline")
      assertThat(largeIdx in 0..<smallIdx).isEqualTo(true)
    }
  }

  @Test
  fun empty_bundle_files_passes() {
    val t = task(files = emptyList(), max = 1_000L)
    t.run()  // 0 bytes < budget — passes
  }
}
