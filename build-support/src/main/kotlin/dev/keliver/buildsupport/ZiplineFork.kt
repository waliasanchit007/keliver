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

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.Companion.MAIN_COMPILATION_NAME
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.js.ir.Executable
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget

/**
 * NF2: Keliver builds on its fork of Zipline, `dev.keliver.zipline` (QuickJS 2026, with BigInt).
 * Its Gradle plugin keeps the id `app.cash.zipline`, so this is keyed on that id.
 */
private const val ZIPLINE_PLUGIN_ID = "app.cash.zipline"
internal const val UPSTREAM_ZIPLINE_GROUP = "app.cash.zipline"
internal const val LONG_AS_BIGINT_FLAG = "-Xes-long-as-bigint"

/** `-Pkeliver.longAsBigInt=false` links guests with Kotlin/JS's emulated Long again (measurement). */
private const val LONG_AS_BIGINT_PROPERTY = "keliver.longAsBigInt"

/**
 * `-Pkeliver.allowUpstreamZipline` (or `=true`) skips [refuseUpstreamZipline] (a baseline
 * measurement only); `=false` keeps the check, as `-Pkeliver.longAsBigInt=false` is read.
 */
private const val ALLOW_UPSTREAM_PROPERTY = "keliver.allowUpstreamZipline"

/** Whether the value of `-Pkeliver.allowUpstreamZipline` (null when absent) skips the check. */
internal fun allowsUpstreamZipline(value: String?): Boolean = value != null && value.trim() != "false"

/**
 * Every Zipline guest (a project applying the Zipline plugin) links its Kotlin/JS executables,
 * development and production, with Kotlin's `Long` as a JavaScript BigInt (`-Xes-long-as-bigint`):
 * the link generates the JavaScript of every library, so this covers all of them. Only the fork's
 * engine can run such a bundle (QuickJS 2021 has no BigInt); docs/GUEST_PROFILE.md measured it
 * (emulated Long was 38% of the guest's time in its hottest path). Not wasm: wasm has 64-bit
 * integers of its own.
 */
internal fun Project.configureZiplineGuestLongAsBigInt() {
  plugins.withId(ZIPLINE_PLUGIN_ID) {
    if (providers.gradleProperty(LONG_AS_BIGINT_PROPERTY).orNull == "false") return@withId
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
      val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
      kotlin.targets.withType(KotlinJsIrTarget::class.java).configureEach { target ->
        if (target.platformType != KotlinPlatformType.js) return@configureEach
        target.compilations.matching { it.name == MAIN_COMPILATION_NAME }.configureEach { compilation ->
          compilation.binaries.withType(Executable::class.java).configureEach { binary ->
            binary.linkTask.configure { link ->
              link.compilerOptions.freeCompilerArgs.add(LONG_AS_BIGINT_FLAG)
            }
          }
        }
      }
    }
  }
}

/**
 * One Zipline: no configuration of this build may resolve upstream's `app.cash.zipline` modules.
 * The fork's modules declare upstream's capabilities, so most mixes already fail with Gradle's
 * capability conflict; this also catches one that a resolution strategy settled, or a configuration
 * holding upstream alone (for example a dependency that names `app.cash.zipline` directly), with a
 * message that says what to do. Upstream's 1.28.0 compiles QuickJS 2021 bytecode, which the fork's
 * engine cannot load, and two copies of `app.cash.zipline`'s classes on one classpath load whichever
 * comes first.
 */
internal fun Project.refuseUpstreamZipline() {
  if (allowsUpstreamZipline(providers.gradleProperty(ALLOW_UPSTREAM_PROPERTY).orNull)) return
  val owner = path
  configurations.configureEach { configuration -> refuseUpstreamZiplineIn(configuration, "project $owner") }
  // The build script's classpath (the Zipline Gradle plugin among it) is resolved before any plugin
  // is applied, so the root project checks what it already resolved.
  if (this == rootProject) {
    val classpath = buildscript.configurations.findByName("classpath") ?: return
    val upstream = upstreamZiplineModules(classpath.incoming.resolutionResult.allComponents.map { it.id })
    if (upstream.isNotEmpty()) throw upstreamZiplineFailure(upstream, "the root build script's classpath")
  }
}

private fun refuseUpstreamZiplineIn(configuration: Configuration, owner: String) {
  val name = configuration.name
  configuration.incoming.afterResolve { resolved ->
    val upstream = upstreamZiplineModules(resolved.resolutionResult.allComponents.map { it.id })
    if (upstream.isNotEmpty()) throw upstreamZiplineFailure(upstream, "configuration '$name' of $owner")
  }
}

internal fun upstreamZiplineModules(ids: Iterable<ComponentIdentifier>): List<String> =
  ids.filterIsInstance<ModuleComponentIdentifier>()
    .filter { it.group == UPSTREAM_ZIPLINE_GROUP }
    .map { it.displayName }
    .distinct()
    .sorted()

private fun upstreamZiplineFailure(upstream: List<String>, where: String) = GradleException(
  "keliver: $where resolved upstream Zipline (${upstream.joinToString()}). Keliver builds on its " +
    "fork, dev.keliver.zipline (QuickJS 2026): bundles the fork compiles do not load on upstream's " +
    "engine, and two copies of app.cash.zipline's classes on one classpath load whichever comes " +
    "first. Depend on dev.keliver.zipline:<the same artifact> (the version catalog's libs.zipline*) " +
    "instead. -P$ALLOW_UPSTREAM_PROPERTY skips this check, for a baseline measurement only.",
)
