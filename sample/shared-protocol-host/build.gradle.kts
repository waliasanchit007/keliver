/*
 * Codegen: host-side protocol adapters. Produces `SampleSchemaHostProtocol`
 * which the host's `TreehouseApp.Spec` plugs into Keliver's
 * `TreehouseAppFactory` as the `hostProtocolFactory` argument.
 *
 * Host-only target set (jvm + iOS); the guest side has its own twin
 * module `:shared-protocol-guest`.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.keliver.generator.protocol.host)
}

redwoodSchema {
  source = project(":schema")
  type = "dev.keliver.sample.schema.SampleSchema"
}

kotlin {
  jvm()
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":shared-widget"))
        implementation(libs.keliver.protocol.host)
      }
    }
  }
}
