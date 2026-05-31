/*
 * Codegen: guest-side widget system factory. Produces
 * `SampleSchemaProtocolWidgetSystemFactory` which the guest's
 * `StandardAppLifecycle` plugs in as `protocolWidgetSystemFactory`.
 *
 * Guest-only target (js); the host has the twin
 * `:shared-protocol-host` module.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.konduit.generator.protocol.guest)
}

redwoodSchema {
  source = project(":schema")
  type = "dev.keliver.sample.schema.SampleSchema"
}

kotlin {
  js {
    browser()
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":shared-widget"))
        implementation(libs.konduit.protocol.guest)
      }
    }
  }
}
