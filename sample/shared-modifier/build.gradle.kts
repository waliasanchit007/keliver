/*
 * Codegen: layout-modifier types. Our schema declares no
 * `@Modifier` types, so this module ends up holding only the base
 * `Modifier` companion + the (empty) modifier serializer module —
 * but the `:shared-widget` and `:shared-protocol-*` modules still
 * need a `Modifier` type to reference, so we keep the module around
 * even with an empty body.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.konduit.generator.modifiers)
}

redwoodSchema {
  source = project(":schema")
  type = "dev.konduit.sample.schema.SampleSchema"
}

kotlin {
  jvm()
  js {
    browser()
  }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(libs.konduit.widget)
      }
    }
  }
}
