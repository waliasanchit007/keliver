/*
 * Copyright (C) 2026 Square, Inc.
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

import java.util.concurrent.Callable
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.Companion.TEST_COMPILATION_NAME
import org.jetbrains.kotlin.gradle.targets.js.ir.JsIrBinary
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget

private const val SKIKO_GROUP = "org.jetbrains.skiko"
private const val COMPOSE_PLUGIN_ID = "org.jetbrains.compose"

/**
 * Puts Skiko's web runtime (`skiko.mjs`, `skiko.wasm` and their companions) beside every js and
 * wasmJs TEST executable whose runtime classpath has Skiko, which is any test that pulls in
 * compose-ui. compose-ui's js and wasm code imports `./skiko.mjs`; without the file the test
 * runner fails before the first test (Node: "Cannot find module", webpack: "Can't resolve").
 *
 * The `org.jetbrains.compose` Gradle plugin does this for every web binary of the modules that
 * apply it (they are left to it). Library modules do not apply it for this: it also adds the
 * runtime to the js MAIN resources (applied to keliver-widget-composeui, its published js klib went
 * from 14 KB to 3.4 MB) and a resources variant to the js and wasmJs publications. Here only the
 * test binaries' sync tasks change, so no published artifact does. The runtime's version is the
 * Skiko version the test classpath resolved, so the runtime always matches compose-ui's bindings.
 */
internal fun Project.configureSkikoForWebTests() {
  plugins.withId("org.jetbrains.kotlin.multiplatform") {
    val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.targets.withType(KotlinJsIrTarget::class.java).configureEach { target ->
      target.compilations.matching { it.name == TEST_COMPILATION_NAME }.configureEach { compilation ->
        val classpathName = compilation.runtimeDependencyConfigurationName
        val runtime = configurations.register("${target.name}TestSkikoWebRuntime") { configuration ->
          configuration.isCanBeConsumed = false
          configuration.isTransitive = false
          configuration.description = "Skiko's web runtime for the ${target.name} test executable."
          configuration.defaultDependencies { dependencySet ->
            if (plugins.hasPlugin(COMPOSE_PLUGIN_ID)) return@defaultDependencies
            val skikoVersion = configurations.getByName(classpathName)
              .incoming.resolutionResult.allComponents
              .map { it.id }
              .filterIsInstance<ModuleComponentIdentifier>()
              // Skiko's own module, not another artifact that might share its group.
              .firstOrNull { it.group == SKIKO_GROUP && it.module.startsWith("skiko") }
              ?.version
              ?: return@defaultDependencies
            dependencySet.add(
              dependencies.create("$SKIKO_GROUP:skiko-js-wasm-runtime:$skikoVersion"),
            )
          }
        }
        val runtimeFiles = files(
          Callable {
            runtime.get().files.map { jar ->
              zipTree(jar).matching { it.exclude("META-INF/**") }
            }
          },
        )
        compilation.binaries.withType(JsIrBinary::class.java).configureEach { binary ->
          binary.linkSyncTask.configure { it.from.from(runtimeFiles) }
        }
      }
    }
  }
}
