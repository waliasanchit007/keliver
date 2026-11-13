/*
 * Gate B-e (prototype only; included by settings.gradle.kts with -PkeliverComposePrototype): a
 * second guest bundle whose screen is plain Compose against Keliver's Compose-generated API
 * (`dev.keliver.compose.*`), with the animation benchmark in both forms. See docs/GATE_B_RESULTS.md.
 *
 *   commonMain: the app service the Android host takes (jvm + js, as :shared).
 *   jsMain:     the guest (`main`, the service, the screen in AnimationBench.kt).
 *
 * The host builds a native copy of AnimationBench.kt (imports rewritten), so the same source runs
 * as native Compose too.
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
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(libs.zipline)
        api(libs.keliver.treehouse)
      }
    }
    val jsMain by getting {
      dependencies {
        implementation(libs.keliver.compose)
        implementation(libs.keliver.treehouse.guest)
        implementation("dev.keliver:keliver-compose-gatea-compose:${libs.versions.keliver.get()}")
        implementation("dev.keliver:keliver-compose-gatea-protocol-guest:${libs.versions.keliver.get()}")
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
