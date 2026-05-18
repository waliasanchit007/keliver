/*
 * Standalone Gradle build for the Konduit sample app. Mirrors how an
 * external adopter would wire their own project — published Konduit
 * artifacts from GitHub Packages, plain `kotlin("multiplatform")` /
 * `com.android.application` plugins (no `redwoodBuild { }` DSL — that
 * one is internal to the Konduit repo).
 *
 * Auth: configure `gpr.user` / `gpr.token` in `~/.gradle/gradle.properties`
 * OR set `GITHUB_ACTOR` / `GITHUB_TOKEN` env vars. Token needs `read:packages`
 * scope. See sample/README.md for the full setup walkthrough.
 */

rootProject.name = "konduit-sample"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  repositories {
    mavenLocal()
    maven {
      url = uri("https://maven.pkg.github.com/waliasanchit007/konduit")
      credentials {
        username = (providers.gradleProperty("gpr.user").orNull
          ?: System.getenv("GITHUB_ACTOR")).orEmpty()
        password = (providers.gradleProperty("gpr.token").orNull
          ?: System.getenv("GITHUB_TOKEN")).orEmpty()
      }
      // Restrict to dev.konduit.* — without this, Gradle queries GH
      // Packages for every transitive dep (kotlin, androidx, etc.) and
      // first builds hang for ~10 min before falling through. Same fix
      // as documented in `konduit/docs/USAGE.md`.
      content {
        includeGroup("dev.konduit")
        includeGroupByRegex("dev\\.konduit\\..*")
      }
    }
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
    maven {
      url = uri("https://maven.pkg.github.com/waliasanchit007/konduit")
      credentials {
        username = (providers.gradleProperty("gpr.user").orNull
          ?: System.getenv("GITHUB_ACTOR")).orEmpty()
        password = (providers.gradleProperty("gpr.token").orNull
          ?: System.getenv("GITHUB_TOKEN")).orEmpty()
      }
      content {
        includeGroup("dev.konduit")
        includeGroupByRegex("dev\\.konduit\\..*")
      }
    }
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
