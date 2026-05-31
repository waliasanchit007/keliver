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
  // KSP runs the `keliver-treehouse-codegen` processor that emits
  // `Generated<Name>Adapter` for `@KeliverAppService`-annotated
  // interfaces. Drops the ~70-line manual adapter file to a
  // ~5-line companion-object stub on the interface itself.
  alias(libs.plugins.ksp)
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
        api(libs.keliver.treehouse)
        implementation(libs.keliver.protocol)
        implementation(libs.keliver.protocol.host)
      }
    }
  }
}

// Run the @KeliverAppService processor against commonMain. KSP for
// multiplatform projects needs an explicit target-per-source-set
// configuration; the `kspCommonMainMetadata` config below feeds the
// generator output into every target's compilation. Mirrors what
// adopters using the codegen on a multiplatform module need to
// write themselves.
dependencies {
  add("kspCommonMainMetadata", libs.keliver.treehouse.codegen)
}

// Workaround for https://github.com/google/ksp/issues/1318 — make
// every target's compileKotlin task depend on the metadata KSP
// task so the generated sources are visible everywhere.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().all {
  if (name != "kspCommonMainKotlinMetadata") {
    dependsOn("kspCommonMainKotlinMetadata")
  }
}

// Tell every commonMain-derived source set to also include the
// metadata KSP output. Without this, only `commonMain` itself
// sees the generated sources; jvm/js/iOS targets compile against
// the un-generated source set and fail to resolve the generated
// class name.
kotlin.sourceSets.commonMain.configure {
  kotlin.srcDir("${layout.buildDirectory.get()}/generated/ksp/metadata/commonMain/kotlin")
}
