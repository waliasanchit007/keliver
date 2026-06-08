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
