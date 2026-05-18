/*
 * Cross-platform `ZiplineService` contract that both host and guest
 * implement against. The guest's `:guest` module bind()s the impl;
 * each host's `TreehouseApp.Spec.create()` take()s the proxy.
 *
 * Multiplatform target set covers every platform that consumes the
 * service: host runs on jvm + iOS, guest runs on js.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
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
        api(project(":shared-widget"))
        api(libs.zipline)
        api(libs.konduit.treehouse)
        implementation(libs.konduit.protocol)
        implementation(libs.konduit.protocol.host)
      }
    }
  }
}
