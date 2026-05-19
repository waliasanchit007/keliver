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
  // Zipline's IR plugin generates `Adapter` classes for every
  // interface that extends `ZiplineService` (or `AppService`,
  // transitively). WITHOUT this plugin applied here, the host
  // sees a `Adapter` whose constructor doesn't match what Zipline's
  // loader expects, and `codeLoadFailed: Constructor 'Adapter.<init>'
  // can not be called` fires at QuickJS load time. Must be applied
  // to every module that DEFINES a ZiplineService — not just the
  // module that takes/binds it.
  alias(libs.plugins.zipline)
  // Required by Zipline's Adapter codegen — it generates
  // `KSerializer<*>` arguments and reads `.serializer()` lookups
  // for every method param/return type. Without this plugin those
  // lookups fail at QuickJS load time with `Serializer for class 'X'
  // is not found`.
  alias(libs.plugins.kotlinSerialization)
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
