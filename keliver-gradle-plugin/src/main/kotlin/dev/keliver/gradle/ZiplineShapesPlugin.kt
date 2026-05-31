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
package dev.keliver.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin.CHECK_TASK_NAME
import org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP

/**
 * Auto-registers a `validateZiplineServiceShapes` Gradle task that
 * runs on every `./gradlew check`. The task scans every `.kt` source
 * under `src/` recursively and rejects `ZiplineService` interfaces
 * with function-typed parameters (KNOWN_BUGS U11).
 *
 * Adopters apply this plugin in any module that declares
 * `ZiplineService` interfaces (typically the `:shared` /
 * `:shared-protocol` / `:schema-types` module of an SDUI integration):
 *
 * ```
 * plugins {
 *     id("dev.keliver.zipline-shapes")
 * }
 * ```
 *
 * No configuration required — the default scans every `*.kt` file
 * under `src/`. For custom file layouts adopters can register
 * [ValidateZiplineServiceShapesTask] directly and skip the plugin.
 *
 * Lifecycle: hooks into `check` via `dependsOn`. The task only runs
 * when invoked through `check` (or directly); it doesn't slow down
 * `assemble` or `installDebug`.
 */
@Suppress("unused") // Invoked reflectively by Gradle.
public class ZiplineShapesPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val task = project.tasks.register(
      "validateZiplineServiceShapes",
      ValidateZiplineServiceShapesTask::class.java,
    ) { task ->
      task.group = VERIFICATION_GROUP
      task.description =
        "Reject ZiplineService methods with function-typed parameters (Keliver KNOWN_BUGS U11)."
      // Default: every .kt file under src/ — covers commonMain,
      // commonTest, platform source sets. The task's body skips files
      // that don't mention ZiplineService at all, so the wide net is
      // free.
      task.protocolFiles.from(
        project.fileTree(project.layout.projectDirectory.dir("src")).matching {
          it.include("**/*.kt")
        },
      )
    }
    // Wire into check if the lifecycle plugin (which JVM / Android /
    // KMP all apply) is present. `withType` defers until the plugin
    // applies — works regardless of plugin application order.
    project.plugins.withId("org.gradle.lifecycle-base") {
      project.tasks.named(CHECK_TASK_NAME).configure {
        it.dependsOn(task)
      }
    }
    // Most Kotlin/Java/Android plugins transitively apply
    // lifecycle-base, but if none has yet, also register a deferred
    // hook so adopters who apply this plugin first still get the wire.
    project.afterEvaluate {
      runCatching {
        project.tasks.named(CHECK_TASK_NAME).configure {
          it.dependsOn(task)
        }
      }
    }
  }
}
