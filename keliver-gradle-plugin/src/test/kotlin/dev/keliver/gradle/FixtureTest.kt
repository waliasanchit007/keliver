/*
 * Copyright (C) 2021 Square, Inc.
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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.prop
import java.io.File
import java.util.zip.ZipFile
import org.gradle.testkit.runner.BuildTask
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.Test

class FixtureTest {
  @Test fun schemaProjectAccessor() {
    val fixtureDir = File("src/test/fixture/schema-project-accessor")
    fixtureGradleRunner(fixtureDir, "clean", "assemble").build()
    assertThat(fixtureDir.resolve("widget/build/generated/redwood").walk().toList()).isNotEmpty()
  }

  @Test fun schemaProjectReference() {
    val fixtureDir = File("src/test/fixture/schema-project-reference")
    fixtureGradleRunner(fixtureDir, "clean", "assemble").build()
    assertThat(fixtureDir.resolve("widget/build/generated/redwood").walk().toList()).isNotEmpty()
  }

  @Test fun schemaApiClean() {
    val fixtureDir = File("src/test/fixture/schema-api-clean")
    fixtureGradleRunner(fixtureDir, "clean", "check").build().let { result ->
      val redwoodApiCheck = result.task(":redwoodApiCheck")!!
      assertThat(redwoodApiCheck).prop(BuildTask::getOutcome).isEqualTo(SUCCESS)
    }
    // Ensure the task is properly incremental.
    fixtureGradleRunner(fixtureDir, "redwoodApiCheck").build().let { result ->
      val redwoodApiCheck = result.task(":redwoodApiCheck")!!
      assertThat(redwoodApiCheck).prop(BuildTask::getOutcome).isEqualTo(UP_TO_DATE)
    }
  }

  @Test fun schemaApiDirty() {
    val fixtureDir = File("src/test/fixture/schema-api-dirty")

    // Ensure we have the original dirty file as the test will overwrite it.
    fixtureDir.resolve("keliver-api.xml.original")
      .copyTo(fixtureDir.resolve("keliver-api.xml"), overwrite = true)

    fixtureGradleRunner(fixtureDir, "clean", "check").buildAndFail().let { result ->
      val redwoodApiCheck = result.task(":redwoodApiCheck")!!
      assertThat(redwoodApiCheck).prop(BuildTask::getOutcome).isEqualTo(FAILED)
      assertThat(result.output).contains(
        """
        |API file does not match!
        |
        |Differences:
        | - Widget(tag=2) is missing property(tag=2) which is part of the Kotlin schema (fixable)
        | - Widget(tag=3) is missing property(tag=2) which is part of the Kotlin schema (fixable)
        |
        |Run 'redwoodApiGenerate' to automatically update the file.
        |
        """.trimMargin(),
      )
    }
    fixtureGradleRunner(fixtureDir, "redwoodApiGenerate").build()
    fixtureGradleRunner(fixtureDir, "redwoodApiCheck").build()
  }

  @Test fun schemaApiMissing() {
    val fixtureDir = File("src/test/fixture/schema-api-missing")

    // Ensure we do not have this file. It may be left over from a previous run/failure.
    fixtureDir.resolve("keliver-api.xml").delete()

    fixtureGradleRunner(fixtureDir, "clean", "check").buildAndFail().let { result ->
      val redwoodApiCheck = result.task(":redwoodApiCheck")!!
      assertThat(redwoodApiCheck).prop(BuildTask::getOutcome).isEqualTo(FAILED)
      assertThat(result.output).contains(
        """
        |API file keliver-api.xml missing!
        |
        |Run 'redwoodApiGenerate' to generate this file.
        |
        """.trimMargin(),
      )
    }
    fixtureGradleRunner(fixtureDir, "redwoodApiGenerate").build()
    fixtureGradleRunner(fixtureDir, "redwoodApiCheck").build()
  }

  @Test fun lintNoKotlinFails() {
    val fixtureDir = File("src/test/fixture/lint-no-kotlin")
    val result = fixtureGradleRunner(fixtureDir).buildAndFail()
    assertThat(result.output).contains(
      "'dev.keliver.lint' requires a compatible Kotlin plugin to be applied (root project)",
    )
  }

  @Test fun lintAndroidNoKotlinFails() {
    val fixtureDir = File("src/test/fixture/lint-android-no-kotlin")
    val result = fixtureGradleRunner(fixtureDir).buildAndFail()
    assertThat(result.output).contains(
      "'dev.keliver.lint' requires a compatible Kotlin plugin to be applied (root project)",
    )
  }

  @Test fun lintAndroid() {
    val fixtureDir = File("src/test/fixture/lint-android")
    val result = fixtureGradleRunner(fixtureDir).build()
    val lintTasks = result.tasks.map { it.path }.filter { it.startsWith(":redwoodLint") }
    assertThat(lintTasks).containsExactly(
      ":redwoodLintDebug",
      ":redwoodLintRelease",
      ":redwoodLint",
    )
  }

  @Test fun lintJvm() {
    val fixtureDir = File("src/test/fixture/lint-jvm")
    val result = fixtureGradleRunner(fixtureDir).build()
    val lintTasks = result.tasks.map { it.path }.filter { it.startsWith(":redwoodLint") }
    assertThat(lintTasks).containsExactly(
      ":redwoodLint",
    )
  }

  @Test fun lintMppAndroid() {
    val fixtureDir = File("src/test/fixture/lint-mpp-android")
    val result = fixtureGradleRunner(fixtureDir).build()
    val lintTasks = result.tasks.map { it.path }.filter { it.startsWith(":redwoodLint") }
    assertThat(lintTasks).containsExactly(
      ":redwoodLintAndroidDebug",
      ":redwoodLintAndroidRelease",
      ":redwoodLintJvm",
      ":redwoodLint",
    )
  }

  @Test fun lintMppNoAndroid() {
    val fixtureDir = File("src/test/fixture/lint-mpp-no-android")
    val result = fixtureGradleRunner(fixtureDir).build()
    val lintTasks = result.tasks.map { it.path }.filter { it.startsWith(":redwoodLint") }
    assertThat(lintTasks).containsExactly(
      ":redwoodLintJs",
      ":redwoodLintJvm",
      ":redwoodLint",
    )
  }

  @Test fun protocolWithoutModifiers() {
    // When no layout modifier are present, the serialization codegen contains unused things.
    // Ensure that it does not produce warnings (which are set to error in this build).
    val fixtureDir = File("src/test/fixture/protocol-no-modifiers")
    fixtureGradleRunner(fixtureDir).build()
  }

  @Test fun frameRulesStrict() {
    // NF5 A1 (docs/SHOW_BEFORE_COMPUTE.md 2.4): with `frameRules { strict = true }`, the check fails
    // until the review list (the values a Shell frame keeps) is checked in, then passes in `build`.
    val fixtureDir = File("src/test/fixture/frame-rules-strict")
    val review = File(fixtureDir, "widget-protocol/FramesFrameRules.review.txt")
    review.delete()
    try {
      val failed = fixtureGradleRunner(fixtureDir, ":widget-protocol:redwoodFrameRulesCheck").buildAndFail()
      assertThat(failed.output).contains("e: FramesFrameRules (strict): the review list")
      assertThat(failed.output).contains("    Frames: widget Label.enabled: kotlin.Boolean")
      // The failure names the project's own generate task, not the bare name.
      assertThat(failed.output).contains(":widget-protocol:redwoodFrameRulesGenerate")

      fixtureGradleRunner(fixtureDir, ":widget-protocol:redwoodFrameRulesGenerate").build()
      assertThat(review.readLines()).containsAtLeast("Frames: widget Label", "Frames: widget Label.enabled: kotlin.Boolean")
      assertThat(review.readText()).doesNotContain("size")

      // `build` runs the check, now passing, and compiles the generated FrameRules without warnings.
      val built = fixtureGradleRunner(fixtureDir).build()
      assertThat(built.task(":widget-protocol:redwoodFrameRulesCheck")?.outcome).isEqualTo(SUCCESS)
    } finally {
      review.delete()
    }
  }

  @Test fun hostBaselineProfile() {
    // dev.keliver.generator.host-baseline-profile, from the published plugin and codegen, on an
    // Android app and an Android library (docs/DEVICE_HOST.md, "Baseline profiles").
    val fixtureDir = File("src/test/fixture/host-baseline-profile")
    fixtureGradleRunner(fixtureDir, "clean", ":app:mergeReleaseArtProfile", ":lib:bundleReleaseAar").build()
    val rules = listOf(
      "HSPLexample/counter/protocol/host/**;->**(**)**",
      "HSPLexample/counter/widget/*;->**(**)**",
      "HSPLexample/counter/modifier/*;->**(**)**",
      "Lexample/counter/protocol/host/**;",
      "Lexample/counter/widget/*;",
      "Lexample/counter/modifier/*;",
    )

    // The app: the rules reach the merged profile AGP compiles into the APK.
    val merged = fixtureDir.resolve("app/build/intermediates/merged_art_profile/release").walk()
      .single { it.name == "baseline-prof.txt" }
    assertThat(merged.readLines()).containsAtLeast(*rules.toTypedArray())

    // The library: the rules ship in its AAR, from which an app's build merges them.
    val aar = fixtureDir.resolve("lib/build/outputs/aar").walk().single { it.name.endsWith("release.aar") }
    val aarProfile = ZipFile(aar).use { zip ->
      zip.getEntry("baseline-prof.txt")?.let { entry -> zip.getInputStream(entry).reader().readLines() }
    }
    assertThat(aarProfile).isNotNull().containsAtLeast(*rules.toTypedArray())
  }

  private fun fixtureGradleRunner(
    fixtureDir: File,
    vararg tasks: String = arrayOf("clean", "build"),
  ): GradleRunner {
    val gradleRoot = File(fixtureDir, "gradle").also { it.mkdir() }
    File("../gradle/wrapper").copyRecursively(File(gradleRoot, "wrapper"), true)

    return GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments(*tasks, "--no-build-cache", "--stacktrace", "-PredwoodVersion=$redwoodVersion")
      .withDebug(true) // Do not use a daemon.
  }
}
