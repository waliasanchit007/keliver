/*
 * Copyright (C) 2026 Keliver contributors
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

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin.BUILD_GROUP

/**
 * `dev.keliver.generator.host-baseline-profile`: an Android baseline profile for the host code the
 * schema generators write (the host protocol, the widget interfaces and the modifiers of a schema
 * and its dependencies), added to the Android module this plugin is applied to. Off unless applied.
 *
 * Apply it to the Android module that ships the app (the `com.android.application` module) or to an
 * Android library the app depends on (its AAR then carries the rules, as `keliver-treehouse-host`'s
 * does), and name the schema as for the generator plugins:
 *
 * ```
 * plugins { id("dev.keliver.generator.host-baseline-profile") }
 * keliverHostBaselineProfile {
 *   source = project(":schema")
 *   type = "com.example.schema.MySchema"
 * }
 * ```
 *
 * The rules go to every variant's `baselineProfiles` sources (AGP merges them with the libraries'
 * profiles). It is a separate plugin, not an option of `dev.keliver.generator.protocol.host`,
 * because the generated host code usually lives in a JVM-only module, and AGP takes a library's
 * baseline profile only from an AAR: a JAR's would never reach the app.
 */
public class RedwoodHostBaselineProfilePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create(
      "keliverHostBaselineProfile",
      RedwoodGeneratorExtension::class.java,
    )

    val toolingConfiguration = project.configurations.register("keliverHostBaselineProfileCodegen") {
      it.isCanBeConsumed = false
      it.isVisible = false
    }
    project.dependencies.add(toolingConfiguration.name, project.redwoodDependency("keliver-tooling-codegen"))

    val schemaConfiguration = project.configurations.register("keliverHostBaselineProfileSchema") {
      it.isCanBeConsumed = false
      it.isVisible = false
    }

    val generate = project.tasks.register(
      "keliverHostBaselineProfileGenerate",
      RedwoodGeneratorTask::class.java,
    ) {
      it.group = BUILD_GROUP
      it.description = "Generate baseline-profile rules for the schema's generated host code"

      it.toolClasspath.from(project.files(toolingConfiguration))
      it.outputDir.set(project.redwoodGeneratedDir("hostBaselineProfile"))
      it.generatorFlag.set("--host-baseline-profile")
      it.schemaType.set(extension.type)
      it.classpath.from(project.files(schemaConfiguration))
    }

    var hasAndroid = false
    project.plugins.withId("com.android.application") {
      hasAndroid = true
      val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
      components.onVariants(components.selector().all()) { it.addHostBaselineProfile(generate) }
    }
    project.plugins.withId("com.android.library") {
      hasAndroid = true
      val components = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
      components.onVariants(components.selector().all()) { it.addHostBaselineProfile(generate) }
    }

    project.afterEvaluate {
      check(hasAndroid) {
        "dev.keliver.generator.host-baseline-profile needs com.android.application or " +
          "com.android.library in ${project.path}: apply it to the Android module that ships the app."
      }
      check(extension.source.isPresent && extension.type.isPresent) {
        "keliverHostBaselineProfile { source = …; type = … } must name the schema in ${project.path}."
      }
      project.dependencies.add(schemaConfiguration.name, extension.source.get())
    }
  }

  private fun Variant.addHostBaselineProfile(generate: TaskProvider<RedwoodGeneratorTask>) {
    sources.baselineProfiles?.addGeneratedSourceDirectory(generate, RedwoodGeneratorTask::outputDir)
  }
}
