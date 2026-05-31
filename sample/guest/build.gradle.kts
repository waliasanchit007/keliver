/*
 * The Kotlin/JS guest bundle. Compiled to JS by the Kotlin
 * multiplatform plugin, then packaged into a Zipline bundle by the
 * `app.cash.zipline` plugin. The bundle's `manifest.zipline.json`
 * (under `build/zipline/Development/`) is what the host loads at
 * runtime.
 *
 * `redwoodSchema` here drives the `generator.compose` plugin's
 * codegen: it emits `@Composable fun Box(...)` / `Text(...)`
 * top-level functions that match the schema, which `Ui.kt` calls.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.zipline)
  alias(libs.plugins.keliver.generator.compose)
  alias(libs.plugins.composeMultiplatform)
}

redwoodSchema {
  source = project(":schema")
  type = "dev.keliver.sample.schema.SampleSchema"
}

kotlin {
  js {
    browser()
    binaries.executable()
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(libs.keliver.compose)
        implementation(libs.keliver.widget)
        implementation(libs.keliver.treehouse)
        implementation(libs.keliver.treehouse.guest)
        implementation(libs.zipline)
        implementation(project(":shared"))
      }
    }
    val jsMain by getting {
      dependencies {
        implementation(project(":shared-protocol-guest"))
        implementation(project(":shared-widget"))
      }
    }
  }
}
