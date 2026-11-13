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
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
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
    }
  }
}

dependencyResolutionManagement {
  repositories {
    mavenLocal()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
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
        from(files("gradle/libs.versions.toml"))
        version("kotlin", "2.4.20")
        version("ksp", "2.3.13")
        version("keliver", providers.gradleProperty("keliverVersion").getOrElse("0.0.0-gateb"))
        version("zipline", "1.28.0")
        version("composeMultiplatform", "1.12.2")
        // main's Android artifacts require compileSdk 37 (AAR metadata).
        version("android-compileSdk", "37")
      }
    }
  }
  include(":gateb-guest")
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
