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
import app.cash.zipline.loader.SignatureAlgorithmId

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

// Sign the Zipline manifest with an Ed25519 key. The host
// (host-android MainActivity / host-compose MainViewController) verifies
// this signature with the matching PUBLIC key before running any guest
// code — closing the "a MITM swaps the bundle" hole that
// `ManifestVerifier.NO_SIGNATURE_CHECKS` leaves open.
//
//   key name:    "keliver-sample-ed25519"  (must match the host verifier)
//   public key:  98ecb60bb1c418e771378d4451ddfb2f9c440e3ec29cf2f89850ded8f5190cea
//
// DEMO KEY ONLY. This private key is committed so the sample is
// reproducible out of the box. A real app generates its own pair
// (`java -cp <zipline-loader> … InternalJvmKt.generateEd25519KeyPair`, or
// the `zipline-cli generate-key-pair` command) and injects the private
// key from a secret at build time — e.g.
// `privateKeyHex.set(providers.gradleProperty("keliverSigningKey").get())`
// — never committing it. See sample/TESTING.md (C: signed-manifest).
zipline {
  signingKeys {
    create("keliver-sample-ed25519") {
      algorithmId.set(SignatureAlgorithmId.Ed25519)
      privateKeyHex.set("2d69a8f0d62f16b05aba4d1ef5bbbb74c6a1e7397825d0a2dd3a610c942357ad")
    }
  }
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
