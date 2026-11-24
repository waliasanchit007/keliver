/*
 * Three-way benchmark (prototype only; included by settings.gradle.kts with
 * -PkeliverComposePrototype): the same two screens (a list from an API, and a detail) as native
 * Compose, as a Keliver guest, and as a web page. See docs/THREE_WAY_BENCHMARK.md.
 *
 *   commonMain: the data model and its parser (kotlinx.serialization), the screens' data and timing
 *               interfaces, the app service and the host service the guest reports timings to
 *               (jvm + js: the Android host uses the jvm side).
 *   jsMain:     the guest (`main`, the service, HostHttp) and the screens in ThreewayScreens.kt.
 *
 * The host builds a native copy of ThreewayScreens.kt (imports rewritten), so the same screen source
 * runs as native Compose too.
 */
import app.cash.zipline.loader.SignatureAlgorithmId

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.zipline)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.ksp)
}

zipline {
  signingKeys {
    // The sample's committed DEMO key (see guest/build.gradle.kts); never a real key.
    create("keliver-sample-ed25519") {
      algorithmId.set(SignatureAlgorithmId.Ed25519)
      privateKeyHex.set("2d69a8f0d62f16b05aba4d1ef5bbbb74c6a1e7397825d0a2dd3a610c942357ad")
    }
  }
}

kotlin {
  jvm()
  js {
    browser()
    binaries.executable()
    // NF2: Kotlin's Long as a JavaScript BigInt, as build-support links Keliver's own guests. Only
    // the Zipline fork's engine (QuickJS 2026, this prototype stack's) runs such a bundle.
    // -Pkeliver.longAsBigInt=false links the emulated Long again (the baseline measurement), as in
    // Keliver's own build (build-support's ZiplineFork.kt).
    if (providers.gradleProperty("keliver.longAsBigInt").orNull != "false") {
      compilerOptions.freeCompilerArgs.add("-Xes-long-as-bigint")
    }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(libs.zipline)
        api(libs.keliver.treehouse)
        api(libs.keliver.http)
      }
    }
    val jsMain by getting {
      dependencies {
        implementation(libs.keliver.compose)
        implementation(libs.keliver.treehouse.guest)
        implementation("dev.keliver:keliver-compose-gatea-compose:${libs.versions.keliver.get()}")
        implementation("dev.keliver:keliver-compose-gatea-protocol-guest:${libs.versions.keliver.get()}")
        // The `retain` experiment's retained stack (RetainedStack.kt).
        implementation("dev.keliver:keliver-nav:${libs.versions.keliver.get()}")
      }
    }
  }
}

dependencies {
  add("kspCommonMainMetadata", libs.keliver.treehouse.codegen)
}

// The common source set reads the metadata KSP's output, so every other compile and KSP task waits for it.
tasks.configureEach {
  if (name != "kspCommonMainKotlinMetadata" && (name.startsWith("ksp") || name.startsWith("compile"))) {
    dependsOn("kspCommonMainKotlinMetadata")
  }
}

kotlin.sourceSets.commonMain.configure {
  kotlin.srcDir("${layout.buildDirectory.get()}/generated/ksp/metadata/commonMain/kotlin")
}
