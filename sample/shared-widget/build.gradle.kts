/*
 * Codegen: widget interfaces (host + guest). Produces `BoxWidget`,
 * `TextWidget` etc. interfaces that the host renderer implements and
 * that the guest's @Composable functions emit.
 *
 * Target set must cover both the host (jvm + iosArm64 + iosSimulatorArm64)
 * and the guest (js). Mixing all four in one multiplatform module is
 * what lets `:shared` and `:guest` share generated types.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.konduit.generator.widget)
}

redwoodSchema {
  source = project(":schema")
  type = "dev.keliver.sample.schema.SampleSchema"
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
        api(project(":shared-modifier"))
      }
    }
  }
}
