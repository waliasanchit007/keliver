/*
 * Standalone Gradle build for the Keliver sample app. Mirrors how an
 * external adopter wires their own project — published Keliver artifacts
 * from Maven Central (`dev.keliver:keliver-*:0.1.0`), plain
 * `kotlin("multiplatform")` / `com.android.application` plugins (no
 * `redwoodBuild { }` DSL — that one is internal to the Keliver repo).
 *
 * No credentials required: `mavenCentral()` serves every `dev.keliver`
 * artifact. Clone and build. See sample/README.md.
 */

rootProject.name = "keliver-sample"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  repositories {
    mavenLocal()
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "app.cash.zipline") {
        useModule("app.cash.zipline:zipline-gradle-plugin:${requested.version}")
      }
      // NF2: Keliver's Zipline fork (the prototype stack below).
      if (requested.id.id == "dev.keliver.zipline") {
        useModule("dev.keliver.zipline:zipline-gradle-plugin:${requested.version}")
      }
    }
  }
}

dependencyResolutionManagement {
  repositories {
    mavenLocal()
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
  }
}

// Gate B-e (prototype only, docs/GATE_B_RESULTS.md): with -PkeliverComposePrototype the sample builds
// against Keliver's unpublished Compose-generated group (a scratch Maven repository, version
// 0.0.0-gateb, on main's stack) and adds a debug-only route whose screen is plain Compose against the
// generated API. Without the property nothing here changes: same versions, same modules.
val composePrototype = providers.gradleProperty("keliverComposePrototype").isPresent
if (composePrototype) {
  dependencyResolutionManagement {
    // Gradle would import gradle/libs.versions.toml as `libs` itself; let it use another name, so
    // `libs` is this copy of the same file with the prototype's versions.
    defaultLibrariesExtensionName = "defaultLibs"
    versionCatalogs {
      create("libs") {
        // NF2: main's stack builds on Keliver's Zipline fork (QuickJS 2026): same artifact names and
        // packages, another group, and the plugin id dev.keliver.zipline (pluginManagement above).
        // The fork's group goes into a copy of the TOML: a library or plugin alias declared here AND
        // in the imported TOML resolves to the TOML's entry (Gradle applies the import last and logs
        // "Duplicate entry ... is replaced"), so `library("zipline", "dev.keliver.zipline", ...)`
        // here left `app.cash.zipline` at the fork's version, which does not exist (found by NF5 R1's
        // build, 2026-11-26). An explicit version() does win over the TOML's.
        val catalog = file("build/prototype-catalog/libs.versions.toml")
        catalog.parentFile.mkdirs()
        catalog.writeText(file("gradle/libs.versions.toml").readText().replace("\"app.cash.zipline", "\"dev.keliver.zipline"))
        from(files(catalog))
        version("kotlin", "2.4.21")
        version("ksp", "2.3.14")
        version("keliver", providers.gradleProperty("keliverVersion").getOrElse("0.0.0-gateb"))
        version("zipline", "1.28.0-keliver.1")
        version("composeMultiplatform", "1.12.2")
        // main's Android artifacts require compileSdk 37 (AAR metadata).
        version("android-compileSdk", "37")
      }
    }
  }
  include(":gateb-guest")
  // The three-way benchmark's guest (docs/THREE_WAY_BENCHMARK.md), on the same prototype stack.
  include(":threeway-guest")
  // androidx.compose 1.12 (CMP 1.12.2) declares AGP 9.1 as its minimum; the sample stays on AGP 8.13
  // and Gradle 9.0 (AGP 9 needs a newer Gradle), so prototype builds skip that AAR-metadata check.
  gradle.beforeProject {
    tasks.configureEach {
      if (name.startsWith("check") && name.endsWith("AarMetadata")) {
        // Not `enabled = false`: release-shaped builds (benchmark) bundle lint AARs that take the
        // check's output directory as an input, so the replacement still creates it, empty.
        actions.clear()
        doLast { outputs.files.forEach { it.mkdirs() } }
      }
    }
  }
}

include(":schema")
include(":shared-widget")
include(":shared-modifier")
include(":shared-protocol-host")
include(":shared-protocol-guest")
include(":shared")
include(":guest")
include(":host-compose")
include(":host-android")
include(":benchmarks")
